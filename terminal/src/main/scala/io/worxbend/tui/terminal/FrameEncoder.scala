package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, BufferExport, Cell, CharWidth, Style}

/** Turns the difference between two frames into the ANSI text that moves the terminal from one to the other.
  *
  * Pure: it touches no terminal, no signal state and nothing mutable outside the call, so an instance may be shared and
  * `encode` may be called from any thread. [[JLine3Backend]] keeps one and calls it from the render thread.
  *
  * Separated from the backend because this is where a wrong frame comes from — the carry-over rules below are the
  * subtlest logic in the module, and as a method on the backend they could only be exercised by driving a whole JLine
  * terminal over streams.
  *
  * `colorDepth` decides how a [[io.worxbend.tui.core.Style]]'s colours are written; it is fixed for the lifetime of the
  * encoder because it is a property of the terminal.
  *
  * Visible to the whole `io.worxbend.tui` tree rather than to this package alone, because `runtime.AsciicastRecorder`
  * writes the very same frame diffs into a recording — the encoders stay here, in `terminal`, and the tiers above call
  * them. It is still not published API: nothing outside this repository can name it.
  */
private[tui] final class FrameEncoder(colorDepth: ColorDepth):

  /** The cell-level ANSI for everything that differs between `previous` and `next`, in row-major order.
    *
    * Cursor position, SGR state and the open hyperlink carry across cells, so each is re-emitted only where it actually
    * changes — that suppression is most of the reason a diffed frame is small enough to write in one go. Returns the
    * empty string when the frame is unchanged.
    *
    * `previous` must be what the terminal is actually showing; [[FrameBaseline.prepareFor]] hands one out only while
    * that holds, and answers [[FrameSource.RepaintAll]] otherwise, which is [[encodeAll]]'s job. `previous` and `next`
    * covering different rectangles is not a difference that can be encoded — that happens when the terminal was
    * resized, and the grid the old frame described no longer exists — so every cell of `next` is written instead.
    *
    * Style is written in two forms. The first painted cell of a frame gets the absolute sequence, which begins with a
    * reset, so nothing the terminal was left holding can leak into this frame. Every later style change on the same
    * frame gets only the attributes that moved (see [[Sgr.sgrDelta]]) — a run that differs from its predecessor in the
    * bold flag alone costs a few bytes rather than a restatement of both colours.
    */
  def encode(previous: Buffer, next: Buffer): String = write(Some(previous), next)

  /** Every cell of `next`, blanks included, as one positioned and styled run per row.
    *
    * What a frame drawn against an unknown screen needs — the first frame, the frame after a resize, a frame after
    * something else owned the terminal. A diff against a blank grid is *not* the same thing and is the bug this exists
    * to prevent: a cell that is blank in `next` differs from blankness in nothing, so it would never be written, and
    * whatever the older frame left in that column would stay on screen for the rest of the run.
    */
  def encodeAll(next: Buffer): String = write(None, next)

  /** The shared body of [[encode]] and [[encodeAll]]: `previous` is the frame on screen when there is one, and `None`
    * says nothing is known about the screen, which is what makes every cell of `next` get written.
    */
  private def write(previous: Option[Buffer], next: Buffer): String =
    val body                            = StringBuilder()
    var expectedX                       = -1
    var currentY                        = -1
    var currentStyle: Style             = Style.Default
    var currentLink: Option[String]     = None
    // Whether this frame has emitted an SGR sequence yet. The first one is written in the absolute form
    // ([[Sgr.sgr]], which opens with a reset), because the encoder cannot know what state the terminal was
    // left in by whatever was drawn before — a previous frame, the shell, a subprocess. After that anchor every style
    // change on the frame is written as a delta against the style the encoder itself last emitted, which is a handful
    // of bytes instead of a full restatement of every colour and flag.
    var frameOpened                     = false
    val paint: (Int, Int, Cell) => Unit =
      (x, y, cell) => {
        if y != currentY || x != expectedX then body ++= AnsiSequences.moveTo(x, y)
        // Both halves of the comparison earn their keep: reference equality catches the common case of one `Style`
        // value reused across a run of cells, and the structural compare catches effects such as `Effect.fadeIn`,
        // which hand every cell a freshly allocated but usually equal `Style`. Either way a run of same-styled cells
        // builds no sequence at all.
        if !frameOpened then
          body ++= Sgr.sgr(cell.style, colorDepth)
          currentStyle = cell.style
          frameOpened = true
        else if !((cell.style eq currentStyle) || cell.style == currentStyle) then
          body ++= Sgr.sgrDelta(currentStyle, cell.style, colorDepth)
          currentStyle = cell.style
        currentLink = carryLink(body, currentLink, cell.style.link)
        body ++= guarded(cell.symbol)
        currentY = y
        expectedX = x + advanceOf(next, x, y, cell)
      }
    previous match
      case Some(shown) if shown.area == next.area => shown.diff(next, paint)
      case _                                      => next.emitAll(paint)
    // a link left open would swallow everything drawn after this frame
    carryLink(body, currentLink, None)
    body.result()

  /** One row of `buffer` as styled ANSI text, with no cursor movement in it at all.
    *
    * [[encode]] is for a frame that owns the screen: it positions the cursor at each run of changed cells, which is
    * exactly wrong for text that is being *printed* into the terminal's scrollback, where the terminal itself decides
    * which line the text lands on. This writes only style sequences and glyphs, so the caller can print the result
    * followed by a newline the way it would print any other line of output.
    *
    * Trailing cells that are blank in the default style are dropped: a rendered block is padded to the full width of
    * the terminal, and a scrollback line carrying that padding is one whose background colour runs to the edge of the
    * window and stays there for as long as the line does. Continuation columns — the second half of a two-column
    * grapheme — are skipped, because the terminal paints them from the cell to their left.
    *
    * The result ends with an explicit style reset, so a colour set for the last cell cannot leak into whatever the
    * shell prints next. Rows outside `buffer.area` encode to the empty string.
    */
  def encodeRow(buffer: Buffer, y: Int): String =
    val end = lastInterestingColumn(buffer, y)
    if end < buffer.area.x then ""
    else
      val body                        = StringBuilder()
      var currentStyle                = ""
      var currentLink: Option[String] = None
      var x                           = buffer.area.x
      while x <= end do
        if !buffer.isContinuation(x, y) then
          val cell = buffer.get(x, y)
          val sgr  = Sgr.sgr(cell.style, colorDepth)
          if sgr != currentStyle then
            body ++= sgr
            currentStyle = sgr
          currentLink = carryLink(body, currentLink, cell.style.link)
          body ++= guarded(cell.symbol)
        x += 1
      carryLink(body, currentLink, None)
      body ++= AnsiSequences.ResetStyle
      body.result()

  /** Every row of `buffer` as styled text to be *printed*, rows separated by newlines — what print mode writes.
    *
    * Three things set it apart from [[encodeAll]], which writes a frame that owns the screen. There is no cursor
    * movement at all: the terminal decides which scrollback line each row lands on, so a `CUP` would be wrong there.
    * Style is written as a delta from the terminal's default rather than as the absolute, reset-first form — so a style
    * that renders identically to the default at `colorDepth` writes *nothing*, and under [[ColorDepth.NoColor]] a frame
    * whose styles are colour-only comes out as plain text with no escape sequence in it, which is what `NO_COLOR` asks
    * for and what a file the output is redirected into should hold. Rows are terminated with a style reset exactly when
    * they emitted a style, so the last cell's colour cannot leak into the shell prompt that follows.
    *
    * Trailing cells that would not be visible — blank, no background at this depth, no underline, reverse or
    * strikethrough — are dropped, because a row padded to the terminal's width is a scrollback line whose background
    * runs to the window's edge for as long as the line exists. Continuation columns of a wide grapheme are skipped, as
    * [[encodeRow]] skips them. Unlike [[encodeRow]] this does not restate the whole style per run, so it is the
    * encoding to reach for from outside the backend; `encodeRow` stays as the scrollback path `insertBefore` uses.
    *
    * The encoding is `core.BufferExport.ansiLines` under this depth's [[ColorDepth.ansiColors]]: one encoder, one SGR
    * codec and one colour policy shared with the exports, so a printed frame and an exported one cannot disagree.
    */
  def encodeLines(buffer: Buffer): String = BufferExport.ansiLines(buffer, colorDepth.ansiColors)

  /** The rightmost column of row `y` that carries anything worth writing, or one less than the row's first column when
    * the whole row is blank in the default style.
    */
  private def lastInterestingColumn(buffer: Buffer, y: Int): Int =
    var last = buffer.area.x - 1
    var x    = buffer.area.x
    while x < buffer.area.right do
      val cell = buffer.get(x, y)
      if !buffer.isContinuation(x, y) && (cell.symbol != " " || cell.style != Style.Default) then last = x
      x += 1
    last

  /** How many columns the terminal's cursor has moved after the cell at `(x, y)` was written.
    *
    * The answer comes from the buffer's own bookkeeping rather than from measuring the symbol again: a cell is two
    * columns wide exactly when the buffer reserved the column to its right as that grapheme's continuation, which
    * [[io.worxbend.tui.core.Buffer.isContinuation]] reports.
    *
    * A zero-width symbol — a lone combining mark, say, which the terminal draws on top of the character to its left —
    * advances the cursor by nothing at all, and answering `0` for it is what makes the *next* cell of the row differ
    * from `expectedX` and so be positioned with an explicit `moveTo`. Answering `1` there, as this used to, painted a
    * row of "a", a combining acute and "b" as three adjacent glyphs: the terminal's cursor was still in column 1 when
    * "b" arrived, so "b" and every later changed cell of the row landed one column left of where the buffer says it is.
    *
    * The continuation test comes first, and it is the only test a wide cell pays for: it is a plain read of the
    * buffer's bookkeeping, while `CharWidth.of` walks the symbol's grapheme clusters and copies the non-ASCII tail into
    * a fresh string per cluster — per cell, per frame, on the render hot path. The reorder answers every cell
    * identically because a zero-width cell can never own a continuation: the buffer reserves the column to a grapheme's
    * right only when the grapheme is two columns wide, so `isContinuation(x + 1, y)` already implies a non-zero width.
    *
    * The width question is asked of the symbol only to separate "no advance" from "some advance"; the size of a
    * non-zero advance still comes from the buffer, never from re-measuring, for the reason below. An empty symbol is
    * the one exception: [[guarded]] writes it as a blank, so the cursor is known to have moved one column for it.
    *
    * This used to be `math.max(1, CharWidth.of(cell.symbol))`, and the difference matters. A `Cell`'s symbol is meant
    * to hold one grapheme cluster, and the buffer reserves columns for it with a per-cluster measurement that can only
    * answer 0, 1 or 2. `CharWidth.of` measures a whole string and sums over every cluster in it, so a symbol that
    * somehow carried more than one cluster — several combining marks after their base character, or a long escape
    * sequence smuggled in as content — measured 3, 40 or 400 columns while the buffer had reserved one. `expectedX`
    * then pointed far to the right of the real cursor, the next changed cell's `moveTo` was suppressed because its `x`
    * "already matched", and the rest of the frame was painted into the wrong columns. ratatui hit this exact bug with a
    * 400-byte escape sequence in a cell; asking the buffer instead of re-measuring makes it unreachable by
    * construction.
    */
  private[terminal] def advanceOf(next: Buffer, x: Int, y: Int, cell: Cell): Int =
    if next.isContinuation(x + 1, y) then 2
    else if cell.symbol.isEmpty then 1
    else if CharWidth.of(cell.symbol) == 0 then 0
    else 1

  /** `symbol`, or a single blank if it is empty or contains a C0 or C1 control code (`ESC` included).
    *
    * An empty symbol is the blank `Cell.isBlank` says it is. `Buffer.set` already stores a `Cell("")` as a styled
    * one-column space, so a frame written through the buffer never carries one here; this is the belt to that brace,
    * because written verbatim an empty symbol claims no column at all and the terminal keeps showing whatever glyph was
    * there before, in a column the buffer — and every assertion over it — calls blank.
    *
    * The paragraph above is the column-tracking half of what happens when a `Cell`'s symbol is not the one printable
    * grapheme cluster it is meant to be; this is the byte half. `Buffer.writeString` already refuses to write a
    * zero-width or control cluster reached through ordinary text — see the `width > 0` guard in its private
    * `writeString` — but `Buffer.set` and a caller-supplied glyph parameter (a `Scrollbar` track symbol, a `LineGauge`
    * glyph) go straight to a `Cell` with no such filter. Nothing downstream of this method checks `cell.symbol` either:
    * it is concatenated straight into the ANSI stream this encoder builds. A control byte reaching that stream is
    * written to the terminal exactly as if the application had emitted it itself — indistinguishable from the escape
    * sequences this frame is otherwise the sole author of. Substituting a blank is the same answer `writeString` gives
    * a control cluster reached honestly: the column is claimed and nothing recognizable is drawn in it, rather than
    * smuggled control bytes reaching a real terminal.
    */
  private[terminal] def guarded(symbol: String): String =
    if symbol.isEmpty then " "
    else if CharWidth.isPrintableAscii(symbol) then symbol
    else if containsControl(symbol) then " "
    else symbol

  /** Whether `symbol` carries a C0 or C1 control code (`ESC` included) — [[AnsiSequences.isControl]], walked character
    * by character.
    *
    * The walk is a plain `charAt` loop rather than `codePoints().anyMatch(Character.isISOControl)`: this runs for every
    * non-ASCII cell of every frame, and the `IntStream` the code-point view allocates per call is a real cost on the
    * render hot path for what is, in the end, a `< 0x20 || == 0x7f || C1` test per char.
    */
  private def containsControl(symbol: String): Boolean =
    var index = 0
    var found = false
    while !found && index < symbol.length do
      if AnsiSequences.isControl(symbol.charAt(index)) then found = true
      index += 1
    found

  /** Emits the OSC-8 transitions that carry the hyperlink state from `open` to `next`, and answers `next`.
    *
    * A link is closed only when one was open, and opened only when the new cell has one, so a run of cells sharing a
    * link (or sharing no link) writes nothing. Passing `None` for `next` is how both encoders close the frame or row: a
    * link left open would swallow everything drawn afterwards.
    */
  private def carryLink(body: StringBuilder, open: Option[String], next: Option[String]): Option[String] =
    if next != open then
      if open.nonEmpty then body ++= AnsiSequences.LinkClose
      next.foreach(url => body ++= AnsiSequences.linkOpen(url))
    next

/** The two whole-frame encodings that leave the backend: what print mode and a no-TTY run write to standard output.
  *
  * The class above is not published API: the diff encoder's contract (`previous` is what the terminal shows) only holds
  * inside a backend or a recorder that tracks what was written. These two have no such precondition — a buffer in, text
  * out, callable from any thread — so they are the published face of the encoder.
  */
object FrameEncoder:

  /** [[FrameEncoder.encodeLines]] for a one-off encoder at `colorDepth`. */
  def encodeLines(buffer: Buffer, colorDepth: ColorDepth): String = FrameEncoder(colorDepth).encodeLines(buffer)

  /** The glyphs of `buffer` and nothing else, one string per row with trailing blanks trimmed — the accessible form of
    * a frame, for a screen reader, a log, or a `Plain` no-TTY run. Shares [[Backend.plainRows]]'s rules on wide
    * graphemes.
    */
  def plainLines(buffer: Buffer): Seq[String] = Backend.plainRows(buffer)

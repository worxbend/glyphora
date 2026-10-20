package io.worxbend.tui.core

import scala.collection.mutable.ArrayBuffer

/** Reads a stream of terminal output — text with SGR colour and attribute sequences and OSC 8 hyperlinks in it — back
  * into styled [[Line]]s, keeping its place between chunks.
  *
  * This is the inverse of the terminal module's SGR encoder, written for the output of *other* programs: a compiler, a
  * test runner or `git` run as a subprocess, whose colours should survive into a `log` or a paragraph instead of being
  * stripped or, worse, written to the screen as raw escape bytes. [[Text.fromAnsi]] is the one-shot form; this class is
  * for a caller that reads the output as it arrives, because a chunk boundary can fall anywhere — in the middle of an
  * escape sequence, in the middle of a line — and only a parser that remembers where it was can put the pieces back
  * together the way a terminal would have.
  *
  * [[feed]] hands back every line the chunk *completed* (terminated by `\n`) and keeps the unterminated remainder, so
  * `parser.feed(chunk).foreach(logState.append)` is the whole wiring for a log. [[finish]] returns that remainder as a
  * final line once the stream has ended. A parser is used for one stream from start to finish; the style in force at
  * the end of one chunk is the style the next chunk starts in, exactly as it would be on a terminal.
  *
  * What is recognised:
  *
  *   - '''SGR''' (`ESC [ … m`): the 16 named colours, the 256-colour and truecolour forms in both their `;` and `:`
  *     spellings, bold, dim, italic, underline including the `4:n` styled underlines and the SGR 58 underline colour,
  *     blink, rapid blink, reverse, hidden, crossed-out, and the reset codes for each of them. SGR 0 returns to
  *     [[Style.Default]] — silent, not [[Style.Reset]] — so the plain stretches of the output take whatever style the
  *     widget that draws the text gives them, the way plain text in that widget would.
  *   - '''OSC 8''' hyperlinks (`ESC ] 8 ; params ; uri ST`), carried on [[Style.link]]; an empty URI closes the link.
  *     The link is independent of SGR, as it is on a terminal: SGR 0 does not close it.
  *
  * Every other escape sequence — cursor movement, screen clearing, other OSC strings, DCS and APC payloads, two-byte
  * escapes — is recognised far enough to be skipped whole and then dropped, with one exception: '''erase in line'''
  * (`ESC [ K`, `ESC [ 1 K`, `ESC [ 2 K`), which a progress bar sends after its `\r` and which is applied to the line
  * being built. Control strings and CSI parameter text are limited to 4096 UTF-16 units: reaching the limit abandons
  * the malformed sequence and resumes ordinary text, rather than swallowing an unlimited remainder. CAN/SUB cancel
  * pending sequences; newline, carriage return and tab execute even inside CSI.
  *
  * Three controls move within the line the way a terminal's cursor would:
  *
  *   - a '''tab''' advances to the next eight-column stop — columns counted in display width through [[CharWidth]], so
  *     a wide character takes two — padding with spaces in the style in force;
  *   - a '''carriage return''' (`\r`) goes back to the first column, and the text after it *overwrites* the line cell
  *     by cell, keeping whatever it does not reach: `"100%\r5%"` reads `5%0%`, as it would on screen, and
  *     `"100%\r\u001b[K5%"` reads `5%`. A progress bar that redraws itself with `\r` therefore shows its latest state,
  *     and CRLF output still splits into clean rows. Overwriting half of a wide character blanks its other half;
  *   - a '''newline''' (`\n`) ends the line.
  *
  * Every other control character is dropped: a control occupies no column but one [[Cell]], and a text model that kept
  * one would draw the rest of its row one column out of place (see [[CharWidth.withoutControls]]).
  *
  * The widths of the lines produced are, as for every [[Line]], measured through [[CharWidth]]. Plain text with no
  * escape sequence or control in it comes out exactly as [[Text.raw]] would read it.
  *
  * '''Ownership.''' A mutable object owned by its caller and confined to one thread at a time — the thread reading the
  * subprocess, typically, with the finished lines then handed to the render thread. It holds no reference to anything
  * it has returned.
  */
final class AnsiParser:

  import AnsiParser.*

  // the style under construction: SGR and OSC 8 edit these fields, and `current` is rebuilt from them on each change
  private var fg: Option[Color]              = None
  private var bg: Option[Color]              = None
  private var modifiers: Modifiers           = Modifiers.None
  private var underlineStyle: UnderlineStyle = UnderlineStyle.None
  private var underlineColor: Option[Color]  = None
  private var link: Option[String]           = None

  /** The style of the run being accumulated in `run`. */
  private var current: Style = Style.Default
  private val run            = StringBuilder()
  private val spans          = ArrayBuffer.empty[Span]

  /** Where the next text lands while the line is being rewritten after a `\r`, or `-1` while text appends at its end.
    */
  private var cursor: Int = -1

  private var state: State  = State.Ground
  private val sequence      = StringBuilder()
  private var intermediates = 0
  private var stringLength  = 0
  private var columns       = 0
  private var tail          = ""

  /** Reads `chunk` and answers the lines it completed, in order. A line is complete when its `\n` has been read; text
    * after the last `\n` stays in the parser until the next chunk completes it or [[finish]] is called.
    */
  def feed(chunk: String): Seq[Line] =
    val completed = ArrayBuffer.empty[Line]
    var index     = 0
    while index < chunk.length do
      val c = chunk.charAt(index)
      if c == '\u0018' || c == '\u001a' then
        sequence.clear()
        state = State.Ground
      else
        state match
          case State.Ground          => ground(c, completed)
          case State.Escape          => escape(c, completed)
          case State.EscIntermediate =>
            if c >= 0x20 && c <= 0x2f then ()
            else if c >= 0x30 && c <= 0x7e then state = State.Ground
            else
              state = State.Ground
              ground(c, completed)
          case State.Csi             => csi(c, completed)
          case State.Osc             => controlString(c, keep = true)
          case State.ControlString   => controlString(c, keep = false)
          case State.StringEsc       =>
            if c == '\\' then endControlString()
            else
              // ESC inside an OSC that is not the ST terminator abandons the string and starts a fresh escape
              sequence.clear()
              state = State.Escape
              escape(c, completed)
      index += 1
    completed.toSeq

  /** Ends the stream: the unterminated remainder — possibly empty — as one last line. An escape sequence the stream
    * broke off in the middle of is dropped. The parser is back at the start of a line afterwards, in the style the
    * stream left in force.
    */
  def finish(): Line =
    state = State.Ground
    sequence.clear()
    endLine()

  private def ground(c: Char, completed: ArrayBuffer[Line]): Unit =
    if c == Esc then
      sequence.clear()
      state = State.Escape
    else if c == '\n' then completed += endLine()
    else if c == '\r' then carriageReturn()
    else if c == '\t' then tab()
    else if !Character.isISOControl(c) then run.append(c)

  private def escape(c: Char, completed: ArrayBuffer[Line]): Unit =
    c match
      case '['                         =>
        intermediates = 0
        state = State.Csi
      case ']'                         =>
        stringLength = 0
        state = State.Osc
      case 'P' | '_' | '^' | 'X'       =>
        stringLength = 0
        state = State.ControlString
      case _ if c >= 0x20 && c <= 0x2f => state = State.EscIntermediate
      case _ if c >= 0x30 && c <= 0x7e => state = State.Ground
      case _                           =>
        // not an escape sequence at all: the ESC is dropped and the character is read as ordinary text
        state = State.Ground
        ground(c, completed)

  private def csi(c: Char, completed: ArrayBuffer[Line]): Unit =
    if c >= 0x30 && c <= 0x3f then
      // a parameter byte after an intermediate is malformed; the sequence is still consumed up to its final byte
      sequence.append(c)
      if sequence.length >= MaxSequence then
        sequence.clear()
        state = State.Ground
    else if c >= 0x20 && c <= 0x2f then intermediates += 1
    else if c >= 0x40 && c <= 0x7e then
      if c == 'm' && intermediates == 0 then applySgr(sequence.result())
      else if c == 'K' && intermediates == 0 then eraseInLine(sequence.result())
      sequence.clear()
      state = State.Ground
    else if c == Esc then
      sequence.clear()
      state = State.Escape
    else if c >= 0x7f then
      sequence.clear()
      state = State.Ground
      ground(c, completed)
    else if c == '\n' || c == '\r' || c == '\t' then ground(c, completed)

  private def controlString(c: Char, keep: Boolean): Unit =
    if c == Bel then endControlString()
    else if c == Esc then state = State.StringEsc
    else
      stringLength += 1
      if stringLength >= MaxSequence then
        sequence.clear()
        state = State.Ground
      else if keep then sequence.append(c)

  private def endControlString(): Unit =
    // only an OSC keeps its payload, so a non-empty buffer is one; a DCS/APC/PM/SOS string leaves nothing to apply
    if sequence.nonEmpty then applyOsc(sequence.result())
    sequence.clear()
    state = State.Ground

  private def applyOsc(payload: String): Unit =
    if payload.startsWith("8;") then
      val afterCode = payload.drop(2)
      val separator = afterCode.indexOf(';')
      if separator >= 0 then
        val uri = afterCode.drop(separator + 1)
        link = if uri.isEmpty then None else Some(uri)
        restyle()

  private def applySgr(parameters: String): Unit =
    val params = if parameters.isEmpty then Array(Array("")) else parameters.split(";", -1).map(_.split(":", -1))
    if !params.head.head.exists(c => c == '?' || c == '<' || c == '=' || c == '>') then
      var index = 0
      while index < params.length do
        val sub  = params(index)
        val code = sub.head.toIntOption.getOrElse(if sub.head.isEmpty then 0 else -1)
        code match
          case 0                               => resetAttributes()
          case 1                               => set(Modifiers.Bold)
          case 2                               => set(Modifiers.Dim)
          case 3                               => set(Modifiers.Italic)
          case 4                               => underline(sub)
          case 5                               => set(Modifiers.Blink)
          case 6                               => set(Modifiers.RapidBlink)
          case 7                               => set(Modifiers.Reverse)
          case 8                               => set(Modifiers.Hidden)
          case 9                               => set(Modifiers.CrossedOut)
          case 21                              => underlineStyle = UnderlineStyle.Double
          case 22                              => clear(Modifiers.Bold | Modifiers.Dim)
          case 23                              => clear(Modifiers.Italic)
          case 24                              =>
            clear(Modifiers.Underline)
            underlineStyle = UnderlineStyle.None
          case 25                              => clear(Modifiers.Blink | Modifiers.RapidBlink)
          case 27                              => clear(Modifiers.Reverse)
          case 28                              => clear(Modifiers.Hidden)
          case 29                              => clear(Modifiers.CrossedOut)
          case _ if code >= 30 && code <= 37   => fg = Some(Named(code - 30))
          case 38                              =>
            val (color, consumed) = extendedColor(sub, params, index)
            color.foreach(c => fg = Some(c))
            index += consumed
          case 39                              => fg = None
          case _ if code >= 40 && code <= 47   => bg = Some(Named(code - 40))
          case 48                              =>
            val (color, consumed) = extendedColor(sub, params, index)
            color.foreach(c => bg = Some(c))
            index += consumed
          case 49                              => bg = None
          case 58                              =>
            val (color, consumed) = extendedColor(sub, params, index)
            color.foreach(c => underlineColor = Some(c))
            index += consumed
          case 59                              => underlineColor = None
          case _ if code >= 90 && code <= 97   => fg = Some(Named(code - 90 + 8))
          case _ if code >= 100 && code <= 107 => bg = Some(Named(code - 100 + 8))
          case _                               => () // unknown or unsupported: dropped
        index += 1
      restyle()

  /** The `4` family: `4` and `4:1` are the plain underline, `4:0` turns every underline off, `4:2`–`4:5` pick a styled
    * line, which draws on its own and leaves the plain flag alone — the reading the encoder's `withUnderlineStyle`
    * documents.
    */
  private def underline(sub: Array[String]): Unit =
    val selector = if sub.length > 1 then sub(1).toIntOption.getOrElse(-1) else 1
    selector match
      case 0 =>
        clear(Modifiers.Underline)
        underlineStyle = UnderlineStyle.None
      case 1 => set(Modifiers.Underline)
      case 2 => underlineStyle = UnderlineStyle.Double
      case 3 => underlineStyle = UnderlineStyle.Curly
      case 4 => underlineStyle = UnderlineStyle.Dotted
      case 5 => underlineStyle = UnderlineStyle.Dashed
      case _ => ()

  /** The colour named by an SGR 38/48/58 parameter, and how many *further* `;`-separated parameters it used up.
    *
    * Two spellings are in use. The `:` form keeps everything in one parameter — `38:5:n`, `38:2::r:g:b` with the
    * colour-space slot ISO 8613-6 reserves, or `38:2:r:g:b` without it — and consumes nothing further. The `;` form
    * spreads the same values over the following parameters — `38;5;n`, `38;2;r;g;b` — which this consumes so they are
    * not read as attribute codes of their own. A form with too few values yields no colour and consumes what it could.
    */
  private def extendedColor(sub: Array[String], params: Array[Array[String]], index: Int): (Option[Color], Int) =
    def int(value: String): Option[Int] = value.toIntOption
    if sub.length > 1 then
      val color = sub(1) match
        case "5" if sub.length >= 3 => int(sub(2)).flatMap(indexed)
        case "2" if sub.length >= 6 => rgb(int(sub(3)), int(sub(4)), int(sub(5)))
        case "2" if sub.length == 5 => rgb(int(sub(2)), int(sub(3)), int(sub(4)))
        case _                      => None
      (color, 0)
    else
      def param(offset: Int): Option[Int] =
        if index + offset < params.length then int(params(index + offset).head) else None
      param(1) match
        case Some(5) => (param(2).flatMap(indexed), math.min(2, params.length - 1 - index))
        case Some(2) => (rgb(param(2), param(3), param(4)), math.min(4, params.length - 1 - index))
        case _       => (None, 0)

  private def indexed(index: Int): Option[Color] =
    if index >= 0 && index <= 255 then Some(Color.Indexed(index)) else None

  private def rgb(r: Option[Int], g: Option[Int], b: Option[Int]): Option[Color] =
    for
      red   <- r
      green <- g
      blue  <- b
    yield Color.rgb(red, green, blue)

  private def set(flags: Modifiers): Unit   = modifiers = modifiers | flags
  private def clear(flags: Modifiers): Unit = modifiers = modifiers.without(flags)

  /** SGR 0: every attribute and colour back to silent. The hyperlink is not an SGR attribute and stays. */
  private def resetAttributes(): Unit =
    fg = None
    bg = None
    modifiers = Modifiers.None
    underlineStyle = UnderlineStyle.None
    underlineColor = None

  /** Rebuilds the style from the fields and, if it differs from the run's, closes the run as a span. */
  private def restyle(): Unit =
    val next = Style(
      fg = fg,
      bg = bg,
      modifiers = modifiers,
      link = link,
      underlineColor = underlineColor,
      underlineStyle = underlineStyle,
    )
    if next != current then
      flushRun()
      current = next

  private def flushRun(): Unit =
    if run.nonEmpty then
      val text = run.result()
      run.clear()
      if cursor < 0 then
        spans += Span(text, current)
        // Only the final cluster can join the next run (even across SGR or a split surrogate).
        columns -= CharWidth.of(tail)
        CharWidth.graphemeClusters(Iterator(tail, text)).foreach { cluster =>
          columns += CharWidth.of(cluster)
          tail = cluster
        }
      else
        val cells = LineCells.of(spans)
        cursor = cells.write(cursor, text, current)
        replaceSpans(cells)
        if cursor >= cells.width then cursor = -1

  private def replaceSpans(cells: LineCells): Unit =
    spans.clear()
    spans ++= cells.spans
    columns = cells.width
    tail = Line(spans.toVector).styledGraphemes(Style.Default).foldLeft("")((_, grapheme) => grapheme.cluster)

  /** Width of flushed text. Callers flush first; tab expansion never rescans the growing line. */
  private def lineWidth: Int = columns

  /** `\r`: back to the first column. An empty line has nothing to overwrite, so it stays in append mode. */
  private def carriageReturn(): Unit =
    flushRun()
    cursor = if lineWidth > 0 then 0 else -1

  /** `\t`: on to the next multiple of eight columns. Appending, the gap is spaces in the style in force; rewriting
    * after a `\r`, a tab moves over the cells without erasing them, and pads only past the end of the line.
    */
  private def tab(): Unit =
    flushRun()
    val width  = lineWidth
    val column = if cursor < 0 then width else cursor
    val target = (column / TabStop + 1) * TabStop
    if target < width then cursor = target
    else
      cursor = -1
      run.append(" " * (target - width))

  /** `ESC [ n K`: erase in line — `0` (the default) from the cursor to the end, `1` from the start through the cursor,
    * `2` the whole line. The cursor keeps its column, so text after an erase lands where it would on screen.
    */
  private def eraseInLine(parameters: String): Unit =
    val mode = if parameters.isEmpty then Some(0) else parameters.toIntOption
    mode.foreach { selected =>
      flushRun()
      val cells  = LineCells.of(spans)
      val column = if cursor < 0 then cells.width else cursor
      selected match
        case 0 => cells.truncate(column)
        case 1 => cells.blank(0, column + 1)
        case 2 =>
          cells.truncate(0)
          cells.padTo(column)
        case _ => ()
      replaceSpans(cells)
      cursor = if column >= cells.width then -1 else column
    }

  /** Closes the current line. A line with no spans gets one empty span, the shape [[Text.raw]] gives an empty row. */
  private def endLine(): Line =
    flushRun()
    cursor = -1
    val merged  = ArrayBuffer.empty[Span]
    val content = StringBuilder()
    var style   = spans.headOption.map(_.style).getOrElse(current)
    spans.foreach { span =>
      if span.style != style then
        merged += Span(content.result(), style)
        content.clear()
        style = span.style
      content.append(span.content)
    }
    merged += Span(content.result(), style)
    spans.clear()
    columns = 0
    tail = ""
    Line(merged.toVector)

object AnsiParser:

  /** Columns between tab stops, the terminal default. */
  private val TabStop = 8

  /** Malformed control sequences cannot retain arbitrary subprocess output indefinitely. */
  private val MaxSequence = 4096

  private val Esc: Char = '\u001b'
  private val Bel: Char = '\u0007'

  /** The sixteen named colours in SGR order: 30–37 (and 40–47) index the first eight, 90–97 (and 100–107) the rest. */
  private val Named: Vector[Color] = Vector(
    Color.Black,
    Color.Red,
    Color.Green,
    Color.Yellow,
    Color.Blue,
    Color.Magenta,
    Color.Cyan,
    Color.White,
    Color.BrightBlack,
    Color.BrightRed,
    Color.BrightGreen,
    Color.BrightYellow,
    Color.BrightBlue,
    Color.BrightMagenta,
    Color.BrightCyan,
    Color.BrightWhite,
  )

  /** Where the parser is in the escape grammar between two characters — and so between two chunks. */
  private enum State:
    /** Ordinary text. */
    case Ground

    /** An ESC has been read and the next character says which kind of sequence follows. */
    case Escape

    /** An `nF` escape (`ESC ( B` and friends): intermediates until a final byte, then dropped. */
    case EscIntermediate

    /** Inside `ESC [ … final`; only a final `m` with no intermediates is an SGR. */
    case Csi

    /** Inside `ESC ] … ST`, the payload kept for OSC 8. */
    case Osc

    /** Inside a DCS, APC, PM or SOS string, dropped whole. */
    case ControlString

    /** An ESC inside an OSC or control string: a following `\` is the ST terminator. */
    case StringEsc

/** The line an [[AnsiParser]] is rewriting after a `\r`, one entry per display column: a grapheme cluster on the column
  * it starts at, and an empty string on each further column a wide cluster covers. Built from the line's spans when an
  * overwrite needs it and turned back into spans straight after, so the append path never pays for it.
  */
private final class LineCells private (columns: ArrayBuffer[(String, Style)]):

  def width: Int = columns.size

  /** Writes `text` from `column` on, cluster by cluster, and answers the column after the last one written. */
  def write(column: Int, text: String, style: Style): Int =
    var at         = column
    val previous   = leadBefore(column).filter { lead =>
      val prior = columns(lead)._1
      CharWidth.graphemeClusters(prior + text).nextOption().exists(_ != prior)
    }
    val input      = previous.fold(text)(lead => columns(lead)._1 + text)
    val firstStyle = previous.fold(style)(lead => columns(lead)._2)
    previous.foreach(lead => at = lead)
    var first      = true
    CharWidth.graphemeClusters(input).foreach { cluster =>
      val w = CharWidth.of(cluster)
      if w == 0 then
        // a zero-width cluster joins the cluster before it, as it would have had the two arrived together
        leadBefore(at).foreach(lead => columns(lead) = (columns(lead)._1 + cluster, columns(lead)._2))
      else
        padTo(at)
        (at until math.min(at + w, width)).foreach(clearClusterAt)
        (at until at + w).foreach { index =>
          val entry = (if index == at then cluster else "", if first then firstStyle else style)
          if index < width then columns(index) = entry else columns += entry
        }
        at += w
      first = false
    }
    at

  /** Drops every column from `column` on, blanking a wide cluster the cut goes through. */
  def truncate(column: Int): Unit =
    if column < width then
      if column > 0 then clearClusterAt(column)
      columns.dropRightInPlace(width - column)

  /** Blanks the columns `from until until` (and any wide cluster they cut through) to spaces in their own style. */
  def blank(from: Int, until: Int): Unit =
    (from until math.min(until, width)).foreach { index =>
      clearClusterAt(index)
      columns(index) = (" ", columns(index)._2)
    }

  /** Extends the line with default-style spaces until it is `column` wide. */
  def padTo(column: Int): Unit =
    while width < column do columns += ((" ", Style.Default))

  /** The line as spans, adjacent columns of one style merged into one span. */
  def spans: Seq[Span] =
    val out                  = ArrayBuffer.empty[Span]
    val run                  = StringBuilder()
    var style: Option[Style] = None
    columns.foreach { (cluster, cellStyle) =>
      if cluster.nonEmpty then
        if !style.contains(cellStyle) then
          style.foreach(previous => out += Span(run.result(), previous))
          run.clear()
          style = Some(cellStyle)
        run.append(cluster)
    }
    style.foreach(previous => out += Span(run.result(), previous))
    out.toSeq

  /** Replaces the whole cluster covering `index` with spaces, so writing over part of a wide character never leaves the
    * other part half-drawn.
    */
  private def clearClusterAt(index: Int): Unit =
    leadAtOrBefore(index).foreach { lead =>
      var column = lead
      columns(column) = (" ", columns(column)._2)
      column += 1
      while column < width && columns(column)._1.isEmpty do
        columns(column) = (" ", columns(column)._2)
        column += 1
    }

  private def leadAtOrBefore(index: Int): Option[Int] =
    var column = math.min(index, width - 1)
    while column >= 0 && columns(column)._1.isEmpty do column -= 1
    Option.when(column >= 0)(column)

  private def leadBefore(index: Int): Option[Int] = if index <= 0 then None else leadAtOrBefore(index - 1)

private object LineCells:

  def of(spans: Iterable[Span]): LineCells =
    val columns = ArrayBuffer.empty[(String, Style)]
    Line(spans.toVector).styledGraphemes(Style.Default).foreach { grapheme =>
      columns += ((grapheme.cluster, grapheme.style))
      (1 until grapheme.width).foreach(_ => columns += (("", grapheme.style)))
    }
    new LineCells(columns)

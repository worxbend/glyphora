package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Event, Position, Rect, Size, Widget}

import org.jline.terminal.Terminal

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

/** A terminal a TUI can draw to and read input from.
  *
  * Implementations own the physical (or simulated) terminal: raw mode, the alternate screen, cursor visibility, and
  * translating [[Buffer]] diffs into whatever the device understands. Everything above this trait — runtime, widgets,
  * DSL — is backend-agnostic.
  *
  * I/O failures are values (`Either[BackendError, A]`) because callers can meaningfully degrade (log and continue vs.
  * abort); `throw` is reserved for genuine defects.
  *
  * `draw` flushes only the cells that changed since the previous `draw` call (diff-based updates); the first call after
  * construction, or after the buffer area changes, flushes everything.
  *
  * '''Threading and ownership.''' A backend belongs to the one loop that drives it, and every member runs on that
  * loop's render thread — the thread that calls [[readEvent]], and is therefore the one reader of the input stream —
  * with three exceptions that exist precisely so other threads can reach it: [[wake]], [[requestFullRedraw]] and
  * [[emergencyRestore]] are safe from any thread. Nothing else is synchronised: a second thread calling `draw` tears
  * the frame on the wire, and one calling `readEvent` interleaves its reads into sequences the terminal never sent. The
  * per-member notes below ("Must run on the render thread", "Safe from any thread") are instances of this rule, not
  * exceptions to it.
  */
trait Backend:
  def size: Either[BackendError, Size]
  def draw(buffer: Buffer): Either[BackendError, Unit]
  def enableRawMode(): Either[BackendError, Unit]
  def disableRawMode(): Either[BackendError, Unit]
  def enterAlternateScreen(): Either[BackendError, Unit]
  def leaveAlternateScreen(): Either[BackendError, Unit]
  def enableMouseCapture(): Either[BackendError, Unit]
  def disableMouseCapture(): Either[BackendError, Unit]
  def hideCursor(): Either[BackendError, Unit]
  def showCursor(): Either[BackendError, Unit]

  /** What this terminal is known to support, established once when raw mode was entered.
    *
    * The default answers [[TerminalCapabilities.unknown]] — nothing was established — which every caller must read as
    * "use the feature anyway". See [[TerminalCapabilities]] for why silence is the optimistic answer and only an
    * explicit denial turns anything off.
    *
    * Read-only, and stable for the life of a raw-mode session: a backend probes once and hands out the value it got.
    */
  def capabilities: TerminalCapabilities = TerminalCapabilities.unknown

  /** Asks the terminal to report key *releases* as well as presses (kitty keyboard protocol flag 2).
    *
    * Answering `Right(())` promises nothing about whether releases actually arrive: a backend with no such protocol
    * takes the default, does nothing, and simply never emits [[io.worxbend.tui.core.Event.KeyRelease]]. Callers must
    * treat releases as an enrichment the terminal may or may not provide, never as an event a feature depends on.
    *
    * There is no matching "disable". The flag is pushed onto the terminal's own keyboard-mode stack and
    * [[disableRawMode]] pops it with everything else, so a run cannot leave a terminal reporting releases into the
    * user's shell.
    */
  def enableKeyEventTypes(): Either[BackendError, Unit] = Right(())

  /** The window in character cells *and*, when the terminal will say, in device pixels.
    *
    * What it is for: anything that has to know the *shape* of a cell rather than how many there are. A picture drawn
    * with half-block glyphs assumes a cell is twice as tall as it is wide, and on a terminal where that is not true the
    * picture comes out stretched; `windowSize().map(_.cellAspectRatio)` is how an application finds out instead of
    * assuming.
    *
    * The default answers cells only, with `pixels` empty. That is deliberately not a fabricated geometry: a backend
    * that cannot ask its terminal has to say "unknown" so the caller falls back to its own assumption rather than
    * trusting a number nobody measured. Every backend written before this method existed keeps compiling and keeps
    * behaving exactly as it did.
    *
    * A backend that does ask pays for a round trip on the input stream, with all the constraints
    * [[queryCursorPosition]] documents — render thread only, and a terminal that does not implement the query simply
    * never answers. Callers that draw every frame should ask once and hold the answer, refreshing it on a resize.
    */
  def windowSize: Either[BackendError, WindowSize] = size.map(cells => WindowSize(cells, None))

  /** Starts mouse capture in `mode`, which decides whether pointer motion with no button held is reported at all.
    *
    * The no-argument [[enableMouseCapture]] is `MouseCaptureMode.Buttons`. Overriding this one is what lets an app ask
    * for `MouseCaptureMode.AllMotion` and receive [[io.worxbend.tui.core.MouseEventKind.Moved]] — hover. The default
    * body requests buttons-only for either capturing mode, so a backend written before all-motion tracking existed
    * keeps compiling and keeps behaving exactly as it did. `MouseCaptureMode.Off` is [[disableMouseCapture]].
    */
  def enableMouseCapture(mode: MouseCaptureMode): Either[BackendError, Unit] =
    mode match
      case MouseCaptureMode.Off => disableMouseCapture()
      case _                    => enableMouseCapture()

  /** Parks the terminal's own cursor — the hardware caret — on the zero-based cell `position`.
    *
    * This is the caret the operating system knows about: an input method editor (IME, the software that turns a
    * sequence of keystrokes into a Chinese, Japanese or Korean character) anchors its candidate popup to it, and a
    * screen reader reports it as the insertion point. It is *not* the highlighted cell a text widget paints into the
    * [[Buffer]]; a focused text field normally wants both, because the painted block is what a sighted user sees and
    * the hardware caret is what everything else follows.
    *
    * Position only: visibility stays with [[hideCursor]] and [[showCursor]]. Before this method existed the caret was
    * hidden once at start-up and then left wherever the last cell of the frame diff happened to leave it; now a caller
    * that wants a visible caret moves it here and calls `showCursor()`, and one that wants none calls `hideCursor()`
    * without having to move anything.
    *
    * Call it *after* [[draw]], on the render thread: a frame flush is a stream of cursor moves, so a caret parked
    * before the flush is walked away from by the flush itself. A position outside the terminal is clamped by the device
    * rather than rejected here, so callers that care must bound it against [[size]] themselves.
    *
    * The default is a no-op for backends with no real terminal.
    */
  def setCursorPosition(position: Position): Either[BackendError, Unit] =
    val _ = position
    Right(())

  /** Asks the terminal where its cursor currently is, and waits up to `timeout` for the answer.
    *
    * A round trip, not a lookup: the request is a Device Status Report (`ESC[6n`) written to the terminal, and the
    * reply comes back on the same stream the user's keystrokes travel on. That has three consequences a caller must
    * know about.
    *
    *   - It must run on the render thread, the one thread allowed to read input. A query racing a [[readEvent]] would
    *     hand the reply to the key decoder, where it decodes to nothing and is dropped.
    *   - A key pressed while the query is in flight arrives before the reply. Those keys are held and delivered by the
    *     following [[readEvent]] calls in the order they were typed — up to a bounded queue (256 events per query; past
    *     that the oldest are dropped, which only a terminal flooding input during an unbounded-timeout query can reach)
    *     — but the reply is not instant, and an app that queries every frame will feel it.
    *   - A terminal that does not implement the report never answers at all. That is the ordinary outcome, not a
    *     defect, and it is why there is a timeout rather than a blocking read.
    *
    * The position is zero-based, in the same space as every other [[Position]] in glyphora; the wire format is
    * one-based and is converted once, in the decoder.
    *
    * What it is for: anchoring an *inline* viewport. An app that draws below the shell prompt rather than taking the
    * alternate screen has to know which row the prompt left the cursor on, or it draws over the user's scrollback.
    *
    * The default reports [[BackendError.UnsupportedTerminal]]. A backend with no terminal cannot answer, and a guessed
    * origin — the top-left corner, say — is the specific wrong answer that puts an inline UI over the output above it.
    */
  def queryCursorPosition(timeout: Duration): Either[BackendError, Position] =
    val _ = timeout
    Left(BackendError.UnsupportedTerminal("this backend cannot report the cursor position"))

  /** Asks the terminal itself to shift the rows of `region` up by `lines`, instead of repainting them.
    *
    * What this is for. A list of forty rows scrolled by one costs, through the ordinary frame diff, forty rows of
    * changed cells — every row now holds what the row below it held. The terminal can do the same job with one escape
    * sequence: confine scrolling to the band (DECSTBM), scroll it (SU), and the only row the application still has to
    * write is the one newly exposed at the bottom. On a slow link that is the difference between a scroll that keeps up
    * with a held-down arrow key and one that does not.
    *
    * Two things to know before reaching for it. Rows leaving the top of a *region* are discarded — they do not enter
    * the terminal's scrollback the way rows leaving the top of the whole screen do; [[appendLines]] is the operation
    * for that. And the region ends at `region.bottom` inclusive, so a caller that scrolls a list must pass the list's
    * own rows and not the panel's, or it will move the border too.
    *
    * The backend's diff baseline is adjusted to match, so the next [[draw]] correctly writes only the newly exposed
    * rows. Must run on the render thread. `lines <= 0` is a successful no-op, so a caller computing a delta needs no
    * guard.
    *
    * The default reports [[BackendError.UnsupportedTerminal]] rather than succeeding silently: a caller that cannot
    * scroll this way has to know, because its fallback is to repaint the rows itself, and a no-op that claimed success
    * would leave the stale rows on screen.
    */
  def scrollRegionUp(region: RowRange, lines: Int): Either[BackendError, Unit] =
    Backend.scrollUnsupported(region, lines)

  /** As [[scrollRegionUp]], but downward: the rows of `region` move down by `lines`, `lines` blank rows appear at its
    * top, and the rows pushed off its bottom are discarded.
    *
    * This is a list scrolling *backwards* — the user pressing Up at the top of the visible window — and it is the
    * direction with no consolation prize at all: rows leaving the bottom of the screen have nowhere to go, whereas rows
    * leaving the top of the whole screen at least reach the scrollback.
    */
  def scrollRegionDown(region: RowRange, lines: Int): Either[BackendError, Unit] =
    Backend.scrollUnsupported(region, lines)

  /** Asks the terminal emulator to resize its text area to `size`.
    *
    * Named `requestSize` rather than `setSize`, and the asymmetry with the read-only [[size]] is the point: asking is
    * not the same as getting. Most emulators disable window manipulation by default and ignore the request without
    * saying so, the ones that honour it apply their own clamping to the screen and to their configured limits, and a
    * tiling window manager overrules both. Success here means the request was written and nothing more.
    *
    * So [[size]] and `Event.Resize` remain the only truth about how big the terminal is. A caller must not assume the
    * requested size took effect, and must not wait for a resize event that may never arrive.
    *
    * `size` must have strictly positive width and height; a zero or negative request is a defect, not a runtime
    * failure, so it is rejected with `IllegalArgumentException` rather than a [[BackendError]]. Must run on the render
    * thread. The default is a successful no-op for backends with no emulator to ask.
    */
  def requestSize(size: Size): Either[BackendError, Unit] =
    val _ = size
    Right(())

  /** Sets whether the terminal's own caret blinks (the DECSET 12 mode).
    *
    * Cosmetic, and a companion to [[setCursorPosition]] rather than a replacement for [[hideCursor]]: a form can blink
    * the caret in the field being typed into and hold it steady while the app is idle, which reads as "the app is
    * waiting for you" without drawing anything.
    *
    * Best-effort in the strongest sense. Terminals that do not implement DECSET 12 ignore the sequence; terminals that
    * encode blink into the cursor *shape* may overrule it; and some users configure their emulator to ignore it on
    * purpose. Success here means the request was written, never that the caret changed — so no widget's correctness may
    * depend on it. Must run on the render thread. Blinking is the DEC default, so a backend that lets an app turn it
    * off is responsible for turning it back on when the app exits.
    *
    * The default is a successful no-op for backends with no real caret.
    */
  def setCursorBlink(blinking: Boolean): Either[BackendError, Unit] =
    val _ = blinking
    Right(())

  /** Selects the shape the terminal draws its hardware cursor in — see [[CursorShape]].
    *
    * The affordance this exists for is a modal editor: a block cursor in command mode and a bar in insert mode is how a
    * user knows which mode they are in without reading a status line.
    *
    * The shape is process-global terminal state, not per-frame state, so set it when the mode changes rather than on
    * every draw, and expect the backend to hand it back to [[CursorShape.Default]] when the app closes or is suspended.
    * A terminal that does not implement DECSCUSR ignores the request, so this answers `Right(())` whether or not
    * anything visibly changed; the default here is likewise a no-op for backends with no real terminal.
    */
  def setCursorShape(shape: CursorShape): Either[BackendError, Unit] =
    val _ = shape
    Right(())

  /** Asks the terminal to draw the mouse pointer as `shape` while it is over the window (OSC 22) — see
    * [[PointerShape]].
    *
    * Pointer shape is terminal state, not frame state: set it when the thing under the pointer changes, not on every
    * draw, and hand it back to [[PointerShape.Default]] before the app exits — the JLine backend does so on `close()`
    * and across a handover, as it does for the caret's shape. A terminal without OSC 22 ignores the request, and a
    * backend whose capabilities deny it (`capabilities.pointerShapes`) writes nothing and still answers `Right(())`:
    * success means "nothing went wrong", never "the pointer changed". Must run on the render thread. The default is a
    * no-op for backends with no pointer to shape.
    */
  def setPointerShape(shape: PointerShape): Either[BackendError, Unit] =
    val _ = shape
    Right(())

  /** Raises a desktop notification with `title` and `body` through whichever notification dialect the terminal speaks
    * (OSC 9, OSC 777 or OSC 99 — see [[DesktopNotificationProtocol]]).
    *
    * For telling a user who has switched to another window that something finished. Best-effort in every direction: a
    * terminal that speaks none of the dialects ignores it, one inside a multiplexer never sees it (the capability
    * `desktopNotifications` is `No` there and nothing is written), and the operating system may suppress it. Success
    * means the write went out. Must run on the render thread. The default is a no-op.
    */
  def notifyDesktop(title: String, body: String): Either[BackendError, Unit] =
    val _ = (title, body)
    Right(())

  /** Shows `progress` on the terminal's tab or the taskbar (OSC 9;4): a determinate fraction in `0.0..1.0`, or `None`
    * to clear it.
    *
    * Terminal state like [[setPointerShape]], so emit it when the fraction changes and clear it on the way out — the
    * JLine backend clears it on `close()` when something was shown. A terminal without the indicator ignores it, a
    * backend whose capabilities deny it (`capabilities.taskbarProgress`) writes nothing, and both answer `Right(())`.
    * Must run on the render thread. The default is a no-op.
    */
  def setTaskbarProgress(progress: Option[Double]): Either[BackendError, Unit] =
    val _ = progress
    Right(())

  /** [[setTaskbarProgress]] with the protocol's other states: error, indeterminate and paused — see [[TaskbarState]].
    *
    * The same terminal state under the same rules. The default shows the state by its fraction through
    * [[setTaskbarProgress]] (an indeterminate bar reads as cleared), so a backend written before this method existed
    * keeps working with the subset it can draw. Must run on the render thread.
    */
  def setTaskbarState(state: TaskbarState): Either[BackendError, Unit] = setTaskbarProgress(state.progress)

  /** Reserves `rows` lines at the bottom of the *primary* screen for an app that is not taking the alternate screen.
    *
    * The complement of [[enterAlternateScreen]]. Instead of switching to a blank second screen, this scrolls whatever
    * the shell has already printed up by `rows` lines, so those lines are free for the app to draw into and everything
    * above them stays visible. On exit the app's last frame is left where it is rather than being wiped, which is the
    * point: an inline UI ends up in the user's scrollback like any other command's output.
    *
    * The default is a no-op, for a backend with no real screen to scroll — an inline run against it simply composes
    * into the bottom rows of the size it reports.
    */
  def reserveInlineRows(rows: Int): Either[BackendError, Unit] =
    val _ = rows
    Right(())

  /** Asks the next [[close]] of an inline run to keep only the top `rows` rows of the strip it reserved, and to park
    * the cursor on the row below them instead of below the whole strip.
    *
    * For a run whose last frame is shorter than the strip it needed while live — a prompt that asked with a list of
    * options and answers with one summary line. The rows below the kept ones must already be blank in the last frame
    * drawn; this only moves where the shell's next output starts, so those blank rows are reused rather than left as a
    * gap. A value at or above the strip's height keeps the whole strip, which is what closing does without this call.
    *
    * The default is a no-op, for a backend with no real screen.
    */
  def keepInlineRows(rows: Int): Either[BackendError, Unit] =
    val _ = rows
    Right(())

  /** Blocks up to `timeout` for the next input event; `Right(None)` means nothing arrived.
    *
    * `timeout` must be **strictly positive**, or infinite to block until an event arrives. Zero is rejected rather than
    * treated as "poll once": the underlying JLine reader treats a non-positive timeout as an unbounded blocking read,
    * so a zero here would wedge the event loop until the user happened to press a key.
    *
    * `Right(None)` covers an elapsed timeout, input that decoded to nothing (a device-attributes reply, a torn
    * sequence) and a read cut short by [[wake]] — callers must treat it as "no event this round", never as
    * end-of-input. Render thread only: this is the one reader of the input stream.
    */
  def readEvent(timeout: Duration): Either[BackendError, Option[Event]]

  /** Interrupts an in-flight [[readEvent]] so the caller can re-check its own state promptly. Safe from any thread.
    *
    * Without this, work queued onto the render thread from a background thread (an `Async` result, a timer) is
    * invisible until the current poll expires. The default is a no-op for backends whose reads are already cheap to
    * wait out.
    */
  def wake(): Unit = ()

  /** Restores the terminal by the shortest path available, for callers that may be racing the backend's own teardown.
    *
    * Unlike `close()` this makes no attempt to be tidy and keeps no state: it exists for a JVM shutdown hook, where the
    * normal machinery may already have been torn down underneath it. Must be idempotent, must never throw, and — a
    * shutdown hook is never the render thread — must be safe from any thread. The default is a no-op for backends with
    * no real terminal to restore.
    */
  def emergencyRestore(): Unit = ()

  /** Copies `text` to the system clipboard via the OSC 52 terminal sequence.
    *
    * Support is terminal-dependent (and often opt-in for security reasons); terminals that don't understand OSC 52
    * ignore the sequence, so this is best-effort and reports success as long as the write itself succeeds. The default
    * implementation is a no-op for backends without a real terminal.
    */
  def copyToClipboard(text: String): Either[BackendError, Unit] =
    val _ = text
    Right(())

  /** Sets the terminal's window or tab title (the OSC 2 sequence).
    *
    * Best-effort, like [[copyToClipboard]]: a terminal that does not understand OSC 2 ignores the sequence, so success
    * here means "the write went out", not "the title changed". Where the terminal supports XTerm's title stack the
    * previous title is pushed before the first change and popped by `close()`, so the shell's own title comes back when
    * the app exits.
    *
    * The default implementation is a no-op for backends without a real terminal.
    */
  def setTitle(title: String): Either[BackendError, Unit] =
    val _ = title
    Right(())

  /** Temporarily hands the real terminal back so `body` can own it — leave the alternate screen, drop raw mode and
    * mouse capture, run `body` (e.g. launch `$EDITOR`), then restore the app's screen and force a full repaint. Must
    * run on the render thread. The default just runs `body` without touching the terminal, so headless/basic backends
    * stay correct.
    */
  def suspend[A](body: => A): Either[BackendError, A] =
    Right(body)

  /** Emits `lines` into the terminal's scrollback *above* the live UI (like Bubble Tea's `tea.Println` / ratatui's
    * `insert_before`) — durable log lines that remain after the app exits. Must run on the render thread. The default
    * is a no-op.
    */
  def printAbove(lines: Seq[String]): Either[BackendError, Unit] =
    val _ = lines
    Right(())

  /** [[printAbove]] with styling: renders `widget` into a `height`-row block and emits *that* into the scrollback above
    * the live UI, colours, bold and hyperlinks included.
    *
    * `printAbove` takes plain strings and strips every control sequence out of them before they reach the terminal,
    * which is the right thing to do with text of unknown provenance and also means an inserted line can carry no style
    * at all — no coloured log level, no bold prefix, no link. Here the caller draws instead of printing: `widget` is
    * handed a [[io.worxbend.tui.core.Buffer]] covering `Rect(0, 0, terminalWidth, height)` and whatever it paints is
    * what lands in the scrollback. It is the counterpart of ratatui's `insert_before`.
    *
    * The block is emitted once, at the moment of the call; it does not become part of any later frame and is not
    * repainted on a resize, exactly like the shell output above it. Must run on the render thread. A `height` of zero
    * or less inserts nothing and succeeds, so a caller computing a height from its content needs no guard.
    *
    * The default renders the block and then hands its rows to [[printAbove]] as plain text, so a backend that only
    * knows how to write strings stays correct and loses only the styling. Backends that can do better override it.
    */
  def insertBefore(height: Int, widget: Widget): Either[BackendError, Unit] =
    if height <= 0 then Right(())
    else size.flatMap(sz => printAbove(Backend.plainRows(Backend.renderBlock(sz.width, height, widget))))

  /** Erases part of the screen, as `kind` describes.
    *
    * Must run on the render thread. It also invalidates the diff baseline through [[requestFullRedraw]], because after
    * an erase the terminal no longer shows what the last flushed frame described, and a diff against that frame would
    * leave every unchanged cell blank. The default is a successful no-op for a backend with no screen to erase.
    */
  def clearRegion(kind: ClearType): Either[BackendError, Unit] =
    val _ = kind
    Right(())

  /** Writes `sequence` to the terminal exactly as given, without diffing it, encoding it or recording it as part of a
    * frame.
    *
    * This is the partner of [[io.worxbend.tui.core.DiffDirective.Skip]] and the only reason it exists. A terminal image
    * protocol — Sixel, the kitty graphics protocol, iTerm2 inline images — paints a rectangle with an escape sequence
    * that has no cell-by-cell representation, so the two halves of showing a picture are: reserve the columns in the
    * frame so the diff stops flushing over them, then hand the payload to the terminal through this method.
    *
    * Nothing is validated or stripped. That is the difference from [[printAbove]], which strips control sequences out
    * of text of unknown provenance: here the control sequences *are* the payload, so a caller passing text it did not
    * build itself is handing the terminal whatever that text says. Must run on the render thread, and the caller is
    * responsible for leaving the cursor and the terminal modes as it found them — this backend's picture of the screen
    * does not change, so anything the sequence draws outside the reserved columns is overwritten by the next frame only
    * if that frame happens to change those cells.
    *
    * The default reports [[BackendError.UnsupportedTerminal]] rather than succeeding silently, because a caller whose
    * picture never appeared needs to know that the backend never even tried.
    */
  def writeRaw(sequence: String): Either[BackendError, Unit] =
    val _ = sequence
    Left(BackendError.UnsupportedTerminal("this backend cannot pass raw escape sequences through to a terminal"))

  /** Discards what this backend believes is currently on screen, so the next [[draw]] writes every cell again.
    *
    * `draw` is diff-based: it emits only the cells that differ from the frame it last flushed. That is correct for
    * exactly as long as nothing but this backend writes to the terminal. When something else does — a subprocess
    * started outside [[suspend]], a library that printed to the real stdout, a terminal that dropped its screen on its
    * own — the picture on screen and the backend's baseline disagree, and every cell the app would have redrawn to the
    * value it already holds stays unwritten. The corruption then survives until the app happens to change that exact
    * cell. Calling this says "assume nothing on screen survived", and the next frame repaints in full.
    *
    * Safe to call from any thread, and idempotent: two calls before the next frame are one repaint, not two. It does
    * not itself schedule a frame — a caller that is not already drawing every tick must ask for one. The default is a
    * no-op for backends that keep no such baseline.
    */
  def requestFullRedraw(): Unit = ()

  /** Scrolls the terminal up by `n` rows: the top `n` rows leave the screen into the terminal's scrollback and `n`
    * blank rows appear at the bottom.
    *
    * This is the primitive an *inline* viewport is built from — a UI that lives below the shell prompt on the primary
    * screen rather than taking over the whole terminal. Reserving room for such a UI is "scroll the shell up by the
    * height I need"; letting a finished chunk of output become permanent scrollback is the same operation. Where
    * [[printAbove]] emits text the backend formats for you, this only makes room, and the caller then draws into it.
    *
    * Meaningless on the alternate screen, which has no scrollback: call it only while the primary screen is current.
    * Must run on the render thread. `n <= 0` is a no-op rather than a failure, so a caller computing a delta needs no
    * guard of its own. The default is a no-op for backends with no real terminal.
    */
  def appendLines(n: Int): Either[BackendError, Unit] =
    val _ = n
    Right(())

  /** Restores the terminal and releases everything this backend owns.
    *
    * Fallible like every other operation here, because the failure it can report is the most user-visible one the
    * library has: a terminal handed back in raw mode, on the alternate screen, or with the cursor hidden leaves the
    * user's shell unusable and gives no hint why. The returned value is the *first* step that failed; the remaining
    * steps are still attempted, since a half-restored terminal is worse than a fully attempted one.
    *
    * Must be idempotent: the runner closes the backend on its normal teardown path and a JVM shutdown hook may close it
    * again.
    */
  def close(): Either[BackendError, Unit]

private[terminal] object Backend:

  /** Runs `body`, catching what it throws as [[BackendError.Io]] — the failure shape every fallible backend operation
    * reports in.
    *
    * Lives here, rather than as a private helper on each JLine-backed collaborator, so all of them raise the identical
    * failure: a closed stream or a failed write must read the same way from `JLine3Backend.draw` and from
    * `ScrollRegions.scroll`, not pick up a per-class copy that can drift.
    */
  private[terminal] def attempt[A](body: => A): Either[BackendError, A] =
    try Right(body)
    catch case NonFatal(error) => Left(BackendError.Io(error))

  /** The window in cells of a JLine terminal, as [[Backend.size]] reports it.
    *
    * One owner for the JLine `Terminal.Size` → [[Size]] conversion, because the backend and its collaborators all ask
    * the same question and a conversion written twice can drift by an argument order.
    */
  private[terminal] def sizeOf(terminal: Terminal): Size =
    val jlineSize = terminal.getSize
    Size(jlineSize.getColumns, jlineSize.getRows)

  /** Enforces the strictly-positive (or infinite) timeout that [[Backend.readEvent]] documents.
    *
    * Lives here so every implementation raises the identical failure: JLine reads a non-positive timeout as an
    * unbounded blocking read, so a zero that slipped through one backend and not the other would wedge the event loop
    * in production while the headless tests kept passing.
    *
    * `> Zero` rather than a finiteness test: it accepts `Duration.Inf` (block until an event arrives) and rejects
    * `Duration.MinusInf`, which would otherwise take the blocking branch and never return.
    */
  def requirePositiveTimeout(timeout: Duration): Unit =
    require(timeout > Duration.Zero, s"readEvent timeout must be positive or infinite, got $timeout")

  /** Enforces the strictly-positive size that [[Backend.requestSize]] documents.
    *
    * Lives here so every implementation raises the identical failure, for the same reason as
    * [[requirePositiveTimeout]]: a zero or negative size is a defect in the caller, and it must read the same way
    * whichever backend rejects it.
    */
  def requirePositiveSize(size: Size): Unit =
    require(size.width > 0 && size.height > 0, s"requestSize needs a positive size, got $size")

  /** The block [[Backend.insertBefore]] inserts: `widget` rendered into `Rect(0, 0, width, height)`.
    *
    * One owner for the geometry, because all three implementations of `insertBefore` differ only in what they do with
    * the finished buffer — plain rows, encoded rows, or the buffer itself — not in how it is drawn.
    */
  private[terminal] def renderBlock(width: Int, height: Int, widget: Widget): Buffer =
    val area   = Rect(0, 0, width, height)
    val buffer = Buffer(area)
    widget.render(area, buffer)
    buffer

  /** The text of `buffer`, one string per row, with trailing blanks removed.
    *
    * How [[Backend.insertBefore]]'s default implementation flattens a rendered block back down to the plain lines
    * [[Backend.printAbove]] takes. Styling is dropped — that is the whole point of the fallback — but the glyphs and
    * their columns are kept.
    *
    * Continuation columns are skipped rather than printed. A two-column grapheme (a CJK ideograph, most emoji) occupies
    * two cells, and the second one is a reserved blank the terminal never draws: writing it out would push everything
    * after it one column to the right. Trailing blanks go because a row is padded to the terminal's full width, and a
    * scrollback line that carries that padding is a line whose background bleeds to the edge of the window.
    */
  def plainRows(buffer: Buffer): Seq[String] =
    val rows = Seq.newBuilder[String]
    var y    = buffer.area.y
    while y < buffer.area.bottom do
      val row = StringBuilder()
      var x   = buffer.area.x
      while x < buffer.area.right do
        if !buffer.isContinuation(x, y) then row ++= buffer.get(x, y).symbol
        x += 1
      rows += trimRightBlanks(row.result())
      y += 1
    rows.result()

  /** `text` without its trailing blanks. */
  private def trimRightBlanks(text: String): String =
    text.reverse.dropWhile(_ == ' ').reverse

  /** The answer both scroll-region defaults give: this backend has no scroll region to offer.
    *
    * A failure rather than a silent success, because the caller's fallback is to repaint the rows itself. A no-op that
    * reported `Right(())` would leave the stale rows on screen and give nothing to notice it by.
    */
  private def scrollUnsupported(region: RowRange, lines: Int): Either[BackendError, Unit] =
    val _ = (region, lines)
    Left(BackendError.UnsupportedTerminal("this backend has no scroll region"))

/** An inclusive, zero-based band of terminal rows — the unit [[Backend.scrollRegionUp]] operates on.
  *
  * Rows only, and deliberately not a [[io.worxbend.tui.core.Rect]]: a `Rect` carries columns, and the terminal's own
  * scroll region has no column margins on the emulators this library targets. Handing one over would promise a
  * horizontal bound that the sequence then quietly ignores, moving the full width of every row inside the band.
  *
  * `bottom` is inclusive because that is how DECSTBM reads and how every reference for it is written; an exclusive
  * bound here would put an off-by-one between this type and the specification it exists to express.
  */
final case class RowRange(top: Int, bottom: Int):
  require(top >= 0, s"a row range starts at or after row 0, got $top")
  require(bottom >= top, s"a row range ends at or after it starts, got $top..$bottom")

  /** How many rows the band covers — at least one, since `bottom` is inclusive. */
  def height: Int = bottom - top + 1

/** Which part of the screen [[Backend.clearRegion]] erases.
  *
  * Everything except `All` is relative to where the cursor currently is, which is what makes the partial forms useful
  * to a viewport that owns only some of the screen: it positions the cursor at the start of its own region and erases
  * from there, leaving the user's scrollback above it intact.
  */
enum ClearType:

  /** The whole display (`CSI 2J`). */
  case All

  /** From the cursor to the end of the display (`CSI 0J`). */
  case AfterCursor

  /** From the start of the display up to and including the cursor (`CSI 1J`). */
  case BeforeCursor

  /** The whole line the cursor is on (`CSI 2K`). */
  case CurrentLine

  /** From the cursor to the end of its line (`CSI 0K`). */
  case UntilNewLine

/** Why a [[Backend]] operation could not be carried out. */
enum BackendError:

  /** The terminal's underlying I/O failed — a closed stream, a disconnected TTY, a write that could not complete. */
  case Io(cause: Throwable)

  /** The terminal lacks a capability the operation needs (no alternate screen, no TTY at all). */
  case UnsupportedTerminal(reason: String)

  /** Raw mode was asked to be left when it had never been entered. */
  case NotInRawMode

  /** One human-readable line describing the failure, for an app that reports it to the user.
    *
    * The generated `toString` of an enum case is a constructor call — `Io(java.io.IOException: Stream closed)` — which
    * is a fine debugging string and a poor thing to print at someone. This is the sentence to print instead.
    */
  def message: String =
    this match
      case Io(cause)                   =>
        val detail = Option(cause.getMessage).getOrElse(cause.getClass.getName)
        s"terminal I/O failed: $detail"
      case UnsupportedTerminal(reason) => s"terminal not supported: $reason"
      case NotInRawMode                => "the terminal is not in raw mode"

package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Event, Position, Size, Widget}

import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp

import java.io.{FileDescriptor, FileOutputStream, IOException, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/** [[Backend]] implementation over JLine 3's system terminal.
  *
  * Owns the JLine `Terminal` for its whole lifetime: construct via [[JLine3Backend.create]], release with `close()`
  * (which restores cooked mode, the main screen, and cursor visibility if still active). `draw` keeps a copy of the
  * last flushed frame and writes only the diff.
  *
  * Signals are owned here rather than left to JLine's defaults. `INT`/`QUIT` become [[Event.Interrupt]] so the runner
  * unwinds through its normal teardown; `TSTP`/`CONT` hand the terminal back to the shell and take it again on resume;
  * `WINCH` posts a coalesced resize. See [[JLine3Backend.create]] for why the defaults are unusable.
  *
  * The mechanics live in package-private collaborators — [[FrameEncoder]] and [[FrameBaseline]] for the frame diff,
  * [[EventPump]] for the wake/poll machinery, [[ReplyQueries]] for the query/reply round trips, [[TerminalDressing]]
  * for the dress/undress choreography, [[ScrollRegions]] for hardware scroll regions and [[TitleStack]] for the window
  * title. This class wires them to the JLine terminal and carries the public contract.
  */
final class JLine3Backend private (
    private[terminal] val terminal: Terminal,
    colorDepth: ColorDepth,
    env: Map[String, String],
    knownCapabilities: Option[TerminalCapabilities],
) extends Backend:

  // written on the render thread, read by JLine's signal-dispatch thread and by the shutdown hook
  // holds the *cooked*-mode attributes captured when raw mode was entered, so it doubles as "are we in raw mode?"
  @volatile private var cookedAttributes: Option[Attributes]                   = None
  @volatile private[terminal] var alternateScreenActive                        = false
  // which capture mode is in force, or `None` for "capture is off" — the mode has to be remembered, not just the fact
  // of capture, so that taking the terminal back after Ctrl+Z re-requests all-motion tracking rather than silently
  // downgrading a hover-driven app to buttons-only
  @volatile private[terminal] var mouseCaptureActive: Option[MouseCaptureMode] = None
  @volatile private[terminal] var cursorHidden                                 = false

  /** Successfully requested enhanced keyboard mode, included in terminal handover snapshots. */
  @volatile private[terminal] var keyEventTypesActive   = false
  @volatile private var keyboardOwned                   = false
  // whether *this* backend turned the caret's blink off. Only what an app suppressed is restored on the way out: a
  // user whose emulator is configured for a steady caret would otherwise have that preference overwritten by every
  // glyphora app that exits, including the ones that never touched blink at all.
  @volatile private[terminal] var cursorBlinkSuppressed = false

  /** Last successfully requested shape. Non-default shapes owe a reset and are restored exactly after a handover. */
  @volatile private[terminal] var cursorShape = CursorShape.Default

  /** Last successfully requested mouse pointer shape, on the same terms as [[cursorShape]]: a non-default one owes a
    * reset on the way out and is restored after a handover.
    */
  @volatile private[terminal] var pointerShape = PointerShape.Default

  /** The taskbar progress last shown, so a handover can clear it and the reacquire put it back; `None` once cleared. */
  @volatile private[terminal] var taskbarState: TaskbarState = TaskbarState.Cleared
  // how many rows an inline run reserved on the primary screen, so the dressing choreography can park the cursor below
  // the frame such a run leaves behind
  @volatile private[terminal] var inlineRows                 = 0
  // how many of those rows the run asked a close to keep (`keepInlineRows`); `None` keeps them all
  @volatile private[terminal] var inlineKept: Option[Int]    = None

  /** The terminal modes [[TerminalDressing]] found active when SIGTSTP handed the terminal back, so SIGCONT can put
    * them back. Written and read on JLine's signal-dispatch thread alone.
    */
  @volatile private var suspendedState: TerminalDressing.TerminalState = TerminalDressing.TerminalState.Undressed

  /** What the terminal said about itself when raw mode was entered. Written once per raw-mode session, on the render
    * thread, and read from `draw` and from the probe-free re-dress after a handover — hence `@volatile`.
    */
  @volatile private var probed: TerminalCapabilities = TerminalCapabilities.unknown

  /** Which notification dialect this terminal is believed to speak, decided from the environment once. `None` means a
    * notification would reach nobody — see [[DesktopNotificationProtocol.detect]].
    */
  private val notificationProtocol: Option[DesktopNotificationProtocol] = DesktopNotificationProtocol.detect(env)

  /** The terminal's last known text-area size in pixels, stamped with the [[pixelsGeneration]] it was measured at;
    * `None` until it has been asked.
    *
    * Cached because the answer only changes when the window does, and because the query costs a round trip on the
    * stream the event loop reads from. It has one writer, the render thread: `windowSize` fills it from the `CSI 14 t`
    * query, and `readEvent` fills it from the pixel fields of a mode 2048 in-band resize report, which carries the same
    * fact for free. A resize does not touch it — `onResize` only bumps the generation, which makes an entry stamped
    * with an older one stale, so the next `windowSize` asks again.
    */
  @volatile private var pixelCache: Option[JLine3Backend.PixelEntry] = None

  /** Versions the pixel cache above. Bumped by `onResize` on the signal-dispatch thread (its only writer), captured by
    * `windowSize` before it queries and compared after the query returns: a reply that measured the pre-resize window
    * is discarded, not published as geometry of the window that exists now.
    */
  @volatile private var pixelsGeneration: Long = 0L

  // Owned by the render thread alone — no other thread may read or write it. A thread that takes the screen away (the
  // SIGCONT handler re-entering the alternate screen) raises `fullRedrawRequested` instead: a reset written here from
  // the signal-dispatch thread would be overwritten by an in-flight `draw` and the repaint lost.
  private val baseline = FrameBaseline()

  /** The pictures on screen, kept in step with each frame's placements. Render-thread-confined, like `baseline`. */
  private val graphics = GraphicsLayer()

  /** Serialises the three things that decide which screen the terminal is showing: writing a composed frame, handing
    * the terminal back to the shell, and taking it again. See [[TerminalDressing.releaseTerminal]] for what goes wrong
    * without it. Shared with the collaborators that write to the terminal ([[ReplyQueries]], [[ScrollRegions]],
    * [[TerminalDressing]]), so every write is mutually exclusive with every other.
    */
  private[terminal] val screenOwnership = Object()

  // raised by any thread that disturbed the screen (alternate-screen entry, SIGCONT's reacquire), consumed by `draw`
  private val fullRedrawRequested = RedrawRequest()

  private val inputReader                     = TerminalInput.readerFor(terminal)
  @volatile private[terminal] var inputActive = true
  private var terminalPausedByUs              = false

  private val frameEncoder  = FrameEncoder(colorDepth)
  private val decoder       = InputDecoder(
    timeoutMillis => inputReader.read(timeoutMillis),
    escapeTimeoutMillis = EnvironmentOverrides.escapeDelayMillis(env),
  )
  private val pump          = EventPump(decoder)
  // `write` is the backend's monitored write, passed the way TitleStack receives it: the collaborators emit through
  // it and so own no writer, no flush and no monitor of their own.
  private val queries       = ReplyQueries(screenOwnership, sequence => write(sequence), decoder)
  private val scrollRegions = ScrollRegions(sequence => write(sequence), terminal, baseline)
  private val titleStack    = TitleStack(sequence => Backend.attempt(write(sequence)))
  private val dressing      = TerminalDressing(this)

  private val supportsAlternateScreen =
    terminal.getStringCapability(
      InfoCmp.Capability.enter_ca_mode
    ) != null // scalafix:ok DisableSyntax; getStringCapability returns null when the capability is absent

  terminal.handle(Terminal.Signal.WINCH, _ => onResize())
  // INT/QUIT must not kill the JVM: the process would die before any teardown and hand back a raw, alt-screen terminal
  terminal.handle(Terminal.Signal.INT, _ => onInterrupt())
  terminal.handle(Terminal.Signal.QUIT, _ => onInterrupt())
  terminal.handle(Terminal.Signal.TSTP, _ => onStop())
  terminal.handle(Terminal.Signal.CONT, _ => onContinue())

  override def capabilities: TerminalCapabilities = probed

  /** Replaces what the probe established, for a test driving a terminal that cannot answer. */
  private[terminal] def capabilitiesOverride(capabilities: TerminalCapabilities): Unit = probed = capabilities

  def size: Either[BackendError, Size] = Backend.attempt(currentSize)

  /** The window in cells, plus its pixel size when this terminal will report one.
    *
    * The pixel half costs a round trip on the input stream — `ESC[14t` out, `CSI 4 ; height ; width t` back — so it is
    * asked at most once and then cached, and re-asked only after a resize has invalidated the answer. Everything
    * [[queryCursorPosition]] documents applies: it must run on the render thread, a key typed while the reply is in
    * flight is queued rather than dropped, and a terminal that does not implement the query simply never answers.
    *
    * A terminal that never answers is not a failure. The query is attempted once, the wait is short, and the cells are
    * returned with no pixels — which is what most terminals, including most of the Windows ones, will produce. Asked
    * outside raw mode there is no reader to receive a reply at all, so the query is skipped entirely rather than
    * spending the timeout.
    *
    * The cache is filled only where the query actually runs, and only after it returns: asked outside raw mode, or on a
    * transient I/O failure, it stays empty so the next call — raw mode entered, the error cleared — still asks. A
    * resize landing mid-query discards the reply the same way: the generation `onResize` bumps is compared against the
    * one captured before the round trip, a mismatch leaves the cache unset, and the next call asks at the new size. An
    * in-band resize report that carried pixel fields has already filled it, and then nothing is asked at all.
    */
  override def windowSize: Either[BackendError, WindowSize] =
    Backend.attempt {
      pixelCache match
        case Some(JLine3Backend.PixelEntry(generation, pixels)) if generation == pixelsGeneration =>
          WindowSize(currentSize, pixels)
        case _                                                                                    =>
          // Capture the generation before the query and compare after it. Without the comparison a WINCH during the
          // round trip lets the render thread publish pixels that measured the pre-resize window and mark them
          // current — mixed with the new cell count that is a stale cell geometry surviving until the next resize.
          val generation = pixelsGeneration
          val measured   =
            if cookedAttributes.isEmpty then None
            else
              val pixels = queries.textAreaSize(JLine3Backend.PixelQueryTimeout)
              if generation == pixelsGeneration then
                pixelCache = Some(JLine3Backend.PixelEntry(generation, pixels))
                pixels
              else None
          WindowSize(currentSize, measured)
    }

  def draw(buffer: Buffer): Either[BackendError, Unit] =
    // claimed before the frame is composed, so a request raised while this frame is in flight survives for the next one
    val forced  = fullRedrawRequested.claim()
    // a terminal that narrowed has already reflowed what was on screen, and the wrapped remnants sit outside the new,
    // smaller area where no amount of repainting reaches them — erase only the screen/strip this backend owns
    val erasing = ScreenReset.clearsOnShrink(baseline.area, buffer.area)
    val result  = Backend.attempt {
      screenOwnership.synchronized {
        val frame = composeFrame(buffer, blank = forced || erasing, erasing)
        // an unchanged frame writes nothing at all, so a redraw-on-tick app with a static screen stays silent — unless
        // the erase itself has to go out, which is the one case where "nothing changed" still needs a write
        if frame.nonEmpty then writeFrameAtomically(frame)
        baseline.commit(buffer)
      }
    }
    // the forced frame never reached the terminal and the baseline was not updated: the request has not been served
    if forced && result.isLeft then requestFullRedraw()
    result

  /** Composes the frame to write for `buffer`, diffed against the retained baseline — or "" when the frame is unchanged
    * and no erase is owed, which is the one case [[draw]] writes nothing at all.
    *
    * After an erase, when a full repaint was asked for, and after any resize, the baseline no longer describes what is
    * on screen — so it answers `RepaintAll` and every cell of the frame is written, blanks included. Diffing against a
    * blanked grid instead would emit only the frame's non-blank cells, leaving whatever the previous frame had drawn in
    * every column this one leaves empty.
    */
  private def composeFrame(buffer: Buffer, blank: Boolean, erasing: Boolean): String =
    // A primary-screen run owns at most its reserved bottom strip, never the shell output above it. Use the current
    // height rather than the previous frame's origin, which can be outside the strip after a simultaneous resize.
    val erase    =
      if !erasing then ""
      else if alternateScreenActive then AnsiSequences.ClearScreen
      else if inlineRows > 0 then
        AnsiSequences.moveTo(0, math.max(0, currentSize.height - inlineRows)) +
          AnsiSequences.clear(ClearType.AfterCursor)
      else ""
    val source   = baseline.prepareFor(buffer.area, blank)
    // before the diff: dropping a placement the terminal cannot draw changes which cells the diff skips
    val pictures =
      graphics.compose(buffer, ImageProtocol.select(probed), repaintAll = source == FrameSource.RepaintAll)
    val body     = source match
      case FrameSource.DiffAgainst(previous) => frameEncoder.encode(previous, buffer)
      case FrameSource.RepaintAll            => frameEncoder.encodeAll(buffer)
    if body.nonEmpty || erasing || !pictures.isEmpty then
      AnsiSequences.frame(
        erase + pictures.before + body + pictures.after,
        probed.synchronizedOutput.usable,
      )
    else ""

  /** Writes one composed frame as a single atomic update: the terminal shows the previous frame until the whole batch
    * has arrived.
    *
    * Under the monitor, so a Ctrl+Z landing mid-frame cannot leave the alternate screen between the two writes and
    * spill this frame's cursor moves and box-drawing over the user's shell.
    */
  private def writeFrameAtomically(frame: String): Unit = write(frame)

  /** Asks the next [[draw]] to repaint every cell. Safe to call from any thread.
    *
    * Public through [[Backend.requestFullRedraw]] so an app whose screen was disturbed by something this backend did
    * not do — a subprocess it started itself rather than through [[suspend]] — has a supported way to recover.
    *
    * Raised whenever the screen stops showing what `baseline` describes: the alternate screen was just cleared, or
    * something else owned the terminal in between (the shell, between SIGTSTP and SIGCONT). A flag rather than a reset
    * of the baseline keeps that buffer render-thread-private, so a request raised while a `draw` is in flight is
    * consumed by the *following* frame instead of being overwritten by that frame's own copy.
    */
  override def requestFullRedraw(): Unit = fullRedrawRequested.raise()

  override def redrawOwed: Boolean = fullRedrawRequested.isPending

  def enableRawMode(): Either[BackendError, Unit] = dressRawMode(probe = true)

  /** Enters raw mode and applies the input-mode dressing, probing capabilities first when `probe`.
    *
    * The probe is the expensive half — a DA1 round trip of up to [[JLine3Backend.CapabilityProbeTimeout]] — and it is
    * also a *read*, so it happens only here, on the render thread's first entry into raw mode. Taking the terminal back
    * after a handover goes through `dressRawMode(probe = false)` instead: the answer is retained in `probed` for the
    * whole raw-mode session, and reading from JLine's signal-dispatch thread (SIGCONT) or mid-`suspend` would race the
    * render thread's in-flight read on the decoder.
    */
  private[terminal] def dressRawMode(probe: Boolean): Either[BackendError, Unit] =
    Backend.attempt {
      // first, before anything this backend does can move the cursor: this is where the shell's prompt was, and it is
      // where `disableRawMode` has to put the cursor back. It matters most on a terminal with no alternate screen —
      // the case `enterAlternateScreen` refuses outright — where the app draws over the shell's own scrollback and
      // there is no screen switch to restore the prompt's position for it.
      write(AnsiSequences.SaveCursor)
      cookedAttributes = Some(terminal.enterRawMode())
      // Ask before telling. The probe has to come after raw mode — there is no reader for a reply before it — and
      // before the modes below, so a terminal that denies one is never sent it at all.
      // capabilities handed in at creation were probed by an earlier backend on this same terminal (see `create`): the
      // round trip is skipped, and they were gated by the environment when they were probed
      if probe then
        probed = knownCapabilities.getOrElse(
          JLine3Backend.gatedByEnvironment(queries.probeCapabilities(JLine3Backend.CapabilityProbeTimeout), env)
        )
      // modern input modes; a terminal that answered nothing still gets them, because an unsupported private mode is
      // ignored by an overwhelming majority of terminals and switching the feature off on silence would disable it
      // almost everywhere. Only an explicit denial skips one.
      if probed.bracketedPaste.usable then write(AnsiSequences.EnableBracketedPaste)
      if probed.focusReporting.usable then write(AnsiSequences.EnableFocusReporting)
      acquireKeyboard()
      if probed.colorSchemeUpdates.usable then write(AnsiSequences.EnableColorSchemeUpdates)
      if probed.unicodeWidth.usable then write(AnsiSequences.EnableUnicodeWidth)
      // the one mode gated on an explicit yes: the signal-driven resize it supplements already works everywhere, so
      // there is nothing to gain from asking a terminal that never said it understands the report
      if probed.inBandResize == Support.Yes then write(AnsiSequences.EnableInBandResize)
    }

  /** Re-pushes the kitty keyboard flags with "report event types" added.
    *
    * Pop-then-push rather than a second push: the flags live on a stack inside the terminal, and pushing twice would
    * leave a second entry that `disableRawMode`'s single pop does not remove — the user's shell would keep receiving
    * key releases after the app exited.
    *
    * A terminal that does not implement the protocol ignores both sequences, which is why this reports success either
    * way: there is nothing to fail, and nothing to promise.
    */
  override def enableKeyEventTypes(): Either[BackendError, Unit] =
    Backend.attempt {
      if probed.kittyKeyboard.usable then
        releaseKeyboard()
        write(AnsiSequences.PushKittyKeyboardEvents)
        keyboardOwned = true
        keyEventTypesActive = true
    }

  /** Keyboard flags belong to the currently active screen, never to a screen we just left. */
  private def acquireKeyboard(): Unit =
    if !keyboardOwned && probed.kittyKeyboard.usable then
      write(if keyEventTypesActive then AnsiSequences.PushKittyKeyboardEvents else AnsiSequences.PushKittyKeyboard)
      keyboardOwned = true

  private def releaseKeyboard(): Unit =
    if keyboardOwned then
      write(AnsiSequences.PopKittyKeyboard)
      keyboardOwned = false

  def disableRawMode(): Either[BackendError, Unit] =
    cookedAttributes match
      case None             => Left(BackendError.NotInRawMode)
      case Some(attributes) =>
        // Output failure must not prevent restoring the shell's cooked attributes. Attempt every release and retain
        // the first error; ownership is only cleared when the whole sequence has succeeded.
        var failure                      = Option.empty[BackendError]
        def release(step: => Unit): Unit =
          Backend.attempt(step).left.foreach(error => if failure.isEmpty then failure = Some(error))
        release(releaseKeyboard())
        release(write(AnsiSequences.DisableFocusReporting))
        release(write(AnsiSequences.DisableBracketedPaste))
        release(write(AnsiSequences.DisableColorSchemeUpdates))
        release(write(AnsiSequences.DisableUnicodeWidth))
        release(write(AnsiSequences.DisableInBandResize))
        release(terminal.setAttributes(attributes))
        // Last, paired with the save in enableRawMode, so the shell resumes where its prompt started.
        release(write(AnsiSequences.RestoreCursor))
        if failure.isEmpty then
          cookedAttributes = None
          keyEventTypesActive = false
        failure.fold[Either[BackendError, Unit]](Right(()))(Left(_))

  /** Enters the alternate screen, or reports that the terminal has none.
    *
    * Gated on terminfo's `smcup`: the Linux console (`TERM=linux`) has no alternate screen, so emitting `CSI ?1049h`
    * there paints the app over the user's scrollback and never gives it back. Failing loudly beats destroying history.
    */
  def enterAlternateScreen(): Either[BackendError, Unit] =
    if !supportsAlternateScreen then
      Left(BackendError.UnsupportedTerminal(s"${terminal.getType} has no alternate screen (no smcup capability)"))
    else
      Backend.attempt {
        releaseKeyboard()
        write(AnsiSequences.EnterAlternateScreen)
        alternateScreenActive = true
        if isRawMode then acquireKeyboard()
        write(AnsiSequences.clear(ClearType.All))
        requestFullRedraw() // the alternate screen starts blank; the next draw must repaint everything
      }

  /** Scrolls the primary screen up by `rows` lines so an inline app has room at the bottom.
    *
    * A newline written on the last row is what makes a terminal scroll — that is all this does, `rows` times, which is
    * the same trick ratatui's inline viewport uses. Nothing is cleared: the shell's earlier output moves up and stays
    * readable, and the freed rows are blank because they have never been written to.
    *
    * The frame diff is invalidated afterwards, because the rows the backend believed it had already painted have just
    * moved somewhere else on screen.
    */
  override def reserveInlineRows(rows: Int): Either[BackendError, Unit] =
    if rows <= 0 then Right(())
    else
      Backend.attempt {
        write("\n".repeat(rows))
        inlineRows = rows
        requestFullRedraw()
      }

  override def keepInlineRows(rows: Int): Either[BackendError, Unit] =
    inlineKept = Some(math.max(0, rows))
    Right(())

  def leaveAlternateScreen(): Either[BackendError, Unit] =
    Backend.attempt {
      releaseKeyboard()
      write(AnsiSequences.LeaveAlternateScreen)
      alternateScreenActive = false
      if isRawMode then acquireKeyboard()
    }

  def enableMouseCapture(): Either[BackendError, Unit] = enableMouseCapture(MouseCaptureMode.Buttons)

  override def enableMouseCapture(mode: MouseCaptureMode): Either[BackendError, Unit] =
    // `GLYPHORA_MOUSE` may hold the capture to buttons or refuse it; refused is still a success, as for an OSC the
    // terminal cannot show
    EnvironmentOverrides.mouseMode(mode, env) match
      case MouseCaptureMode.Off if mode == MouseCaptureMode.Off => disableMouseCapture()
      case MouseCaptureMode.Off                                 => Right(())
      case allowed                                              =>
        Backend.attempt {
          write(AnsiSequences.enableMouseCapture(allowed))
          mouseCaptureActive = Some(allowed)
        }

  def disableMouseCapture(): Either[BackendError, Unit] =
    Backend.attempt {
      write(AnsiSequences.DisableMouseCapture)
      mouseCaptureActive = None
    }

  def hideCursor(): Either[BackendError, Unit] =
    Backend.attempt {
      write(AnsiSequences.HideCursor)
      cursorHidden = true
    }

  def showCursor(): Either[BackendError, Unit] =
    Backend.attempt {
      write(AnsiSequences.ShowCursor)
      cursorHidden = false
    }

  /** Writes DECSCUSR (`CSI n SP q`) to pick the hardware cursor's shape.
    *
    * The last successful request is remembered: handing the terminal back resets it to [[CursorShape.Default]], and
    * taking it back restores the exact shape from the handover snapshot rather than guessing a block.
    */
  override def setCursorShape(shape: CursorShape): Either[BackendError, Unit] =
    Backend.attempt {
      write(AnsiSequences.cursorShape(shape))
      cursorShape = shape
    }

  /** Writes OSC 22 to pick the mouse pointer's shape, unless the environment ruled pointer shapes out.
    *
    * Remembered like the caret's shape, so a handover resets it and the reacquire restores it. The write is skipped —
    * not failed — when `capabilities.pointerShapes` is `No`: a multiplexer swallows the string, and an app has nothing
    * to do about that.
    */
  override def setPointerShape(shape: PointerShape): Either[BackendError, Unit] =
    Backend.attempt {
      if probed.pointerShapes.usable then write(AnsiSequences.pointerShape(shape))
      pointerShape = shape
    }

  /** Writes the notification in the dialect [[DesktopNotificationProtocol.detect]] chose, or nothing when the
    * environment says nobody would see it.
    */
  override def notifyDesktop(title: String, body: String): Either[BackendError, Unit] =
    Backend.attempt {
      if probed.desktopNotifications.usable then
        notificationProtocol.foreach(protocol => write(AnsiSequences.desktopNotification(protocol, title, body)))
    }

  /** Writes OSC 9;4, unless the environment ruled the indicator out. Remembered so a handover can clear it and the
    * reacquire put it back, and so `close()` knows whether there is anything to clear.
    */
  override def setTaskbarProgress(progress: Option[Double]): Either[BackendError, Unit] =
    setTaskbarState(TaskbarState.fromProgress(progress))

  /** Writes OSC 9;4 in any of its five states, under the same gate and memory as [[setTaskbarProgress]]. */
  override def setTaskbarState(state: TaskbarState): Either[BackendError, Unit] =
    Backend.attempt {
      if probed.taskbarProgress.usable then write(AnsiSequences.taskbarState(state))
      taskbarState = state
    }

  /** Writes CUP (`CSI row ; column H`) to park the terminal's own caret on `position`.
    *
    * Nothing has to be done to the frame diff afterwards. [[FrameEncoder.encode]] starts every frame with
    * `expectedX = -1`, so it emits an absolute move before its first cell and cannot be misled about where the caret
    * was left by the previous frame.
    */
  override def setCursorPosition(position: Position): Either[BackendError, Unit] =
    Backend.attempt(write(AnsiSequences.moveTo(position.x, position.y)))

  /** Writes DECSET/DECRST 12 to switch the caret's blink on or off.
    *
    * The suppression is remembered so that [[TerminalDressing.releaseTerminal]] can undo it — see
    * `cursorBlinkSuppressed`.
    */
  override def setCursorBlink(blinking: Boolean): Either[BackendError, Unit] =
    Backend.attempt {
      write(if blinking then AnsiSequences.EnableCursorBlink else AnsiSequences.DisableCursorBlink)
      cursorBlinkSuppressed = !blinking
    }

  def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
    val event = pump.poll(timeout)
    // a mode 2048 report measured the window it announced, so its pixel fields are the answer the next `windowSize`
    // would otherwise spend a round trip on — stamped with the generation current now, which a later SIGWINCH outdates
    decoder
      .takeInBandPixels()
      .foreach(pixels => pixelCache = Some(JLine3Backend.PixelEntry(pixelsGeneration, Some(pixels))))
    event

  /** Cuts short an in-flight [[readEvent]]. Safe from any thread. */
  override def wake(): Unit = pump.wake()

  override def clearRegion(kind: ClearType): Either[BackendError, Unit] =
    Backend.attempt {
      write(AnsiSequences.clear(kind))
      // the screen no longer shows what `baseline` describes, so a diff against it would leave the erased cells
      // blank for as long as the app kept drawing them to the same values
      requestFullRedraw()
    }

  /** Writes the XTerm "resize the text area" sequence.
    *
    * Nothing is recorded and nothing is restored on the way out: unlike raw mode or the alternate screen, a window size
    * is not a mode this backend switched on and owes the shell back. If the emulator honoured the request, the new size
    * is the user's terminal now, and shrinking it back on exit would be this library second-guessing a change the user
    * can see and undo.
    */
  override def requestSize(size: Size): Either[BackendError, Unit] =
    Backend.requirePositiveSize(size)
    Backend.attempt(write(AnsiSequences.resizeWindow(size)))

  /** Asks the terminal where its cursor currently is, waiting up to `timeout` for the answer.
    *
    * A terminal that does not implement the report never answers, so the timeout expiring is reported as an unsupported
    * terminal rather than as an I/O failure: nothing broke, the terminal simply cannot say. The round trip itself —
    * request under the monitor, reply read outside it — is [[ReplyQueries.cursorPosition]].
    */
  override def queryCursorPosition(timeout: Duration): Either[BackendError, Position] =
    Backend.requirePositiveTimeout(timeout)
    Backend.attempt(queries.cursorPosition(timeout)).flatMap {
      case Some(position) => Right(position)
      case None           => Left(BackendError.UnsupportedTerminal("the terminal did not report its cursor position"))
    }

  override def scrollRegionUp(region: RowRange, lines: Int): Either[BackendError, Unit] =
    takeDownPictures().flatMap(_ => scrollRegions.scroll(region, lines, ScrollDirection.Up))

  override def scrollRegionDown(region: RowRange, lines: Int): Either[BackendError, Unit] =
    takeDownPictures().flatMap(_ => scrollRegions.scroll(region, lines, ScrollDirection.Down))

  /** Deletes every picture on screen before the terminal moves rows itself: a kitty picture scrolls with the text, so
    * after the move it is no longer where the graphics layer believes it is. The next frame places it again wherever
    * the app now draws it.
    */
  private def takeDownPictures(): Either[BackendError, Unit] =
    val deletes = graphics.forgetAll(ImageProtocol.select(probed))
    if deletes.isEmpty then Right(()) else Backend.attempt(write(deletes))

  override def copyToClipboard(text: String): Either[BackendError, Unit] =
    Backend.attempt(write(AnsiSequences.clipboardCopy(text)))

  override def setTitle(title: String): Either[BackendError, Unit] = titleStack.set(title)

  private[terminal] def pauseInput(): Either[BackendError, Unit] = Backend.attempt {
    pump.pause()
    if terminal.canPauseResume() && !terminal.paused() then
      terminal.pause(true)
      terminalPausedByUs = true
    inputActive = false
  }

  private[terminal] def resumeInput(): Either[BackendError, Unit] = Backend.attempt {
    if terminalPausedByUs then
      terminal.resume()
      terminalPausedByUs = false
    inputActive = true
    pump.resume()
  }

  override def suspend[A](body: => A): Either[BackendError, A] =
    val released = dressing.releaseTerminal()
    try
      released.failure match
        case Some(error) => Left(error) // never launch a competing reader after a failed handover
        case None        => Backend.attempt(body)
    finally dressing.reacquireTerminal(released.state)

  /** Writes the sequence through the same writer every frame goes out on, so it lands in order with them.
    *
    * No control stripping: see [[Backend.writeRaw]] for why the payload is passed through untouched, and for what the
    * caller owes the terminal in return.
    */
  override def writeRaw(sequence: String): Either[BackendError, Unit] =
    Backend.attempt(write(sequence))

  override def printAbove(lines: Seq[String]): Either[BackendError, Unit] =
    // step out to the primary screen so the lines land in real scrollback, print them, then step back in and repaint
    // these strings reach the terminal uninterpreted, so they get the same control-stripping as link targets
    suspend(writeScrollbackRows(lines.map(AnsiSequences.stripControls)))

  /** Renders `widget` into a block `height` rows tall and prints it into the terminal's real scrollback, styling and
    * all.
    *
    * Same trip out to the primary screen as [[printAbove]] — that is what makes the lines durable, since the alternate
    * screen has no scrollback — but the rows are encoded by [[FrameEncoder.encodeRow]] rather than stripped down to
    * plain text, so colours, bold and hyperlinks survive. Each row ends with a style reset and a `\r\n`, so the block
    * behaves like any other command output the shell scrolled past.
    *
    * `encodeRow` rather than [[FrameEncoder.encode]]: a frame diff is a stream of absolute cursor moves, and absolute
    * positions mean nothing for text the terminal is placing on a line of its own choosing. The block is measured
    * against the width the terminal has *now*, so a resize between two calls simply produces a differently sized block.
    */
  override def insertBefore(height: Int, widget: Widget): Either[BackendError, Unit] =
    if height <= 0 then Right(())
    else
      Backend.attempt(Backend.renderBlock(currentSize.width, height, widget)).flatMap { buffer =>
        suspend(writeScrollbackRows((0 until height).map(frameEncoder.encodeRow(buffer, _))))
      }

  /** Writes each row plus its line ending to the terminal, then flushes — the shared skeleton of [[printAbove]] and
    * [[insertBefore]], run inside their `suspend` so the rows land in real scrollback.
    */
  private def writeScrollbackRows(rows: Seq[String]): Unit =
    screenOwnership.synchronized {
      val writer = terminal.writer()
      rows.foreach { row =>
        writer.write(row)
        writer.write("\r\n")
      }
      flushOutput(writer)
    }

  /** Scrolls the screen up by `n` rows with SU (`CSI n S`).
    *
    * SU rather than "move to the last row and write `n` newlines": it does not depend on where the cursor is, does not
    * move it, and needs no knowledge of the terminal's height. What it *does* do is move every row that stays on
    * screen, so the diff baseline no longer describes what is displayed — hence the forced repaint, raised through the
    * same [[requestFullRedraw]] the alternate screen and SIGCONT use rather than a second mechanism.
    */
  override def appendLines(n: Int): Either[BackendError, Unit] =
    if n <= 0 then Right(())
    else
      Backend.attempt {
        write(AnsiSequences.scrollUp(n))
        requestFullRedraw()
      }

  /** Restores the terminal and releases the JLine handle, reporting the first step that failed.
    *
    * Every step is attempted whatever the earlier ones did — stopping at the first failure would leave the terminal
    * half-dressed, which is worse than the failure itself — so the JLine handle is closed even when undressing failed,
    * and the undressing failure is what gets reported because it is the one the user can see.
    */
  def close(): Either[BackendError, Unit] =
    val released     = dressing.releaseTerminal()
    // Unlike a temporary handover, close owes no future inline parking after a successful release.
    if released.failure.isEmpty then
      inlineRows = 0
      inlineKept = None
    val titleFailure = titleStack.release()
    val closed       = Backend.attempt(terminal.close())
    // first failure wins, but by this line everything has been attempted either way — stopping early would leave the
    // terminal half-dressed, which is worse than the failure itself
    val firstFailure = released.failure.orElse(titleFailure)
    firstFailure.fold(closed)(Left(_))

  /** Last-resort restore, for a shutdown hook that may be racing JLine's own terminal closer.
    *
    * Writes straight to the process's stdout descriptor rather than through the JLine writer: once JLine's
    * `ShutdownHooks` closer has run, `terminal.writer()` throws `IllegalStateException: Terminal has been closed` and
    * every teardown write is silently discarded. Blind mode resets remain safe after normal close; the non-idempotent
    * kitty pop is included only if this backend still owns its current screen's keyboard push, before screen exit. A
    * successful fallback retires that ownership too, so a later fallback cannot pop an enclosing shell's mode.
    *
    * Deliberately takes no monitor either — unlike [[TerminalDressing.releaseTerminal]], this is the path that must
    * still work when the render thread is wedged mid-frame, and a last-resort restore that can block is not one.
    */
  override def emergencyRestore(): Unit =
    try
      // deliberately never closed: this wraps the process's own stdout descriptor, and closing the wrapper would close
      // stdout for everything that runs after this hook. The wrapper itself holds no resource beyond that descriptor.
      val out      = FileOutputStream(FileDescriptor.out)
      // A pop must precede RestoreAll's screen exit, and must never be sent after normal close released it already.
      // End a possibly interrupted synchronized frame before touching either screen's keyboard stack.
      val keyboard =
        if keyboardOwned then AnsiSequences.EndSynchronized + AnsiSequences.PopKittyKeyboard else ""
      // Blink enable is conditional too: do not overwrite a user's configured steady-caret preference.
      val restore  = keyboard + AnsiSequences.RestoreAll +
        (if cursorBlinkSuppressed then AnsiSequences.EnableCursorBlink else "")
      out.write(restore.getBytes(UTF_8))
      out.flush()
      keyboardOwned = false
      keyEventTypesActive = false
      alternateScreenActive = false
      cursorBlinkSuppressed = false
    catch case NonFatal(_) => ()

  /** Whether raw mode is currently on, which is exactly "we are holding someone's cooked attributes to put back". */
  private[terminal] def isRawMode: Boolean = cookedAttributes.nonEmpty

  /** SIGWINCH: outdate the cached pixel geometry and queue a coalesced resize for the next poll. `forced` (SIGCONT)
    * delivers the resize even when the size is unchanged, so the repaint after a suspend still happens.
    *
    * Runs on JLine's signal-dispatch thread, and so does the `Backend.sizeOf` inside `currentSize`: an uncaught throw
    * here — `terminal.getSize` on a terminal `close()` is racing — would kill the JDK's one signal-dispatch thread,
    * after which every Java signal handler in the process is silently dead. Total like `onStop`, at the price of a
    * missed resize notification on a terminal that is already gone.
    */
  private def onResize(forced: Boolean = false): Unit =
    try
      // the window moved, so a cached pixel size describes a window that no longer exists; the generation bump is what
      // outdates the cache and an in-flight `windowSize` query, whose reply would otherwise be published as fresh
      // geometry. The cache itself is left to its one writer, the render thread.
      pixelsGeneration += 1
      if forced then pump.postForcedResize(currentSize) else pump.postResize(currentSize)
    catch case NonFatal(_) => ()

  private def onInterrupt(): Unit = pump.postInterrupt()

  /** SIGTSTP: undress the terminal, then stop for real by re-raising with the default disposition. */
  private def onStop(): Unit =
    suspendedState = dressing.releaseTerminal().state
    JLine3Backend.stopSelf()

  /** SIGCONT: take the terminal back and force a full repaint at whatever size it is now. */
  private def onContinue(): Unit =
    dressing.reacquireTerminal(suspendedState)
    suspendedState = TerminalDressing.TerminalState.Undressed
    onResize(forced = true)

  private def currentSize: Size = Backend.sizeOf(terminal)

  /** Writes one sequence to the terminal and flushes it, under `screenOwnership`.
    *
    * Every sequence goes out under the monitor, so a Ctrl+Z cannot land between leaving the alternate screen and the
    * write and aim it at the user's shell. The monitor is reentrant, so a caller that needs a wider critical section —
    * `draw`'s single batched frame, [[ReplyQueries.probeCapabilities]]' five queries, the two teardown paths — simply
    * takes it and calls this.
    */
  private def write(sequence: String): Unit =
    screenOwnership.synchronized {
      val writer = terminal.writer()
      writer.write(sequence)
      flushOutput(writer)
    }

  /** PrintWriter swallows device IOExceptions. Check its error latch before committing any mode or frame accounting;
    * the original exception is not exposed, so report a fresh IOException through Backend.attempt.
    */
  private def flushOutput(writer: PrintWriter): Unit =
    writer.flush()
    if writer.checkError() then throw new IOException("terminal output writer reported an I/O failure")

object JLine3Backend:

  /** How long [[JLine3Backend.windowSize]] waits for a `CSI 14 t` reply before concluding the terminal has none.
    *
    * Short on purpose. A terminal that implements the report answers within one round trip of the pty, so a longer wait
    * buys nothing; a terminal that does not implement it never answers, and the whole wait is dead time in front of the
    * user. A tenth of a second, paid once per window size, is under the threshold at which a start-up stutter is
    * noticed.
    */
  private val PixelQueryTimeout: FiniteDuration = 100.millis

  /** One pixel-cache entry: the text-area size in pixels (`None` when the terminal did not answer) and the resize
    * generation it was measured at.
    */
  private final case class PixelEntry(generation: Long, pixels: Option[Size])

  /** How long [[JLine3Backend.enableRawMode]] waits for the capability answers before starting the app anyway.
    *
    * Paid once, at start-up, and only by a terminal that answers nothing — every terminal that implements DA1, which is
    * almost all of them, ends the wait as soon as its reply arrives. The budget is the same tenth of a second the pixel
    * query uses, for the same reason: it is under the threshold at which a start-up stutter is noticed.
    */
  private val CapabilityProbeTimeout: FiniteDuration = 100.millis

  /** Wraps an already-built JLine terminal.
    *
    * [[create]] is the production entry point; this exists so tests can drive a real backend over a pair of streams,
    * because `create` needs the controlling TTY that CI does not have.
    */
  private[terminal] def wrapping(
      terminal: Terminal,
      colorDepth: ColorDepth,
      env: Map[String, String] = sys.env,
      knownCapabilities: Option[TerminalCapabilities] = None,
  ): JLine3Backend =
    JLine3Backend(terminal, colorDepth, env, knownCapabilities)

  /** `probed` with the three OSC features no terminal can be asked about ruled out where the environment rules them
    * out: inside tmux or screen the multiplexer swallows an OSC string it does not know, so a pointer shape, a desktop
    * notification or a taskbar progress written there reaches nobody. Everywhere else they stay as the probe left them
    * — `Unknown`, which means "send it".
    *
    * Both image protocols are ruled out the same way inside a multiplexer, which would need its own passthrough
    * wrapping to deliver them. Outside one, iTerm2's inline images — which cannot be probed — are marked `Yes` when the
    * environment names a terminal that draws them ([[ImageProtocol.namesInlineImageTerminal]]).
    */
  private[terminal] def gatedByEnvironment(
      probed: TerminalCapabilities,
      env: Map[String, String],
  ): TerminalCapabilities =
    val keyboard =
      if EnvironmentOverrides.legacyKeyboard(env) then probed.copy(kittyKeyboard = Support.No) else probed
    if DesktopNotificationProtocol.insideMultiplexer(env) then
      keyboard.copy(
        pointerShapes = Support.No,
        desktopNotifications = Support.No,
        taskbarProgress = Support.No,
        kittyGraphics = Support.No,
        inlineImages = Support.No,
      )
    else if ImageProtocol.namesInlineImageTerminal(env) then keyboard.copy(inlineImages = Support.Yes)
    else keyboard

  /** Opens the process's controlling terminal. Fails with `UnsupportedTerminal` when there is no usable TTY.
    *
    * `colorDepth` defaults to environment-based detection (honoring `NO_COLOR`/`CLICOLOR_FORCE`); pass an explicit
    * value to force a palette regardless of the environment.
    *
    * `knownCapabilities` is what an earlier backend on the same controlling terminal reported as its [[capabilities]]:
    * given, entering raw mode skips the capability probe — one round trip of up to 100 ms — and reports these instead.
    * A process that opens one backend after another (a run of one-shot prompts) probes once and hands the answer on; a
    * terminal does not change what it supports while the process runs.
    */
  def create(
      colorDepth: ColorDepth = ColorDepth.detect(),
      knownCapabilities: Option[TerminalCapabilities] = None,
  ): Either[BackendError, JLine3Backend] =
    try
      // JLine 4.1.4 added a "software signals" layer, on by default, that raises the JLine-level Terminal.Signal
      // *and* still passes the control byte through to whatever reads the stream — its own Javadoc says so, and
      // warns the default may flip in a future release. glyphora already turns that control byte into a KeyEvent
      // through InputDecoder, so leaving the default on would deliver Ctrl+C as both a KeyEvent and a Signal from
      // one keypress. There is no per-builder override, only this global property, set here (not inherited from
      // whatever the process happened to have) so a future JLine patch flipping the default cannot change this
      // backend's behaviour out from under it.
      System.setProperty(TerminalBuilder.PROP_SOFTWARE_SIGNALS, "false")
      val terminal = TerminalBuilder
        .builder()
        .system(true)
        // With JLine's default SIG_DFL handler, PosixSysTerminal calls sun.misc.Signal.handle(sig, SIG_DFL) for every
        // Terminal.Signal, which strips the JVM's own SIGINT handler: Ctrl+C then terminates the process outright, no
        // shutdown hook runs, and the terminal is handed back raw and on the alternate screen. Any non-SIG_DFL value
        // makes JLine install Java-level handlers instead, which `terminal.handle` can route into the event loop.
        .signalHandler(Terminal.SignalHandler.SIG_IGN)
        // Never inherit the platform charset: every border and glyph in tui-widgets is non-ASCII, and under a POSIX
        // locale a locale-derived encoder renders the entire UI as '?'. All three must be set — `encoding` is only
        // the fallback JLine consults *after* the `stdin.encoding`/`stdout.encoding` system properties, so on its own
        // it is silently ignored wherever the JDK derived those from the locale.
        .encoding(UTF_8)
        .stdinEncoding(UTF_8)
        .stdoutEncoding(UTF_8)
        .build()
      if terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR then
        terminal.close()
        Left(BackendError.UnsupportedTerminal("dumb terminal (no TTY attached)"))
      else Right(wrapping(terminal, colorDepth, knownCapabilities = knownCapabilities))
    catch case NonFatal(error) => Left(BackendError.Io(error))

  /** Stops this process the way the shell expects, after the TSTP handler has handed the terminal back.
    *
    * JLine replaced SIGTSTP's default disposition when the terminal was built — that is what makes
    * `Terminal.Signal.TSTP` routable at all — so returning from the handler would otherwise leave the app running after
    * Ctrl+Z.
    *
    * SIGSTOP rather than re-raising SIGTSTP: it cannot be caught, blocked or ignored, so it always stops us, whereas
    * `sun.misc.Signal.raise` needs a Java handler still installed for the signal it is raising — exactly what we just
    * removed. Sending it costs a `fork` per Ctrl+Z, which is invisible at human speed. If this fails (no `kill`, or
    * Windows, which has no SIGTSTP at all) the app simply keeps running with its terminal restored — the same behaviour
    * as before, never a wedged or half-torn-down state.
    */
  private[terminal] def stopSelf(): Unit =
    try
      val pid     = ProcessHandle.current().pid()
      val stopped = ProcessBuilder("kill", "-STOP", pid.toString).start()
      // bounded, not forever: this runs on the signal-dispatch thread, and a wedged `kill` child would otherwise hang
      // the process's only dispatcher — every signal after it lost. The child is destroyed rather than reaped once it
      // outlives the wait; the SIGSTOP it was sent either already landed or was never going to.
      if !stopped.waitFor(1L, TimeUnit.SECONDS) then
        val _ = stopped.destroyForcibly()
    catch case NonFatal(_) => ()

  /** The millisecond timeout to hand JLine's reader for a [[Backend.readEvent]] timeout.
    *
    * `Duration.Infinite.toMillis` *throws*, so an infinite timeout — which [[Backend.readEvent]] documents and
    * `HeadlessBackend` implements as "block until an event arrives" — used to be caught as an I/O failure and reported
    * as `BackendError.Io`, which the runner treats as fatal. JLine reads a non-positive timeout as an unbounded
    * blocking read, which is exactly what was asked for.
    *
    * That encoding is JLine's, not `InputDecoder.awaitReply`'s, which reads `Duration.Inf` as unbounded and a
    * non-positive wait as "already expired". Do not feed this result to the reply round trips.
    */
  private[terminal] def readTimeoutMillis(timeout: Duration): Long =
    if timeout.isFinite then timeout.toMillis else 0L

  /** Reports a teardown step that failed, always, on `System.err`.
    *
    * This used to be gated behind a `GLYPHORA_DEBUG` environment variable, which meant the single most user-visible
    * failure the library has — the terminal handed back raw, on the alternate screen, or with the cursor hidden — was
    * silent by default. By the time this fires the app is exiting and the alternate screen is already gone, so there is
    * no UI left for the line to corrupt.
    */
  private[terminal] def logTeardownFailure(error: BackendError): Unit =
    reportTeardownFailure("could not restore the terminal", error)

  /** The one place teardown failures are printed, parameterized by what was being attempted. */
  private[terminal] def reportTeardownFailure(phrase: String, error: BackendError): Unit =
    System.err.println(s"glyphora: $phrase: ${error.message}")

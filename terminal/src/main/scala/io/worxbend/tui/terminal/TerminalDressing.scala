package io.worxbend.tui.terminal

/** Owns the dress/undress choreography of [[JLine3Backend]]: handing the terminal back to the shell and taking it
  * again, together with the snapshot of modes carried between the two.
  *
  * A collaborator holding the backend rather than a part of its body, because the choreography is a closed world: it
  * reads the backend's mode flags and drives its mode-switching operations, and touches nothing else. Everything here
  * runs under the backend's `screenOwnership` monitor — see [[releaseTerminal]] for what goes wrong without it.
  */
private[terminal] final class TerminalDressing(backend: JLine3Backend):

  import TerminalDressing.{TerminalRelease, TerminalState}

  /** Hands the terminal back to the shell, returning what was active so [[reacquireTerminal]] can restore it, together
    * with the first undress step that failed.
    *
    * Every step is attempted even after one fails: an undress that stops halfway leaves the shell on the alternate
    * screen *and* in raw mode instead of just one of the two. The first failure is kept so `close()` can report it — it
    * used to be logged and dropped, which made "your terminal is now unusable" the one failure this library could not
    * tell anyone about.
    *
    * Called from two threads: the render thread (via [[JLine3Backend.suspend]], [[JLine3Backend.printAbove]] and
    * `close()`) and JLine's signal-dispatch thread (via the SIGTSTP handler). Both take the backend's `screenOwnership`
    * monitor for the whole sequence, and so does the frame write in [[JLine3Backend.draw]] — which is the case the
    * flags alone could never cover. Undressing writes `LeaveAlternateScreen`; a frame is a full screen of cursor moves,
    * SGR sequences and box-drawing glyphs. A Ctrl+Z landing between the two halves of an unguarded `draw` would put
    * that payload on the *primary* screen: the user's shell and their scrollback, which is durable and outlives the
    * app. Nothing repairs it either, because the backend's diff still believes `baseline` is on screen. (The mirror
    * case — SIGCONT racing a draw — is self-healing, since [[reacquireTerminal]] raises a full redraw.) A frame write
    * costs microseconds, so the signal thread never waits perceptibly.
    *
    * What the monitor does *not* make atomic is a whole `suspend`: the body between release and reacquire runs without
    * it, because that body is `$EDITOR`. A Ctrl+Z arriving then still interleaves two complete undress/redress
    * sequences, which stays tolerable for the reason it always was — every step either way is an idempotent mode reset,
    * so the worst outcome is a mode disabled or re-enabled twice, and the last [[reacquireTerminal]] to run leaves the
    * terminal dressed as its snapshot describes. The flags themselves stay volatile because the single-step public
    * operations ([[JLine3Backend.enableRawMode]], [[JLine3Backend.hideCursor]]) write them from outside this monitor.
    */
  def releaseTerminal(): TerminalRelease = backend.screenOwnership.synchronized:
    val state    =
      TerminalState(
        backend.isRawMode,
        backend.alternateScreenActive,
        backend.cursorHidden,
        backend.cursorShape,
        backend.mouseCaptureActive,
        backend.cursorBlinkSuppressed,
      )
    val failures = Seq.newBuilder[BackendError]

    def undress(active: Boolean, step: => Either[BackendError, Unit]): Unit =
      if active then
        step.left.foreach { error =>
          JLine3Backend.logTeardownFailure(error)
          failures += error
        }

    undress(state.mouse.isDefined, backend.disableMouseCapture())
    undress(state.cursorShape != CursorShape.Default, backend.setCursorShape(CursorShape.Default))
    undress(state.cursorBlinkSuppressed, backend.setCursorBlink(true))
    undress(state.cursorHidden, backend.showCursor())
    undress(state.alternateScreen, backend.leaveAlternateScreen())
    undress(state.raw, backend.disableRawMode())
    // Raw-mode release restores the saved shell cursor. Inline parking must be later or that restore undoes it.
    undress(backend.inlineRows > 0, parkBelowInlineFrame())
    // Each output step flushes and checks the writer. An unconditional silent flush here would hide device failures
    // and touch an already-closed JLine handle during an otherwise harmless repeated close.
    TerminalRelease(state, failures.result().headOption)

  /** An inline run leaves its last frame on the primary screen on purpose, so park the cursor on the line below the
    * strip: without this the shell's next prompt would be drawn straight over the frame the app just left behind. The
    * frame ends at the terminal's current bottom row; neither the app's caret nor the restored shell cursor necessarily
    * does. Move there explicitly before scrolling one fresh line into view.
    */
  private def parkBelowInlineFrame(): Either[BackendError, Unit] =
    backend.size.flatMap { size =>
      backend.writeRaw(AnsiSequences.moveTo(0, math.max(0, size.height - 1)) + "\r\n")
    }

  /** Restores what [[releaseTerminal]] undressed. Same two callers, same two threads, same monitor.
    *
    * The raw-mode half re-dresses without re-probing: the probe is a read, and this runs on JLine's signal-dispatch
    * thread after SIGCONT (or in the `finally` of a `suspend`), where reading would race the render thread's in-flight
    * [[io.worxbend.tui.terminal.InputDecoder.decode]] on the decoder's mutable state. The probe was paid at the first
    * raw-mode entry and its answer is retained for the whole session, so the re-dress simply re-applies the modes that
    * answer established — which is also what keeps a `suspend` from paying a 100 ms round trip per call.
    */
  def reacquireTerminal(state: TerminalState): Unit = backend.screenOwnership.synchronized:
    if state.raw then bestEffort(backend.dressRawMode(probe = false))
    if state.alternateScreen then bestEffort(backend.enterAlternateScreen())
    if state.cursorHidden then bestEffort(backend.hideCursor())
    if state.cursorShape != CursorShape.Default then bestEffort(backend.setCursorShape(state.cursorShape))
    // DECSCUSR also selects blinking/steady: restore the app's explicit blink suppression after its shape.
    if state.cursorBlinkSuppressed then bestEffort(backend.setCursorBlink(false))
    state.mouse.foreach(mode => bestEffort(backend.enableMouseCapture(mode)))
    backend.requestFullRedraw() // whatever ran in between owned the screen: repaint everything

  /** Runs a re-dressing step, reporting a failure rather than propagating it.
    *
    * Only [[reacquireTerminal]] uses this. Taking the terminal back has no caller that could act on a failure — it
    * happens on JLine's signal-dispatch thread after SIGCONT, or inside a `finally` — and abandoning the remaining
    * steps would leave the app running against a terminal dressed in neither shape. Undressing is the direction that
    * *does* report, through [[releaseTerminal]].
    */
  private def bestEffort(step: Either[BackendError, Unit]): Unit =
    step.left.foreach(error => JLine3Backend.reportTeardownFailure("could not reclaim the terminal", error))

private[terminal] object TerminalDressing:

  /** Which terminal modes were active at a given moment, so they can be restored in the same shape. */
  final case class TerminalState(
      raw: Boolean,
      alternateScreen: Boolean,
      cursorHidden: Boolean,
      cursorShape: CursorShape,
      mouse: Option[MouseCaptureMode],
      cursorBlinkSuppressed: Boolean,
  )

  object TerminalState:
    /** Nothing was dressed up: cooked mode, primary screen, visible, blinking cursor of the user's own shape, no mouse
      * capture.
      */
    val Undressed: TerminalState = TerminalState(false, false, false, CursorShape.Default, None, false)

  /** The outcome of handing the terminal back: the modes that were undressed (so they can be re-dressed) and the first
    * step that failed while doing it, if any.
    */
  final case class TerminalRelease(state: TerminalState, failure: Option[BackendError])

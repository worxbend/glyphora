package io.worxbend.tui.dsl

import io.worxbend.tui.core.KeyEvent
import io.worxbend.tui.runtime.{CaptureStdio, ReactiveScope, RunnerConfig, RunnerError, Signal, Viewport}
import io.worxbend.tui.terminal.Backend

/** Asks a sequence of [[PromptStep]]s in whichever shape the [[PromptSession]] names, and records their summaries.
  *
  * One entry point for every prompt, so the three shapes cannot disagree about order, cancellation or what lands in the
  * transcript. Blocks the calling thread until the steps are answered or cancelled.
  */
private[dsl] object PromptRun:

  /** Re-checks a fully answered sequence: `Some((step, message))` sends the user back to `step` with `message`. */
  type Check = () => Option[(Int, String)]

  val NoCheck: Check = () => None

  /** Asks `steps` in order and says whether every one was answered and `check` passed, or the user cancelled (Ctrl-C,
    * or the end of input in the plain shape). `navigation` says whether Shift+Tab may return to the previous step.
    *
    * A terminal backend that fails while the steps are asked degrades to the plain shape, asking them again from the
    * first — the same fallback as a backend that never opened. An app callback that throws (a validator, a view) is a
    * bug, not a terminal failure, and is rethrown as itself.
    */
  def ask(
      session: PromptSession,
      steps: Vector[PromptStep[?]],
      navigation: StepNavigation,
      check: Check = NoCheck,
  ): PromptOutcome =
    session.resolve() match
      case PromptSession.Shape.Scripted(answers)                    => scripted(session, answers, steps, check)
      case PromptSession.Shape.Plain(plain)                         => plainly(session, plain, steps, check)
      case PromptSession.Shape.Terminal(backend, theme, plain, ran) =>
        val app = PromptApp(backend, theme, steps, navigation, check)
        app.runWith(backend) match
          case Left(RunnerError.Backend(_, _))        => plainly(session, plain(), steps, check)
          case Left(RunnerError.Handler(error))       => throw error
          case Left(RunnerError.QueuedTask(failures)) => throw failures.first
          case Right(())                              =>
            ran()
            app.summaries.foreach(session.record)
            app.outcome

  private def scripted(
      session: PromptSession,
      answers: Iterator[String],
      steps: Vector[PromptStep[?]],
      check: Check,
  ): PromptOutcome =
    steps.foreach { step =>
      val raw = answers.synchronized {
        if !answers.hasNext then
          throw IllegalArgumentException(s"Prompt.answers has no answer left for '${step.label}'")
        answers.next()
      }
      step.answer(raw).foreach { message =>
        throw IllegalArgumentException(s"the scripted answer '$raw' for '${step.label}' was rejected: $message")
      }
    }
    check().foreach { (index, message) =>
      throw IllegalArgumentException(s"the scripted answers were rejected at '${steps(index).label}': $message")
    }
    steps.foreach(step => session.record(step.summary))
    PromptOutcome.Answered

  private def plainly(
      session: PromptSession,
      plain: PromptSession.Mode.Plain,
      steps: Vector[PromptStep[?]],
      check: Check,
  ): PromptOutcome =
    var index     = 0
    var cancelled = false
    while !cancelled && index < steps.size do
      val step = steps(index)
      plain.write(s"? ${step.question}: ")
      plain.read() match
        case None      =>
          plain.write("\n")
          session.record(PromptStep.summaryText(step.label, PromptStep.Cancelled))
          cancelled = true
        case Some(raw) =>
          if plain.echoInput then plain.write((if step.masked then "" else raw) + "\n")
          step.answer(raw) match
            case Some(message) => plain.write(s"  $message\n")
            case None          =>
              session.record(step.summary)
              index += 1
              if index == steps.size then
                check().foreach { (back, message) =>
                  plain.write(s"  $message\n")
                  index = back
                }
    if cancelled then PromptOutcome.Cancelled else PromptOutcome.Answered

/** How a prompt ended: every step answered, or the user cancelled. */
private[dsl] enum PromptOutcome:
  case Answered, Cancelled

/** Whether a multi-step prompt lets Shift+Tab (the `focus-previous` key) return to the previous step. */
private[dsl] enum StepNavigation:
  case ForwardOnly, CanStepBack

/** The one-shot inline app a terminal prompt is: the answered steps as summary rows, then the step being asked.
  *
  * Ctrl-C (the signal or the key) cancels, Shift+Tab steps back under [[StepNavigation.CanStepBack]], and the last
  * answer ends the run on a frame holding only the summary rows — which [[Backend.keepInlineRows]] then keeps, so the
  * strip collapses to them.
  */
private final class PromptApp(
    backend: Backend,
    appTheme: Theme,
    steps: Vector[PromptStep[?]],
    navigation: StepNavigation,
    check: PromptRun.Check,
) extends TuiApp:

  private val index    = Signal(0)
  private val error    = Signal(Option.empty[String])
  private val finished = Signal(Option.empty[PromptOutcome])

  /** The strip the busiest step needs: the summaries above it plus its own rows. */
  private val height = steps.indices.map(i => i + steps(i).activeRows).maxOption.getOrElse(1)

  override def config: RunnerConfig =
    // the run ends after the first frame drawn once the outcome is known — the summary frame
    RunnerConfig(viewport = Viewport.Inline(height), onFrame = Some(_ => if finished.peek.isDefined then quit()))
      .withCaptureStdio(CaptureStdio.Off)

  override def theme: Theme      = appTheme
  override def whichKey: Boolean = false

  /** How the run ended — [[PromptOutcome.Cancelled]] when it ended without an answer. Read after the run. */
  def outcome: PromptOutcome = finished.peek.getOrElse(PromptOutcome.Cancelled)

  private def completed: Boolean = finished.peek.contains(PromptOutcome.Answered)

  /** The lines the run leaves behind: every answer, or the answers before a cancelled step plus its cancel line. */
  def summaries: Seq[String] =
    if completed then steps.map(_.summary)
    else
      val at = index.peek
      steps.take(at).map(_.summary) ++
        steps.lift(at).map(step => PromptStep.summaryText(step.label, PromptStep.Cancelled))

  def view(using ReactiveScope, Theme): Element =
    val at   = index.get
    val rows = finished.get match
      case Some(PromptOutcome.Answered)  => steps.map(_.summaryLine)
      case Some(PromptOutcome.Cancelled) =>
        steps.take(at).map(_.summaryLine) :+
          PromptStep.summaryElement(steps(at).label, PromptStep.Cancelled, summon[Theme].error)
      case None                          =>
        steps.take(at).map(_.summaryLine) :+ steps(at).view(error.get, onKey).length(steps(at).activeRows)
    Element.column((rows :+ Element.spacer.fill)*)

  override def onInterrupt(): Boolean =
    finish(PromptOutcome.Cancelled)
    true

  override def onStop(): Unit =
    val kept = if completed then steps.size else index.peek + 1
    val _    = backend.keepInlineRows(kept)

  private def onKey(event: KeyEvent): Boolean =
    if finished.peek.isDefined then true
    else if PromptStep.isCtrl(event, 'c') then
      finish(PromptOutcome.Cancelled)
      true
    else if navigation == StepNavigation.CanStepBack && isStepBack(event) then
      if index.peek > 0 then
        index.set(index.peek - 1)
        error.set(None)
      true
    else
      steps(index.peek).key(event) match
        case StepKey.Submit  =>
          submit()
          true
        case StepKey.Handled =>
          error.set(None)
          true
        case StepKey.Ignored => false

  /** The `focus-previous` framework key — Shift+Tab unless a keymap moved it — read as "previous field". A prompt step
    * has one control, so the key has nothing else to do here, and it is the one "back" key no text field claims.
    */
  private def isStepBack(event: KeyEvent): Boolean =
    val keys = FrameworkKeys.current.value
    keys.fires(keys.focusPrevious, event)

  private def submit(): Unit =
    val at = index.peek
    steps(at).submit() match
      case Some(message) => error.set(Some(message))
      case None          =>
        error.set(None)
        if at + 1 < steps.size then index.set(at + 1)
        else
          check() match
            case Some((back, message)) =>
              index.set(back)
              error.set(Some(message))
            case None                  => finish(PromptOutcome.Answered)

  /** Publishes the outcome; the frame drawn after this event is the last one (see [[config]]). */
  private def finish(outcome: PromptOutcome): Unit = finished.set(Some(outcome))

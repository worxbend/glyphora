package io.worxbend.tui.dsl

import io.worxbend.tui.core.KeyEvent
import io.worxbend.tui.runtime.{CaptureStdio, ReactiveScope, RunnerConfig, Signal, Viewport}
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

  /** Asks `steps` in order. `true` once every one is answered and `check` passes; `false` when the user cancelled
    * (Ctrl-C, or the end of input in the plain shape). `allowBack` lets Ctrl-U return to the previous step.
    */
  def ask(session: PromptSession, steps: Vector[PromptStep[?]], allowBack: Boolean, check: Check = NoCheck): Boolean =
    session.mode match
      case PromptSession.Mode.Scripted(answers)               => scripted(session, answers, steps, check)
      case plain: PromptSession.Mode.Plain                    => plainly(session, plain, steps, check)
      case PromptSession.Mode.Terminal(open, fallback, theme) =>
        if fallback() then plainly(session, PromptSession.standardPlain, steps, check)
        else
          open() match
            case Left(_)        => plainly(session, PromptSession.standardPlain, steps, check)
            case Right(backend) => inTerminal(session, backend, theme, steps, allowBack, check)

  private def scripted(
      session: PromptSession,
      answers: Iterator[String],
      steps: Vector[PromptStep[?]],
      check: Check,
  ): Boolean =
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
    steps.foreach(step => session.record(step.summary, show = true))
    true

  private def plainly(
      session: PromptSession,
      plain: PromptSession.Mode.Plain,
      steps: Vector[PromptStep[?]],
      check: Check,
  ): Boolean =
    var index     = 0
    var cancelled = false
    while !cancelled && index < steps.size do
      val step = steps(index)
      plain.write(s"? ${step.question}: ")
      plain.read() match
        case None      =>
          plain.write("\n")
          session.record(PromptStep.summaryText(step.label, PromptStep.Cancelled), show = false)
          cancelled = true
        case Some(raw) =>
          if plain.echoInput then plain.write((if step.masked then "" else raw) + "\n")
          step.answer(raw) match
            case Some(message) => plain.write(s"  $message\n")
            case None          =>
              session.record(step.summary, show = false)
              index += 1
              if index == steps.size then
                check().foreach { (back, message) =>
                  plain.write(s"  $message\n")
                  index = back
                }
    !cancelled

  private def inTerminal(
      session: PromptSession,
      backend: Backend,
      theme: Theme,
      steps: Vector[PromptStep[?]],
      allowBack: Boolean,
      check: Check,
  ): Boolean =
    val app = PromptApp(backend, theme, steps, allowBack, check)
    app.runWith(backend) match
      case Left(error) => throw IllegalStateException(s"the prompt could not run: ${error.message}")
      case Right(())   => ()
    app.summaries.foreach(line => session.record(line, show = false))
    app.completed

/** The one-shot inline app a terminal prompt is: the answered steps as summary rows, then the step being asked.
  *
  * Ctrl-C (the signal or the key) cancels, Ctrl-U steps back when `allowBack`, and the last answer ends the run on a
  * frame holding only the summary rows — which [[Backend.keepInlineRows]] then keeps, so the strip collapses to them.
  */
private final class PromptApp(
    backend: Backend,
    appTheme: Theme,
    steps: Vector[PromptStep[?]],
    allowBack: Boolean,
    check: PromptRun.Check,
) extends TuiApp:

  private val index   = Signal(0)
  private val error   = Signal(Option.empty[String])
  private val outcome = Signal(Option.empty[Boolean])

  /** The strip the busiest step needs: the summaries above it plus its own rows. */
  private val height = steps.indices.map(i => i + steps(i).activeRows).maxOption.getOrElse(1)

  override def config: RunnerConfig =
    // the run ends after the first frame drawn once the outcome is known — the summary frame
    RunnerConfig(viewport = Viewport.Inline(height), onFrame = Some(_ => if outcome.peek.isDefined then quit()))
      .withCaptureStdio(CaptureStdio.Off)

  override def theme: Theme      = appTheme
  override def whichKey: Boolean = false

  /** Whether every step was answered. Read after the run. */
  def completed: Boolean = outcome.peek.contains(true)

  /** The lines the run leaves behind: every answer, or the answers before a cancelled step plus its cancel line. */
  def summaries: Seq[String] =
    if completed then steps.map(_.summary)
    else
      val at = index.peek
      steps.take(at).map(_.summary) ++
        steps.lift(at).map(step => PromptStep.summaryText(step.label, PromptStep.Cancelled))

  def view(using ReactiveScope, Theme): Element =
    val at   = index.get
    val rows = outcome.get match
      case Some(true)  => steps.map(_.summaryLine)
      case Some(false) =>
        steps.take(at).map(_.summaryLine) :+
          PromptStep.summaryElement(steps(at).label, PromptStep.Cancelled, summon[Theme].error)
      case None        =>
        steps.take(at).map(_.summaryLine) :+ steps(at).view(error.get, onKey).length(steps(at).activeRows)
    Element.column((rows :+ Element.spacer.fill)*)

  override def onInterrupt(): Boolean =
    finish(answered = false)
    true

  override def onStop(): Unit =
    val kept = if completed then steps.size else index.peek + 1
    val _    = backend.keepInlineRows(kept)

  private def onKey(event: KeyEvent): Boolean =
    if outcome.peek.isDefined then true
    else if PromptStep.isCtrl(event, 'c') then
      finish(answered = false)
      true
    else if allowBack && PromptStep.isCtrl(event, 'u') then
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
            case None                  => finish(answered = true)

  /** Publishes the outcome; the frame drawn after this event is the last one (see [[config]]). */
  private def finish(answered: Boolean): Unit = outcome.set(Some(answered))

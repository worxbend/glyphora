package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Span, Style}
import io.worxbend.tui.runtime.{
  CaptureStdio,
  LogLevel,
  LogRing,
  ReactiveScope,
  RenderThread,
  RunnerConfig,
  RunnerError,
  Signal,
}
import io.worxbend.tui.runtime.Viewport
import io.worxbend.tui.terminal.Backend

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

/** Where the work under [[Prompt.task]] reports what it is doing: one call, one line in the scrolling log under the
  * spinner. Safe to call from any thread — the work runs on a thread of its own, and so may anything it starts.
  */
trait TaskLog:
  def apply(line: String): Unit

/** The background-work prompts — [[Prompt.task]] and [[Prompt.loader]] — in all three session shapes. */
private[dsl] object PromptTask:

  /** How many log lines the terminal shape keeps under the spinner. */
  val LogRows: Int = 5

  /** How long a cancelled [[Prompt.task]] waits for its interrupted work to finish before it answers `None`.
    *
    * Cancelling interrupts the worker and then joins it, so work that honours the interrupt — a blocking read, a
    * `sleep`, a `Future` it awaits — has wound down (closed its files, removed its temp directory) by the time the
    * caller sees `None`. Work that ignores the interrupt is waited for this long and no longer: it keeps running on its
    * daemon thread, and the process exiting ends it.
    */
  val CancelJoinBound: FiniteDuration = 2.seconds

  def successText(label: String, detail: String): String =
    val done = Messages.active.taskSucceeded
    if detail.isEmpty then s"$done $label" else s"$done $label $detail"

  def failureText(label: String, detail: String): String = s"${Messages.active.taskFailed} $label $detail"

  /** Runs `work` and answers its result, or `None` when the user cancelled; a failure of `work` is rethrown once the
    * prompt has left its summary line.
    *
    * @param body
    *   what the terminal shape shows under the spinner line while the work runs — the log, or a progress row
    * @param work
    *   the work, handed the log and a way to run a body on the render thread (immediate in the line shapes)
    * @param detail
    *   the summary line's text after the label, from the result
    */
  def run[A](
      session: PromptSession,
      label: String,
      rows: Int,
      body: LogRing => (ReactiveScope, Theme) ?=> Element,
      work: (TaskLog, (() => Unit) => Unit) => A,
      detail: A => String,
  ): Option[A] =
    session.resolve() match
      case PromptSession.Shape.Scripted(_)                     => inLine(session, label, work, detail, None)
      case PromptSession.Shape.Plain(plain)                    => inLine(session, label, work, detail, Some(plain))
      case PromptSession.Shape.Terminal(backend, theme, plain) =>
        val app = TaskApp(backend, theme, label, rows, body, work, detail)
        app.runWith(backend) match
          case Left(RunnerError.Backend(_, _))        =>
            // the terminal went away under the prompt: finish in the plain shape, running the work only if the run
            // never started it — work already under way is waited for, never started a second time
            app.abandon() match
              case None         => inLine(session, label, work, detail, Some(plain()))
              case Some(result) => report(session, label, detail, result, Some(plain()))
          case Left(RunnerError.Handler(error))       => throw error
          case Left(RunnerError.QueuedTask(failures)) => throw failures.first
          case Right(())                              =>
            session.record(app.summary)
            if app.outcome.isEmpty then app.awaitCancelled(CancelJoinBound)
            app.outcome match
              case Some(Success(value)) => Some(value)
              case Some(Failure(error)) => throw error
              case None                 => None

  /** The line shapes: the work runs on the calling thread and each log line is written as it comes — to `plain`, or to
    * the console in the scripted shape, whose session echoes the summary line itself.
    */
  private def inLine[A](
      session: PromptSession,
      label: String,
      work: (TaskLog, (() => Unit) => Unit) => A,
      detail: A => String,
      plain: Option[PromptSession.Mode.Plain],
  ): Option[A] =
    val log: TaskLog = line => writeLine(plain, "  " + line)
    report(session, label, detail, Try(work(log, body => body())), plain)

  private def writeLine(plain: Option[PromptSession.Mode.Plain], line: String): Unit =
    plain.fold(Console.write(line + "\n"))(_.write(line + "\n"))

  /** Records the summary line of `result` (writing it to `plain` when there is one) and answers the value, or rethrows
    * the failure once its summary is down.
    */
  private def report[A](
      session: PromptSession,
      label: String,
      detail: A => String,
      result: Try[A],
      plain: Option[PromptSession.Mode.Plain],
  ): Option[A] =
    val summary = result match
      case Success(value) => successText(label, detail(value))
      case Failure(error) => failureText(label, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
    session.record(summary)
    plain.foreach(_ => writeLine(plain, summary))
    result match
      case Success(value) => Some(value)
      case Failure(error) => throw error

/** The inline app behind a terminal [[Prompt.task]]: a spinner line over `body`, the work on a thread of its own, and a
  * last frame of one summary line. Ctrl-C cancels: the worker is interrupted, the run ends on its summary frame, and
  * the calling thread then joins the worker for at most [[PromptTask.CancelJoinBound]] before answering `None`.
  */
private final class TaskApp[A](
    backend: Backend,
    appTheme: Theme,
    label: String,
    rows: Int,
    body: LogRing => (ReactiveScope, Theme) ?=> Element,
    work: (TaskLog, (() => Unit) => Unit) => A,
    detail: A => String,
) extends TuiApp:

  private val ring                               = LogRing(256)
  private val finished                           = Signal(Option.empty[Option[Try[A]]])
  @volatile private var worker: Option[Thread]   = None
  // the worker's result as it left the work, for a run that ended before the render thread could hear it
  @volatile private var produced: Option[Try[A]] = None

  override def config: RunnerConfig =
    // a `println` from the work lands in the log rather than over the strip
    // and the run ends after the first frame drawn once the work is over, which is the summary: quitting from the
    // completion itself could stop the loop before that frame, since queued work drains in batches
    RunnerConfig(
      viewport = Viewport.Inline(1 + rows),
      onFrame = Some(_ => if finished.peek.isDefined then quit()),
    ).withCaptureStdio(CaptureStdio.Console(ring))

  override def theme: Theme          = appTheme
  override def whichKey: Boolean     = false
  override def bindings: KeyBindings = KeyBindings(binding("ctrl+c", "cancel")(cancel()))

  /** For a run the backend ended early: `None` when the work never started, otherwise its result once it finishes —
    * waited for on the calling thread. Read after the run.
    */
  def abandon(): Option[Try[A]] =
    worker.map { thread =>
      thread.join()
      produced.getOrElse(Failure(IllegalStateException(s"the task '$label' ended without a result")))
    }

  /** Joins a cancelled run's interrupted worker for at most `bound`, on the calling thread — never the render thread,
    * which has already drawn the summary frame and stopped. Read after the run.
    */
  def awaitCancelled(bound: FiniteDuration): Unit = worker.foreach(_.join(math.max(1L, bound.toMillis)))

  /** The work's result, `None` when cancelled. Read after the run. */
  def outcome: Option[Try[A]] = finished.peek.flatten

  def summary: String =
    outcome match
      case Some(Success(value)) => PromptTask.successText(label, detail(value))
      case Some(Failure(error)) =>
        PromptTask.failureText(label, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
      case None                 => PromptTask.failureText(label, PromptStep.Cancelled)

  override def onStart(): Unit =
    val loop         = RenderThread.capture()
    val log: TaskLog = line => ring.log(LogLevel.Info, label, line)
    val post         = (body: () => Unit) => { val _ = loop.execute(body()) }
    val thread       = Thread
      .ofPlatform()
      .daemon()
      .name(s"glyphora-task-$label")
      .unstarted { () =>
        val result = Try(work(log, post))
        produced = Some(result)
        val _      = loop.execute(complete(Some(result)))
      }
    worker = Some(thread)
    thread.start()

  def view(using ReactiveScope, Theme): Element =
    val theme = summon[Theme]
    finished.get match
      case Some(Some(Success(_))) => line(summary, theme.success)
      case Some(_)                => line(summary, theme.error)
      case None                   => Element.column(Element.spinner(label).length(1), body(ring).length(rows))

  private def line(text: String, style: Style): Element =
    Element.column(Element.line(Span(text, style)).length(1), Element.spacer.fill)

  override def onInterrupt(): Boolean =
    cancel()
    true

  override def onStop(): Unit =
    val _ = backend.keepInlineRows(1)

  private def cancel(): Unit =
    if finished.peek.isEmpty then
      worker.foreach(_.interrupt())
      complete(None)

  private def complete(result: Option[Try[A]]): Unit =
    if finished.peek.isEmpty then finished.set(Some(result))

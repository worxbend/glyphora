package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Span, Style}
import io.worxbend.tui.runtime.{CaptureStdio, LogLevel, LogRing, ReactiveScope, RenderThread, RunnerConfig, Signal}
import io.worxbend.tui.runtime.Viewport
import io.worxbend.tui.terminal.Backend

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
    session.mode match
      case PromptSession.Mode.Scripted(_)  => inLine(session, label, work, detail, show = true, None)
      case plain: PromptSession.Mode.Plain => inLine(session, label, work, detail, false, Some(plain))
      case PromptSession.Mode.Terminal(open, fallback, theme) =>
        def plainly = inLine(session, label, work, detail, show = false, Some(PromptSession.standardPlain))
        if fallback() then plainly
        else
          open() match
            case Left(_)        => plainly
            case Right(backend) => inTerminal(session, backend, theme, label, rows, body, work, detail)

  /** The line shapes: the work runs on the calling thread and each log line is written as it comes. */
  private def inLine[A](
      session: PromptSession,
      label: String,
      work: (TaskLog, (() => Unit) => Unit) => A,
      detail: A => String,
      show: Boolean,
      plain: Option[PromptSession.Mode.Plain],
  ): Option[A] =
    val write: String => Unit = line => plain.fold(Console.write(line + "\n"))(_.write(line + "\n"))
    val log: TaskLog          = line => write("  " + line)
    Try(work(log, body => body())) match
      case Success(value) =>
        val summary = successText(label, detail(value))
        session.record(summary, show)
        if !show then write(summary)
        Some(value)
      case Failure(error) =>
        val summary = failureText(label, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
        session.record(summary, show)
        if !show then write(summary)
        throw error

  private def inTerminal[A](
      session: PromptSession,
      backend: Backend,
      theme: Theme,
      label: String,
      rows: Int,
      body: LogRing => (ReactiveScope, Theme) ?=> Element,
      work: (TaskLog, (() => Unit) => Unit) => A,
      detail: A => String,
  ): Option[A] =
    val app = TaskApp(backend, theme, label, rows, body, work, detail)
    app.runWith(backend) match
      case Left(error) => throw IllegalStateException(s"the task prompt could not run: ${error.message}")
      case Right(())   => ()
    session.record(app.summary, show = false)
    app.outcome match
      case Some(Success(value)) => Some(value)
      case Some(Failure(error)) => throw error
      case None                 => None

/** The inline app behind a terminal [[Prompt.task]]: a spinner line over `body`, the work on a thread of its own, and a
  * last frame of one summary line. Ctrl-C cancels: the worker is interrupted, and the prompt answers `None` without
  * waiting for it.
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

  private val ring                             = LogRing(256)
  private val finished                         = Signal(Option.empty[Option[Try[A]]])
  @volatile private var worker: Option[Thread] = None

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

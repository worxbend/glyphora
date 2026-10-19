package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{DurationInt, DurationLong}

/** Covers the no-TTY policies: what `TuiApp.run()` does when [[TuiApp.printTarget]] says the process has no terminal to
  * be interactive on. The target is injected — a fixed size, a fixed colour depth and a `StringBuilder` for standard
  * output — so nothing here depends on how Mill's forked test JVM happens to be attached.
  */
final class NonInteractiveRunSpec extends AnyFunSuite:

  private val Esc = "\u001b["

  /** The hello-world shape: a panel the app never leaves on its own, so only the policy can end the run. */
  private final class PrintedApp(policy: NonInteractivePolicy, out: StringBuilder, colors: ColorDepth) extends TuiApp:
    var starts                                              = 0
    override def config: RunnerConfig                       = RunnerConfig().withNonInteractive(policy)
    override def theme: Theme                               = Theme.Dark
    override def onStart(): Unit                            = starts += 1
    override protected def printTarget: Option[PrintTarget] =
      Some(PrintTarget(Size(24, 5), colors, text => { val _ = out.append(text) }))
    def view(using ReactiveScope, Theme): Element           =
      panel("Hello")(text("Welcome").bold, text("Press q").dim).onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), _) => quit(); true
        case _                              => false
      }

  test("PrintFinalFrame prints exactly one frame and the run returns Right"):
    val out  = StringBuilder()
    val app  = PrintedApp(NonInteractivePolicy.PrintFinalFrame, out, ColorDepth.TrueColor)
    assert(app.run() == Right(()))
    val text = out.result()
    assert(app.starts == 1)
    assert(text.linesIterator.count(_.contains("Hello")) == 1, text)
    assert(text.contains("Welcome") && text.contains("Press q"), text)
    // five rows at Size(24, 5), each on its own line, the whole frame newline-terminated
    assert(text.endsWith("\n") && text.count(_ == '\n') == 5, text)
    // the bold title text arrives as a delta from the border style, so match the bold parameter inside any SGR
    assert("\u001b\\[(?:[0-9]+;)*1(?:;[0-9]+)*m".r.findFirstIn(text).nonEmpty, text.replace("\u001b", "<ESC>"))

  test("Plain prints the text rows with no SGR and no trailing blank rows"):
    val out  = StringBuilder()
    val app  = PrintedApp(NonInteractivePolicy.Plain, out, ColorDepth.TrueColor)
    assert(app.run() == Right(()))
    val text = out.result()
    assert(!text.contains(Esc), text)
    // a five-row panel: top border, two lines, the blank row inside the frame, bottom border
    assert(text.linesIterator.size == 5, text)
    assert(text.contains("Welcome") && text.contains("Press q"), text)

  test("Fail leaves the TTY path alone: printTarget is never consulted"):
    var consulted = false
    val app       = new TuiApp:
      override protected def printTarget: Option[PrintTarget]               =
        consulted = true
        None
      override protected def createBackend(): Either[BackendError, Backend] =
        Left(BackendError.UnsupportedTerminal("no tty in this test"))
      def view(using ReactiveScope, Theme): Element                         = text("unused")
    app.run() match
      case Left(RunnerError.Backend(BackendError.UnsupportedTerminal(_), _)) => ()
      case other => fail(s"expected the TTY failure, got $other")
    assert(!consulted)

  test("a target of None under a print policy still runs interactively"):
    val app = new TuiApp:
      override def config: RunnerConfig                                     =
        RunnerConfig().withNonInteractive(NonInteractivePolicy.PrintFinalFrame)
      override protected def printTarget: Option[PrintTarget]               = None
      override protected def createBackend(): Either[BackendError, Backend] =
        Left(BackendError.UnsupportedTerminal("no tty in this test"))
      def view(using ReactiveScope, Theme): Element                         = text("unused")
    assert(app.run().isLeft)

  /** An app whose content arrives from background work started in `onStart`, after `delayMillis`. */
  private final class LoadingApp(policy: NonInteractivePolicy, out: StringBuilder, delayMillis: Long) extends TuiApp:
    private val loaded                                      = Signal("loading…")
    override def config: RunnerConfig                       = RunnerConfig().withNonInteractive(policy)
    override def onStart(): Unit                            =
      Async.run { Thread.sleep(delayMillis); "loaded 42 rows" }(loaded.set)
    override protected def printTarget: Option[PrintTarget] =
      Some(PrintTarget(Size(24, 2), ColorDepth.NoColor, text => { val _ = out.append(text) }))
    def view(using ReactiveScope, Theme): Element           = text(loaded.get)

  test("PrintFinalFrame alone prints the initial state, without waiting for background work"):
    val out = StringBuilder()
    assert(LoadingApp(NonInteractivePolicy.PrintFinalFrame, out, 200).run() == Right(()))
    assert(out.result().contains("loading"), out.result())

  test("awaitIdle waits for the queued async work, then prints the settled frame well before the timeout"):
    val out     = StringBuilder()
    val policy  = NonInteractivePolicy.PrintFinalFrame.awaitIdle(10.seconds)
    val started = System.nanoTime()
    assert(LoadingApp(policy, out, 150).run() == Right(()))
    val elapsed = (System.nanoTime() - started).nanos
    assert(out.result().contains("loaded 42 rows"), out.result())
    assert(out.result().linesIterator.count(_.nonEmpty) == 1, "still exactly one frame")
    assert(elapsed < 5.seconds, s"returned once idle, not at the timeout: $elapsed")

  test("awaitIdle gives up at the timeout and prints whatever the app composed by then"):
    val out = StringBuilder()
    assert(LoadingApp(NonInteractivePolicy.PrintFinalFrame.awaitIdle(100.millis), out, 3000).run() == Right(()))
    assert(out.result().contains("loading"), out.result())

  test("awaitIdle keeps the policy's output and leaves Fail as Fail"):
    assert(NonInteractivePolicy.Fail.awaitIdle(1.second) == NonInteractivePolicy.Fail)
    assert(NonInteractivePolicy.Plain.awaitIdle(1.second).output == NonInteractivePolicy.Plain)
    assert(NonInteractivePolicy.PrintFinalFrame.awaitIdle(1.second).idleTimeout.contains(1.second))
    assert(NonInteractivePolicy.PrintFinalFrame.idleTimeout.isEmpty)

  // ---------------------------------------------------------------- the frame sink on its own

  private def frame(label: String): Buffer =
    val buffer = Buffer(Rect(0, 0, 10, 3))
    buffer.setString(0, 0, label, Style.Default.withFg(Color.Cyan))
    buffer

  test("Plain writes identical consecutive frames once"):
    val out     = StringBuilder()
    val printer = FramePrinter(NonInteractivePolicy.Plain, ColorDepth.TrueColor, text => { val _ = out.append(text) })
    printer.frame(frame("one"))
    printer.frame(frame("one"))
    printer.frame(frame("two"))
    printer.frame(frame("two"))
    printer.finish()
    assert(out.result() == "one\ntwo\n", out.result())

  test("PrintEveryFrame writes every frame, PrintFinalFrame only the last on finish"):
    val every        = StringBuilder()
    val everyPrinter =
      FramePrinter(NonInteractivePolicy.PrintEveryFrame, ColorDepth.NoColor, text => { val _ = every.append(text) })
    everyPrinter.frame(frame("one"))
    everyPrinter.frame(frame("one"))
    everyPrinter.finish()
    assert(every.result() == "one\n\n\none\n\n\n", every.result())

    val last        = StringBuilder()
    val lastPrinter =
      FramePrinter(NonInteractivePolicy.PrintFinalFrame, ColorDepth.NoColor, text => { val _ = last.append(text) })
    lastPrinter.frame(frame("one"))
    lastPrinter.frame(frame("two"))
    assert(last.isEmpty)
    lastPrinter.finish()
    assert(last.result() == "two\n\n\n", last.result())

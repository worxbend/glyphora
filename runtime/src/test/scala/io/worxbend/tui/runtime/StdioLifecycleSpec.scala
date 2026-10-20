package io.worxbend.tui.runtime

import io.worxbend.tui.core.Style
import io.worxbend.tui.terminal.{JLine3Backend, StdioTerminalFixture}

import org.jline.terminal.{Size, Sized, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.logging.{ConsoleHandler, Logger}

/** Real JLine PrintWriter bytes, not HeadlessBackend's bookkeeping. */
final class StdioLifecycleSpec extends AnyFunSuite:
  private def withStreams(body: (ByteArrayOutputStream, ByteArrayOutputStream) => Unit): Unit =
    val out       = ByteArrayOutputStream()
    val err       = ByteArrayOutputStream()
    val beforeOut = System.out
    val beforeErr = System.err
    val stdout    = PrintStream(out, true, UTF_8)
    val stderr    = PrintStream(err, true, UTF_8)
    System.setOut(stdout)
    System.setErr(stderr)
    try Console.withOut(stdout)(Console.withErr(stderr)(body(out, err)))
    finally
      System.setOut(beforeOut)
      System.setErr(beforeErr)

  private def backend(sink: ByteArrayOutputStream): JLine3Backend =
    val terminal =
      LineDisciplineTerminal("stdio-lifecycle", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    terminal.setSize(Size.of(20, 3): Sized)
    StdioTerminalFixture.wrapping(terminal)

  private def paint(frame: Frame): Unit = frame.buffer.setString(0, 0, "live-ui", Style.Default)

  for operation <- Seq("worker println", "printAbove", "insertBefore", "suspend") do
    test(s"queued $operation repaints immediately after the alternate screen is reentered"):
      withStreams { (_, _) =>
        val sink      = ByteArrayOutputStream()
        val terminal  = backend(sink)
        var frames    = 0
        var handle    = Option.empty[RunnerHandle]
        val config    = RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)
        val completed = CountDownLatch(1)
        val result    = TerminalRunner(terminal, config).run(
          active =>
            handle = Some(active)
            val owner    = RenderThread.capture()
            val watchdog =
              Thread(() => if !completed.await(10, TimeUnit.SECONDS) then owner.enqueue(() => active.quit()))
            watchdog.setDaemon(true)
            watchdog.start()
          ,
          (_, _) => EventOutcome.Ignored,
          frame =>
            paint(frame)
            frames += 1
            if frames == 1 then
              val owner  = RenderThread.capture()
              val worker = Thread(() =>
                owner.enqueue(() => {
                  operation match
                    case "worker println" =>
                      val printer = Thread(() => System.out.println("background-line"))
                      printer.start()
                      printer.join()
                    case "printAbove"     => handle.foreach(_.printAbove(Seq("background-line")))
                    case "insertBefore"   =>
                      handle.foreach(
                        _.insertBefore(
                          1,
                          (area, buffer) => buffer.setString(area.x, area.y, "background-line", Style.Default),
                        )
                      )
                    case _                => handle.foreach(_.suspend(()))
                })
              )
              worker.start()
              worker.join()
            else handle.foreach(_.quit()),
        )
        completed.countDown()
        assert(result == Right(()))
        assert(frames == 2)
        val bytes     = sink.toString(UTF_8)
        val reentry   = bytes.lastIndexOf("\u001b[?1049h")
        assert(reentry >= 0)
        assert(bytes.indexOf("live-ui", reentry) > reentry)
      }

  test("Scala onStop is captured until the terminal is restored and stderr keeps its fallback channel"):
    withStreams { (out, err) =>
      val sink   = ByteArrayOutputStream()
      val result = TerminalRunner(
        backend(sink),
        RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove),
        onStop = () => { println("saved"); Console.err.println("failure") },
      ).run(_.quit(), (_, _) => EventOutcome.Ignored, paint)
      assert(result == Right(()))
      assert(out.toString(UTF_8) == "saved" + System.lineSeparator())
      assert(err.toString(UTF_8) == "failure" + System.lineSeparator())
      assert(!sink.toString(UTF_8).contains("saved"))
    }

  test("suspend preserves complete-before-partial-before-body ordering"):
    withStreams { (out, _) =>
      // The original stdout and JLine's real writer share one tty-shaped byte sink, so ordering is observable.
      val result = TerminalRunner(backend(out), RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        handle =>
          println("complete")
          print("partial")
          handle.suspend(println("body"))
          handle.quit()
        ,
        (_, _) => EventOutcome.Ignored,
        paint,
      )
      assert(result == Right(()))
      val bytes  = out.toString(UTF_8)
      assert(bytes.indexOf("complete") >= 0)
      assert(bytes.indexOf("complete") < bytes.indexOf("partial"))
      assert(bytes.indexOf("partial") < bytes.indexOf("body"))
      assert(bytes.contains("partial" + System.lineSeparator() + "body" + System.lineSeparator()))
    }

  test("carriage-return progress keeps the last update and CRLF retains the completed line"):
    withStreams { (_, _) =>
      val ring   = LogRing(20)
      val result = TerminalRunner(
        backend(ByteArrayOutputStream()),
        RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(ring)),
      ).run(
        handle =>
          System.out.print("\r33%\r66%\r99%\rdone\nnormal\r\n")
          handle.quit()
        ,
        (_, _) => EventOutcome.Ignored,
        paint,
      )
      assert(result == Right(()))
      assert(ring.snapshot.map(_.message) == Vector("done", "normal"))
    }

  test("preinitialised JUL console handler cannot bypass capture or duplicate an attached ring bridge"):
    withStreams { (_, err) =>
      val root      = Logger.getLogger("")
      val handler   = ConsoleHandler()
      val originals = root.getHandlers.toVector
      originals.foreach(root.removeHandler)
      root.addHandler(handler)
      val ring      = LogRing(20)
      val bridge    = JulBridge.attach(ring)
      try
        val result = TerminalRunner(
          backend(ByteArrayOutputStream()),
          RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(ring)),
        ).run(
          handle => { Logger.getLogger("stdio-test").warning("jul-record"); handle.quit() },
          (_, _) => EventOutcome.Ignored,
          paint,
        )
        assert(result == Right(()))
        assert(ring.snapshot.count(_.message == "jul-record") == 1)
        assert(ring.snapshot.size == 1)
        assert(err.toString(UTF_8).isEmpty)
        assert(root.getHandlers.contains(handler))
      finally
        bridge.cancel()
        root.removeHandler(handler)
        originals.foreach(root.addHandler)
    }

  test("a preinitialised JUL console handler without a bridge is captured once and restored by identity"):
    withStreams { (_, err) =>
      val root      = Logger.getLogger("")
      val originals = root.getHandlers.toVector
      originals.foreach(root.removeHandler)
      val handler   = ConsoleHandler()
      root.addHandler(handler)
      val ring      = LogRing(20)
      try
        val result = TerminalRunner(
          backend(ByteArrayOutputStream()),
          RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(ring)),
        ).run(
          handle => { Logger.getLogger("stdio-test").warning("captured-jul"); handle.quit() },
          (_, _) => EventOutcome.Ignored,
          paint,
        )
        assert(result == Right(()))
        assert(err.toString(UTF_8).isEmpty)
        assert(ring.snapshot.count(_.message.contains("captured-jul")) == 1)
        assert(root.getHandlers.toVector == Vector(handler))
      finally
        root.removeHandler(handler)
        originals.foreach(root.addHandler)
    }

  test("an Async worker inherited from an exited run forwards its later Scala println to the active run"):
    withStreams { (out, _) =>
      val started  = CountDownLatch(1)
      val release  = CountDownLatch(1)
      val finished = CountDownLatch(1)
      val first    = LogRing(20)
      val second   = LogRing(20)
      try
        assert(
          TerminalRunner(
            backend(ByteArrayOutputStream()),
            RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(first)),
          ).run(
            handle =>
              Async.onWorker {
                started.countDown()
                if release.await(20, TimeUnit.SECONDS) then println("late-worker")
                finished.countDown()
              }
              assert(started.await(10, TimeUnit.SECONDS))
              handle.quit()
            ,
            (_, _) => EventOutcome.Ignored,
            paint,
          ) == Right(())
        )
        assert(
          TerminalRunner(
            backend(ByteArrayOutputStream()),
            RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(second)),
          ).run(
            handle =>
              release.countDown()
              assert(finished.await(10, TimeUnit.SECONDS))
              handle.quit()
            ,
            (_, _) => EventOutcome.Ignored,
            paint,
          ) == Right(())
        )
        assert(first.snapshot.isEmpty)
        assert(second.snapshot.map(_.message) == Vector("late-worker"))
        assert(out.toString(UTF_8).isEmpty)
      finally release.countDown()
    }

  test("process-lifetime timer and worker threads use the current Scala Console capture on sequential runs"):
    withStreams { (out, _) =>
      for runIndex <- 1 to 2 do
        val ring   = LogRing(20)
        val result = TerminalRunner(
          backend(ByteArrayOutputStream()),
          RunnerConfig.Default.withCaptureStdio(CaptureStdio.Console(ring)),
        ).run(
          handle =>
            val finished = CountDownLatch(2)
            Async.onWorker { println(s"worker-$runIndex"); finished.countDown() }
            val timer    = Async.scheduler.submit(new Runnable:
              def run(): Unit = { println(s"timer-$runIndex"); finished.countDown() })
            assert(finished.await(10, TimeUnit.SECONDS))
            timer.get(10, TimeUnit.SECONDS)
            handle.quit()
          ,
          (_, _) => EventOutcome.Ignored,
          paint,
        )
        assert(result == Right(()))
        assert(ring.snapshot.map(_.message).toSet == Set(s"worker-$runIndex", s"timer-$runIndex"))
      assert(out.toString(UTF_8).isEmpty)
    }

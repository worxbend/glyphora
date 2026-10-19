package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, KeyModifiers, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}

/** Standard output and error captured for the duration of a run — see [[CaptureStdio]].
  *
  * Every test here runs the runner synchronously on the test thread, with the events it needs posted beforehand, so the
  * stream swap and its restoration are observed from the one thread that performed them.
  */
final class StdioCaptureSpec extends AnyFunSuite:

  private def paint(frame: Frame): Unit =
    frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, "live ui", Style.Default), frame.area)

  private val quitKey = Event.Key(KeyEvent(KeyCode.Char('q'), KeyModifiers.None))

  /** Runs `body` with `System.out`/`System.err` pointed at throwaway buffers, so a test can tell "went to the real
    * stream" from "went nowhere", and puts the originals back whatever happens.
    */
  private def withRealStreams[A](body: (ByteArrayOutputStream, ByteArrayOutputStream) => A): A =
    val realOut   = new ByteArrayOutputStream
    val realErr   = new ByteArrayOutputStream
    val priorOut  = System.out
    val priorErr  = System.err
    val outStream = new PrintStream(realOut, true, "UTF-8")
    val errStream = new PrintStream(realErr, true, "UTF-8")
    System.setOut(outStream)
    System.setErr(errStream)
    try Console.withOut(outStream)(Console.withErr(errStream)(body(realOut, realErr)))
    finally
      System.setOut(priorOut)
      System.setErr(priorErr)

  test("under PrintAbove a println from the app lands above the UI and the frame is untouched"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      val before  = System.out
      backend.postEvent(quitKey)
      val result  = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        _ => (),
        (_, handle) =>
          println("x")
          System.err.println("y")
          handle.quit()
          EventOutcome.Ignored
        ,
        paint,
      )
      assert(result == Right(()))
      assert(backend.printedAbove == Seq("x", "y"))
      assert(backend.lastDrawn.exists(frame => (0 until 7).map(x => frame.get(x, 0).symbol).mkString == "live ui"))
      assert(realOut.toString("UTF-8").isEmpty, "a captured line must not also reach the real stream")
      assert(System.out eq before, "System.out is the original object once run returns")
    }

  test("a flood of println under PrintAbove reaches the scrollback in one trip, in order"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      backend.postEvent(quitKey)
      val result  = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        _ => (),
        (_, handle) =>
          (1 to 500).foreach(i => println(s"line $i"))
          handle.quit()
          EventOutcome.Ignored
        ,
        paint,
      )
      assert(result == Right(()))
      assert(backend.printedAbove == (1 to 500).map(i => s"line $i"))
      assert(backend.printAboveCalls == 1, "one scrollback trip for the whole burst")
      assert(realOut.toString("UTF-8").isEmpty)
    }

  test("lines printed from another thread are batched too, and none is lost"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      val result  = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        handle =>
          val worker = Thread(() => (1 to 300).foreach(i => System.out.println(s"bg $i")))
          worker.start()
          worker.join()
          handle.quit()
        ,
        (_, _) => EventOutcome.Ignored,
        paint,
      )
      assert(result == Right(()))
      assert(backend.printedAbove ++ realOut.toString("UTF-8").linesIterator.toSeq == (1 to 300).map(i => s"bg $i"))
      assert(backend.printAboveCalls <= 2, s"${backend.printAboveCalls} trips for one burst")
    }

  test("System.out and System.err are restored when the app throws"):
    withRealStreams { (_, _) =>
      val backend   = HeadlessBackend(Size(20, 3))
      val beforeOut = System.out
      val beforeErr = System.err
      val result    = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        _ => throw IllegalStateException("boom"),
        (_, _) => EventOutcome.Ignored,
        paint,
      )
      assert(result.left.exists { case RunnerError.Handler(error) => error.getMessage == "boom"; case _ => false })
      assert(System.out eq beforeOut)
      assert(System.err eq beforeErr)
    }

  test("a partial line still buffered at exit is flushed to the real stream after the terminal is restored"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      backend.postEvent(quitKey)
      val result  = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        _ => (),
        (_, handle) =>
          print("no newline yet")
          handle.quit()
          EventOutcome.Ignored
        ,
        paint,
      )
      assert(result == Right(()))
      assert(backend.printedAbove.isEmpty)
      assert(realOut.toString("UTF-8") == "no newline yet")
    }

  test("Off leaves the streams alone"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      backend.postEvent(quitKey)
      val result  = TerminalRunner(backend, RunnerConfig.Default).run(
        _ => (),
        (_, handle) =>
          println("x")
          handle.quit()
          EventOutcome.Ignored
        ,
        paint,
      )
      assert(result == Right(()))
      assert(backend.printedAbove.isEmpty)
      assert(realOut.toString("UTF-8") == "x" + System.lineSeparator())
    }

  test("around suspend the captured streams pass straight through to the real ones"):
    withRealStreams { (realOut, _) =>
      val backend = HeadlessBackend(Size(20, 3))
      backend.postEvent(quitKey)
      val result  = TerminalRunner(backend, RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)).run(
        _ => (),
        (_, handle) =>
          print("partial ")
          handle.suspend(println("from the editor"))
          println("back")
          handle.quit()
          EventOutcome.Ignored
        ,
        paint,
      )
      assert(result == Right(()))
      // the partial line is flushed to the real stream before the body runs, so it is not glued to a later line
      assert(realOut.toString("UTF-8") == "partial from the editor" + System.lineSeparator())
      assert(backend.printedAbove == Seq("back"))
    }

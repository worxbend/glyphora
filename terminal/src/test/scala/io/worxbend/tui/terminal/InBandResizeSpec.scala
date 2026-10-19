package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, Size}

import org.jline.terminal.impl.LineDisciplineTerminal
import org.jline.terminal.{Size as JLineSize, Terminal}

import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

import scala.concurrent.duration.DurationInt

/** A mode 2048 in-band resize report, end to end through [[JLine3Backend]] over a real JLine terminal: its pixel fields
  * feed the window-size pixel cache, and the report plus the `SIGWINCH` for the same resize reach the runner as one
  * `Event.Resize`, not two.
  */
final class InBandResizeSpec extends AnyFunSuite:

  private final class Wired:
    private val sink                          = ByteArrayOutputStream()
    val terminal: LineDisciplineTerminal      =
      LineDisciplineTerminal("glyphora-in-band", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    sizeTo(80, 24)
    val backend: JLine3Backend                = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    // the overload pair on LineDisciplineTerminal is ambiguous from Scala; the Terminal interface has one
    def sizeTo(columns: Int, rows: Int): Unit = (terminal: Terminal).setSize(JLineSize.of(columns, rows))
    def written: String                       = sink.toString(UTF_8)
    def forget(): Unit                        = sink.reset()
    def report(rows: Int, columns: Int, heightPx: Int, widthPx: Int): Unit =
      terminal.processInputBytes(s"\u001b[48;$rows;$columns;$heightPx;${widthPx}t".getBytes(UTF_8))
    def resizeTo(columns: Int, rows: Int): Unit                            =
      sizeTo(columns, rows)
      terminal.raise(Terminal.Signal.WINCH)

  private def wired[A](body: Wired => A): A =
    val harness = Wired()
    try body(harness)
    finally
      val _ = harness.backend.close()

  private def resizes(harness: Wired): List[Event] =
    Iterator
      .continually(harness.backend.readEvent(150.millis))
      .take(4)
      .collect { case Right(Some(event)) => event }
      .collect { case resize: Event.Resize => resize }
      .toList

  test("the pixel fields of an in-band resize report fill the pixel cache, so windowSize asks nothing"):
    wired { harness =>
      assert(harness.backend.enableRawMode().isRight)
      harness.sizeTo(100, 30)
      harness.report(rows = 30, columns = 100, heightPx = 600, widthPx = 1000)
      assert(harness.backend.readEvent(150.millis) == Right(Some(Event.Resize(Size(100, 30)))))
      harness.forget()
      assert(harness.backend.windowSize == Right(WindowSize(Size(100, 30), Some(Size(1000, 600)))))
      assert(!harness.written.contains(AnsiSequences.RequestTextAreaPixels))
    }

  test("an in-band report without pixel fields leaves the cache to the query"):
    wired { harness =>
      assert(harness.backend.enableRawMode().isRight)
      harness.terminal.processInputBytes("\u001b[48;24;80t".getBytes(UTF_8))
      assert(harness.backend.readEvent(150.millis) == Right(Some(Event.Resize(Size(80, 24)))))
      harness.forget()
      val _ = harness.backend.windowSize
      assert(harness.written.contains(AnsiSequences.RequestTextAreaPixels))
    }

  test("SIGWINCH then the in-band report of the same resize is one Event.Resize"):
    wired { harness =>
      harness.resizeTo(120, 40)
      harness.report(rows = 40, columns = 120, heightPx = 800, widthPx = 1200)
      assert(resizes(harness) == List(Event.Resize(Size(120, 40))))
    }

  test("the in-band report then SIGWINCH of the same resize is one Event.Resize"):
    wired { harness =>
      harness.report(rows = 40, columns = 120, heightPx = 800, widthPx = 1200)
      assert(harness.backend.readEvent(150.millis) == Right(Some(Event.Resize(Size(120, 40)))))
      harness.resizeTo(120, 40)
      assert(resizes(harness).isEmpty)
    }

  test("two different resizes are both delivered"):
    wired { harness =>
      harness.resizeTo(120, 40)
      assert(resizes(harness) == List(Event.Resize(Size(120, 40))))
      harness.report(rows = 20, columns = 70, heightPx = 400, widthPx = 700)
      assert(resizes(harness) == List(Event.Resize(Size(70, 20))))
    }

  test("a forced resize (SIGCONT) is delivered even at the size the runner already has"):
    val pump = EventPump(InputDecoder(_ => ScriptedInput.NothingAvailable))
    pump.postResize(Size(80, 24))
    assert(pump.poll(50.millis) == Right(Some(Event.Resize(Size(80, 24)))))
    pump.postResize(Size(80, 24))
    assert(pump.poll(50.millis) == Right(None))
    pump.postForcedResize(Size(80, 24))
    assert(pump.poll(50.millis) == Right(Some(Event.Resize(Size(80, 24)))))

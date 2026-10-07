package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Position, Rect, Style}

import org.jline.terminal.{Size as JLineSize, Sized, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal

import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Real JLine bytes replayed into a small ASCII VT screen: assertions concern retained output, not just mode flags. */
final class InlineTerminalSpec extends AnyFunSuite:

  /** Only the controls these tests use; unknown controls fail rather than silently hiding a cursor movement. */
  private final class Screen(var width: Int, val height: Int):
    private var rows   = Vector.fill(height)(" " * width)
    private var x      = 0
    private var y      = 0
    private var saved  = (0, 0)
    private val tokens = "\u001b\\[[0-?]*[ -/]*[@-~]|\u001b[78]|[^\u001b]".r
    private val csi    = "\u001b\\[([0-?]*[ -/]*)([@-~])".r

    def line(row: Int): String = rows(row).stripTrailing()

    def narrow(columns: Int): Unit =
      width = columns
      rows = rows.map(_.take(columns))
      x = math.min(x, width - 1)

    private def newline(): Unit =
      if y == height - 1 then rows = rows.drop(1) :+ (" " * width)
      else y += 1

    def feed(bytes: String): Unit =
      val parsed = tokens.findAllIn(bytes).toVector
      assert(parsed.mkString == bytes, s"unrecognised VT bytes: $bytes")
      parsed.foreach {
        case "\u001b7"            => saved = (math.min(x, width - 1), y)
        case "\u001b8"            =>
          x = saved._1
          y = saved._2
        case "\r"                 => x = 0
        case "\n"                 => newline()
        case csi(parameters, "H") =>
          val coordinates = parameters.split(';').map(_.toInt)
          y = math.max(0, math.min(height - 1, coordinates(0) - 1))
          x = math.max(0, math.min(width - 1, coordinates(1) - 1))
        case csi("2", "J")        => rows = Vector.fill(height)(" " * width)
        case csi("0", "J")        =>
          rows = rows.updated(y, rows(y).take(x) + (" " * (width - x)))
          for row <- y + 1 until height do rows = rows.updated(row, " " * width)
        case csi("2", "K")        => rows = rows.updated(y, " " * width)
        case csi("0", "K")        => rows = rows.updated(y, rows(y).take(x) + (" " * (width - x)))
        // SGR, private modes, kitty push/pop and capability queries do not move this screen's cursor.
        case csi(_, command) if Set("m", "h", "l", "u", "c", "n", "p", "q", "t").contains(command) => ()
        case character if character.length == 1 && character.head >= ' '                           =>
          if x == width then
            x = 0
            newline()
          rows = rows.updated(y, rows(y).updated(x, character.head))
          x += 1
        case unknown => fail(s"unmodelled VT control: $unknown")
      }

  private final class Wired:
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("glyphora-inline", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    terminal.setSize(JLineSize.of(20, 6): Sized)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    val screen   = Screen(20, 6)

    def replay(): String =
      val bytes = sink.toString(UTF_8)
      sink.reset()
      screen.feed(bytes)
      bytes

    def start(raw: Boolean = true): Unit =
      screen.feed("\u001b[3;1HHISTORY-A\u001b[4;1HHISTORY-B\u001b[6;1H")
      if raw then assert(backend.enableRawMode().isRight)
      assert(backend.reserveInlineRows(2).isRight)
      replay()
      assert(screen.line(0) == "HISTORY-A")
      assert(screen.line(1) == "HISTORY-B")

  private def wired(body: Wired => Unit): Unit =
    val harness = Wired()
    try body(harness)
    finally harness.backend.close()

  private def frame(width: Int, text: String): Buffer =
    val buffer = Buffer(Rect(0, 4, width, 2))
    buffer.setString(0, 4, text, Style.Default)
    buffer

  for resizeTerminal <- Seq(false, true) do
    test(s"inline width shrink preserves shell history and clears only its strip (resize=$resizeTerminal)"):
      wired { harness =>
        harness.start()
        assert(harness.backend.draw(frame(20, "XXXXXXXXXXXXXXXXXXXX")).isRight)
        harness.replay()
        if resizeTerminal then
          harness.terminal.setSize(JLineSize.of(18, 6): Sized)
          harness.screen.narrow(18)
        val next    = frame(18, "NEW")
        assert(harness.backend.draw(next).isRight)
        val written = harness.replay()
        assert(harness.screen.line(0) == "HISTORY-A")
        assert(harness.screen.line(1) == "HISTORY-B")
        assert(!written.contains(AnsiSequences.ClearScreen), "an inline frame never owns the whole primary screen")
        assert(harness.screen.line(4) == "NEW")
        assert(harness.screen.line(5).isEmpty)
        assert(harness.backend.draw(next).isRight)
        assert(harness.replay().isEmpty, "the successful repaint must become the new baseline")
      }

  test("only alternate-screen ownership permits a whole-display erase on shrink"):
    for alternate <- Seq(false, true) do
      wired { harness =>
        if alternate then assert(harness.backend.enterAlternateScreen().isRight)
        assert(harness.backend.draw(frame(20, "OLD")).isRight)
        harness.sink.reset()
        assert(harness.backend.draw(frame(18, "NEW")).isRight)
        assert(harness.sink.toString(UTF_8).contains(AnsiSequences.ClearScreen) == alternate)
      }

  test("inline shrink erases at the current strip origin after a simultaneous height change"):
    wired { harness =>
      harness.start()
      assert(harness.backend.draw(frame(20, "OLD")).isRight)
      harness.sink.reset()
      harness.terminal.setSize(JLineSize.of(18, 8): Sized)
      val resizedScreen = Screen(18, 8)
      resizedScreen.feed("\u001b[5;1HKEEP ROW FIVE\u001b[6;1HKEEP ROW SIX\u001b[7;1HSTALE\u001b[8;1HSTALE")
      val next          = Buffer(Rect(0, 6, 18, 2))
      next.setString(0, 6, "NEW", Style.Default)
      assert(harness.backend.draw(next).isRight)
      val written       = harness.sink.toString(UTF_8)
      resizedScreen.feed(written)
      assert(!written.contains(AnsiSequences.ClearScreen))
      assert(resizedScreen.line(4) == "KEEP ROW FIVE")
      assert(resizedScreen.line(5) == "KEEP ROW SIX")
      assert(resizedScreen.line(6) == "NEW")
      assert(resizedScreen.line(7).isEmpty)
    }

  private def finalFrame: Buffer =
    val buffer = frame(20, "FINAL FRAME ROW A")
    buffer.setString(0, 5, "FINAL FRAME ROW B", Style.Default)
    buffer

  private def drawWithUpperCaret(harness: Wired): Unit =
    assert(harness.backend.draw(finalFrame).isRight)
    assert(harness.backend.setCursorPosition(Position(3, 4)).isRight)
    harness.replay()

  private def assertRetainedFrame(harness: Wired, prompt: String): Unit =
    harness.screen.feed(prompt)
    assert(harness.screen.line(3) == "FINAL FRAME ROW A")
    assert(harness.screen.line(4) == "FINAL FRAME ROW B")
    assert(harness.screen.line(5) == prompt)

  for raw <- Seq(false, true) do
    test(s"inline close parks below the retained frame after mode restoration (raw=$raw)"):
      wired { harness =>
        harness.start(raw)
        drawWithUpperCaret(harness)
        assert(harness.backend.close().isRight)
        val released = harness.replay()
        assertRetainedFrame(harness, "SHELL>")
        if raw then
          assert(released.indexOf(AnsiSequences.RestoreCursor) < released.lastIndexOf(AnsiSequences.moveTo(0, 5)))
        assert(harness.backend.close().isRight)
        assert(harness.replay().isEmpty, "a second close must not scroll the retained frame again")
      }

  test("inline close uses the terminal height at handover rather than the saved cursor row"):
    wired { harness =>
      harness.start()
      drawWithUpperCaret(harness)
      harness.terminal.setSize(JLineSize.of(20, 8): Sized)
      val resizedScreen = Screen(20, 8)
      resizedScreen.feed("\u001b[6;1H\u001b7\u001b[7;1HFINAL FRAME ROW A\u001b[8;1HFINAL FRAME ROW B")
      assert(harness.backend.setCursorPosition(Position(3, 6)).isRight)
      assert(harness.backend.close().isRight)
      resizedScreen.feed(harness.sink.toString(UTF_8))
      resizedScreen.feed("SHELL>")
      assert(resizedScreen.line(5) == "FINAL FRAME ROW A")
      assert(resizedScreen.line(6) == "FINAL FRAME ROW B")
      assert(resizedScreen.line(7) == "SHELL>")
    }

  test("inline suspend hands over below the frame and reacquires modes plus a full repaint"):
    wired { harness =>
      harness.start()
      assert(harness.backend.hideCursor().isRight)
      assert(harness.backend.setCursorShape(CursorShape.SteadyBar).isRight)
      drawWithUpperCaret(harness)
      assert(harness.backend.suspend {
        harness.replay()
        assert(!harness.backend.isRawMode)
        assert(!harness.backend.cursorHidden)
        assertRetainedFrame(harness, "CHILD>")
        42
      } == Right(42))
      assert(harness.backend.isRawMode)
      assert(harness.backend.cursorHidden)
      assert(harness.backend.cursorShape == CursorShape.SteadyBar)
      harness.replay()
      drawWithUpperCaret(harness)
      assert(harness.screen.line(4) == "FINAL FRAME ROW A")
      assert(harness.screen.line(5) == "FINAL FRAME ROW B")
      assert(harness.backend.close().isRight)
      harness.replay()
      assertRetainedFrame(harness, "SHELL>")
    }

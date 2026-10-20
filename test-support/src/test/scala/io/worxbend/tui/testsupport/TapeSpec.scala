package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, RunnerError, TerminalRunner}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.concurrent.duration.DurationInt

/** The VHS-subset tape: what [[Tape.parse]] accepts and rejects, and a whole tape driven through a `Pilot` into a
  * `.cast` recording and screenshots on disk.
  */
final class TapeSpec extends AnyFunSuite:

  test("parse reads every command of the subset, skipping comments and blank lines"):
    val script = """# a demo tape
                   |Set Width 40
                   |Set Height 6
                   |
                   |Type "hello \"world\""
                   |Enter
                   |Tab
                   |Key ctrl+s
                   |Sleep 500ms
                   |Sleep 1.5s
                   |Wait /sa+ved/
                   |Screenshot after-save
                   |""".stripMargin
    val tape   = Tape.parse(script).fold(problem => fail(problem), identity)
    assert(
      tape.commands == Seq(
        TapeCommand.SetWidth(40),
        TapeCommand.SetHeight(6),
        TapeCommand.Type("hello \"world\""),
        TapeCommand.Key("enter"),
        TapeCommand.Key("tab"),
        TapeCommand.Key("ctrl+s"),
        TapeCommand.Sleep(500.millis),
        TapeCommand.Sleep(1500.millis),
        TapeCommand.Wait("sa+ved"),
        TapeCommand.Screenshot("after-save"),
      )
    )

  test("parse names the line and the problem for anything outside the subset"):
    assert(Tape.parse("Type hello").left.exists(_.startsWith("line 1: Type takes a double-quoted string")))
    assert(Tape.parse("Enter\nHide").left.exists(_.startsWith("line 2: unknown command 'Hide'")))
    assert(Tape.parse("Sleep 3 years").left.exists(_.contains("Sleep")))
    assert(Tape.parse("Key ctrl+").left.exists(_.contains("bad key spec")))
    assert(Tape.parse("Screenshot a/b").left.exists(_.contains("Screenshot")))
    assert(Tape.parse("Set Width zero").left.exists(_.contains("Set Width")))
    assert(Tape.parse("Wait hi").left.exists(_.contains("/regex/")))

  /** An app that paints what has been typed so far on its first row and quits on Escape. */
  private def echo(backend: HeadlessBackend): Either[RunnerError, Unit] =
    var typed = ""
    TerminalRunner(backend).run(
      _ => (),
      {
        case (Event.Key(KeyEvent(KeyCode.Escape, _)), handle) =>
          handle.quit()
          EventOutcome.Ignored
        case (Event.Key(KeyEvent(KeyCode.Char(c), _)), _)     =>
          typed += Character.toString(c)
          EventOutcome.Redraw
        case _                                                => EventOutcome.Ignored
      },
      frame => frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, typed, Style.Default), frame.area),
    )

  test("a tape typing \"hi\", waiting for /hi/ and taking a screenshot writes the cast and the screenshot files"):
    val tape = Tape
      .parse("""Set Width 12
                            |Set Height 2
                            |Type "hi"
                            |Sleep 200ms
                            |Wait /hi/
                            |Screenshot hi
                            |""".stripMargin)
      .fold(problem => fail(problem), identity)
    withOutput("echo") { directory =>
      val result = Tape.run(tape, directory, "echo")(echo)
      assert(result.cast == directory.resolve("echo.cast"))
      assert(Files.exists(result.cast))
      val cast   = Files.readString(result.cast).split("\n").toSeq
      assert(cast.head.startsWith("""{"version":2,"width":12,"height":2,"""))
      assert(cast.tail.nonEmpty && cast.tail.forall(_.startsWith("[")))
      assert(result.screenshots.map(_.getFileName.toString) == Seq("hi.txt", "hi.svg"))
      assert(result.screenshots.forall(Files.exists(_)))
      assert(Files.readString(directory.resolve("hi.txt")).trim == "hi")
      assert(Files.readString(directory.resolve("hi.svg")).contains("<svg"))
      val frame  = result.frames.get("hi").getOrElse(fail("the screenshot's frame was not kept"))
      assert(frame.area.size == Size(12, 2))
      GoldenFrames.assertMatches("tape-hi", frame)
    }

  test("mid-tape width and height changes write resize events before their repaints"):
    val tape = Tape
      .parse("Set Width 12\nSet Height 2\nType \"hi\"\nSet Width 20\nSet Height 4\nScreenshot resized\n")
      .fold(problem => fail(problem), identity)
    withOutput("resize") { directory =>
      val result  = Tape.run(tape, directory, "resize")(echo)
      val events  = Files.readString(result.cast).split("\n").toVector.tail
      val resizes = events.zipWithIndex.filter(_._1.contains(",\"r\","))
      assert(resizes.map(_._1.dropWhile(_ != 'r')) == Vector("r\",\"20x2\"]", "r\",\"20x4\"]"))
      resizes.foreach { (_, index) =>
        assert(events(index + 1).contains(",\"o\","))
        assert(events(index + 1).contains("\\u001b[2J"))
      }
      assert(result.frames("resized").area.size == Size(20, 4))
    }

  /** A throwaway directory, or — when `GLYPHORA_TAPE_OUTPUT` is set, as `scripts/render-tapes.sh` sets it — a kept one
    * under it, so the artefacts this suite produces are the ones the script leaves behind.
    */
  private def withOutput(name: String)(body: java.nio.file.Path => Unit): Unit =
    Tape.outputDirectory match
      case Some(root) => body(root.resolve(name))
      case None       => TempWorkspace.withWorkspace("glyphora-tape")(body)

  test("a Wait that never matches fails naming the pattern, and still closes the app"):
    val tape = Tape.parse("Wait /never/\n").fold(problem => fail(problem), identity)
    TempWorkspace.withWorkspace("glyphora-tape") { directory =>
      val error = intercept[AssertionError](Tape.run(tape, directory, "never", waitTimeout = 300.millis)(echo))
      assert(error.getMessage.contains("/never/"))
    }

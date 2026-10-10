package io.worxbend.tui.terminal

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

final class KeyboardHandoverSpec extends AnyFunSuite:
  test("suspend restores requested key event reporting"):
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("keyboard-handover", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.enableKeyEventTypes().isRight)
      sink.reset()
      assert(backend.suspend(()).isRight)
      assert(sink.toString(UTF_8).contains(AnsiSequences.PushKittyKeyboardEvents))
    finally backend.close()

  for operation <- Seq("suspend", "printAbove", "insertBefore", "redress") do
    test(s"$operation preserves enhanced mode across repeated handovers with balanced keyboard stack"):
      val sink     = ByteArrayOutputStream()
      val terminal =
        LineDisciplineTerminal("keyboard-handover", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
      val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
      try
        assert(backend.enableRawMode().isRight)
        assert(backend.enableKeyEventTypes().isRight)
        for _ <- 0 until 2 do
          sink.reset()
          operation match
            case "suspend"      => assert(backend.suspend(()).isRight)
            case "printAbove"   => assert(backend.printAbove(Seq("message")).isRight)
            case "insertBefore" =>
              assert(backend.reserveInlineRows(2).isRight)
              assert(backend.insertBefore(1, (_, _) => ()).isRight)
            case _              =>
              val dressing = TerminalDressing(backend)
              val released = dressing.releaseTerminal()
              assert(released.failure.isEmpty)
              dressing.reacquireTerminal(released.state)
          val bytes = sink.toString(UTF_8)
          assert(bytes.contains(AnsiSequences.PushKittyKeyboardEvents))
          assert(backend.keyEventTypesActive)
          assert(bytes.sliding(AnsiSequences.PopKittyKeyboard.length).count(_ == AnsiSequences.PopKittyKeyboard) == 2)
        sink.reset()
        assert(backend.close().isRight)
        val bytes = sink.toString(UTF_8)
        assert(bytes.sliding(AnsiSequences.PopKittyKeyboard.length).count(_ == AnsiSequences.PopKittyKeyboard) == 1)
      finally backend.close()

  test("fresh raw mode does not inherit a previous session's enhanced request"):
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("keyboard-handover", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.enableKeyEventTypes().isRight)
      assert(backend.disableRawMode().isRight)
      assert(backend.enableRawMode().isRight)
      sink.reset()
      assert(backend.suspend(()).isRight)
      assert(!sink.toString(UTF_8).contains(AnsiSequences.PushKittyKeyboardEvents))
      assert(!backend.keyEventTypesActive)
    finally backend.close()

package io.worxbend.tui.terminal

import org.jline.terminal.{Size, Sized, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Kitty maintains two independent stacks: a push on main cannot dress alternate. */
final class KeyboardScreenSpec extends AnyFunSuite:
  private final class Screens:
    var alternate                   = false
    var main                        = List.empty[Int]
    var alt                         = List.empty[Int]
    def flags: Int                  = (if alternate then alt else main).headOption.getOrElse(0)
    def replay(bytes: String): Unit =
      val tokens = "\\u001b\\[\\?1049[hl]|\\u001b\\[>[0-9]+u|\\u001b\\[<u".r
      tokens.findAllIn(bytes).foreach {
        case "\u001b[?1049h" => alternate = true
        case "\u001b[?1049l" => alternate = false
        case "\u001b[<u"     => if alternate then alt = alt.drop(1) else main = main.drop(1)
        case push            =>
          val flags = push.drop(3).dropRight(1).toInt
          if alternate then alt = flags :: alt else main = flags :: main
      }

  for fullscreen <- Seq(false, true); enhanced <- Seq(false, true) do
    test(s"fullscreen=$fullscreen enhanced=$enhanced owns only the current screen's kitty stack"):
      val sink           = ByteArrayOutputStream()
      val terminal       =
        LineDisciplineTerminal("keyboard-screen", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
      terminal.setSize(Size.of(20, 4): Sized)
      val backend        =
        JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, knownCapabilities = Some(TerminalCapabilities.unknown))
      val model          = Screens()
      def replay(): Unit =
        model.replay(sink.toString(UTF_8))
        sink.reset()
      try
        assert(backend.enableRawMode().isRight)
        if fullscreen then assert(backend.enterAlternateScreen().isRight)
        if enhanced then assert(backend.enableKeyEventTypes().isRight)
        replay()
        assert(model.flags == (if enhanced then 3 else 1))
        if fullscreen then assert(model.main.isEmpty)
        for operation <- Seq("suspend", "printAbove", "insertBefore") do
          operation match
            case "suspend"    =>
              assert(backend.suspend {
                replay()
                assert(!model.alternate)
                assert(model.main.isEmpty && model.alt.isEmpty)
              }.isRight)
            case "printAbove" => assert(backend.printAbove(Seq("message")).isRight)
            case _            => assert(backend.insertBefore(1, (_, _) => ()).isRight)
          replay()
          assert(model.alternate == fullscreen)
          assert(model.flags == (if enhanced then 3 else 1))
          if fullscreen then assert(model.main.isEmpty)
        assert(backend.close().isRight)
        replay()
        assert(!model.alternate)
        assert(model.main.isEmpty && model.alt.isEmpty)
      finally backend.close()

  test("blind RestoreAll leaves inherited keyboard stacks alone while resetting the screen"):
    val model = Screens()
    model.replay("\u001b[>9u\u001b[?1049h\u001b[>3u")
    model.replay(AnsiSequences.RestoreAll)
    assert(!model.alternate)
    assert(model.main == List(9) && model.alt == List(3))
    assert(!AnsiSequences.RestoreAll.contains(AnsiSequences.PopKittyKeyboard))

  test("pointer reset uses the empty OSC22 form on release and emergency restoration"):
    assert(AnsiSequences.ResetPointerShape == "\u001b]22;\u001b\\")
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("pointer-reset", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.setPointerShape(PointerShape.Text).isRight)
      sink.reset()
      assert(backend.suspend(()).isRight)
      assert(sink.toString(UTF_8).contains("\u001b]22;\u001b\\"))
      assert(AnsiSequences.RestoreAll.contains("\u001b]22;\u001b\\"))
    finally backend.close()

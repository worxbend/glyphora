package io.worxbend.tui.terminal

import org.jline.terminal.{Attributes, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.{FileDescriptor, FileOutputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A child JVM observes the real emergencyRestore stdout descriptor, not a reconstructed fallback constant. */
final class EmergencyKeyboardSpec extends AnyFunSuite:
  for fullscreen <- Seq(false, true); enhanced <- Seq(false, true); state <- Seq("closed", "active", "failed-close") do
    test(s"emergency restore preserves inherited kitty flags: fullscreen=$fullscreen enhanced=$enhanced state=$state"):
      val java  = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val child = ProcessBuilder(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        "io.worxbend.tui.terminal.EmergencyKeyboardProbe",
        fullscreen.toString,
        enhanced.toString,
        state,
      ).start()
      try
        assert(child.waitFor(20, TimeUnit.SECONDS), "emergency restore probe timed out")
        val bytes     = new String(child.getInputStream.readAllBytes(), UTF_8)
        val errors    = new String(child.getErrorStream.readAllBytes(), UTF_8)
        assert(child.exitValue() == 0, errors)
        var alternate = false
        var main      = List.empty[Int]
        var alt       = List.empty[Int]
        val tokens    = "\\u001b\\[\\?1049[hl]|\\u001b\\[>[0-9]+u|\\u001b\\[<u".r
        tokens.findAllIn(bytes).foreach {
          case "\u001b[?1049h" => alternate = true
          case "\u001b[?1049l" => alternate = false
          case "\u001b[<u"     => if alternate then alt = alt.drop(1) else main = main.drop(1)
          case push            =>
            val flags = push.drop(3).dropRight(1).toInt
            if alternate then alt = flags :: alt else main = flags :: main
        }
        assert(!alternate)
        assert(alt.isEmpty, s"application leaked alternate-screen flags: $alt")
        assert(main == List(9), s"emergency restore consumed inherited main-screen flags: $main")
      finally if child.isAlive then { val _ = child.destroyForcibly() }

object EmergencyKeyboardProbe:
  def main(args: Array[String]): Unit =
    require(args.length == 3)
    val fullscreen = args(0).toBoolean
    val enhanced   = args(1).toBoolean
    val state      = args(2)
    val stdout     = FileOutputStream(FileDescriptor.out)
    stdout.write("\u001b[>9u".getBytes(UTF_8)) // enclosing shell's independently owned keyboard mode
    stdout.flush()
    var failing            = false
    var attributesRestored = false
    val device             = new OutputStream:
      override def write(value: Int): Unit =
        if failing then throw new IOException("normal writer disconnected")
        stdout.write(value)
    val terminal           = new LineDisciplineTerminal(
      "emergency-keyboard",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    ):
      override def setAttributes(attributes: Attributes): Unit =
        super.setAttributes(attributes)
        if failing && attributes.getLocalFlag(Attributes.LocalFlag.ICANON) then attributesRestored = true
    val backend            =
      JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, knownCapabilities = Some(TerminalCapabilities.unknown))
    assert(backend.enableRawMode().isRight)
    if fullscreen then assert(backend.enterAlternateScreen().isRight)
    if enhanced then assert(backend.enableKeyEventTypes().isRight)
    assert(backend.setCursorShape(CursorShape.SteadyBar).isRight)
    assert(backend.setCursorBlink(false).isRight)
    assert(backend.hideCursor().isRight)
    state match
      case "closed"       => assert(backend.close().isRight)
      case "failed-close" =>
        failing = true
        assert(backend.close().isLeft)
        assert(attributesRestored)
        assert(backend.cursorBlinkSuppressed)
        interceptClosed(terminal)
      case _              => ()
    backend.emergencyRestore()
    backend.emergencyRestore() // must not consume the inherited push on the second call either
    terminal.close()

  private def interceptClosed(terminal: Terminal): Unit =
    val closed = try
      terminal.writer()
      false
    catch case _: IllegalStateException => true
    assert(closed, "failed restoration must still close JLine's handle")

package io.worxbend.tui.terminal

import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.terminal.impl.AbstractUnixSysTerminal

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt

/** Child-process protocol for NativeInputHandoverSpec; stdout belongs to its controlling PTY, not ScalaTest. */
object NativeInputHandoverProbe:
  def main(args: Array[String]): Unit =
    require(args.isEmpty)
    System.setProperty(TerminalBuilder.PROP_SOFTWARE_SIGNALS, "true")
    val terminal = TerminalBuilder
      .builder()
      .system(true)
      .provider("jni")
      .signalHandler(Terminal.SignalHandler.SIG_IGN)
      .encoding(UTF_8)
      .build()
    assert(terminal match
      case _: AbstractUnixSysTerminal => true
      case _                          => false)
    val backend  =
      JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, knownCapabilities = Some(TerminalCapabilities.unknown))
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.enterAlternateScreen().isRight)
      for index <- 0 until 20 do
        assert(backend.readEvent(5.millis) == Right(None))
        val handover = backend.suspend {
          val source =
            s"import os,tty; tty.setraw(0); os.write(1,b'EDITOR_READY:$index\\n'); b=os.read(0,1); assert b==b'X',repr(b)"
          val editor = ProcessBuilder("python3", "-c", source).inheritIO().start()
          try
            assert(editor.waitFor(10, TimeUnit.SECONDS), s"editor $index lost its first byte")
            assert(editor.exitValue() == 0)
          finally if editor.isAlive then { val _ = editor.destroyForcibly() }
        }
        assert(handover.isRight, handover.toString)
      assert(backend.close().isRight)
      println("PTY_HANDOVER_OK cycles=20")
    finally backend.close()

package io.worxbend.tui.terminal

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.io.Source

/** Runs only on POSIX hosts with Python's standard-library pty module; no emulator is required. */
final class NativeInputHandoverSpec extends AnyFunSuite:
  test("a JNI system terminal hands every first byte to a subprocess after a timed-out input poll"):
    assume(!System.getProperty("os.name").startsWith("Windows"))
    val resource = Source.fromResource("input-handover.py", getClass.getClassLoader)
    val script   = try resource.mkString
    finally resource.close()
    val java     = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val child    = ProcessBuilder("python3", "-c", script, java, System.getProperty("java.class.path"))
      .redirectErrorStream(true)
      .start()
    try
      assert(child.waitFor(90, TimeUnit.SECONDS), "PTY handover probe timed out")
      val output = new String(child.getInputStream.readAllBytes(), UTF_8)
      assert(child.exitValue() == 0, output)
      assert(output.contains("PTY_HANDOVER_OK cycles=20"), output)
      info(output.trim)
    finally if child.isAlive then { val _ = child.destroyForcibly() }

package io.worxbend.tui.terminal

import java.util.concurrent.TimeUnit

/** PTY-only diagnostic entry point; not a CI test. Invoke with a warm-poll flag and a scratch result path. */
object EditorHandoverProbe:
  def main(args: Array[String]): Unit =
    val backend = JLine3Backend.create(knownCapabilities = Some(TerminalCapabilities.unknown)).toOption.get
    try
      assert(backend.enableRawMode().isRight)
      if args(0) == "warm" then
        val _ = backend.readEvent(scala.concurrent.duration.Duration(20, "millis"))
      println(s"HANDOVER terminal=${backend.terminal.getClass.getName} canPause=${backend.terminal.canPauseResume()}")
      val result = backend.suspend {
        val script =
          "stty -echo -icanon min 1 time 0 < /dev/tty; printf 'HANDOVER_READY\\n' > /dev/tty; dd if=/dev/tty bs=1 count=1 status=none > \"$1\""
        val child  = ProcessBuilder("sh", "-c", script, "sh", args(1)).inheritIO().start()
        val exited = child.waitFor(5, TimeUnit.SECONDS)
        if !exited then child.destroyForcibly()
        exited
      }
      val replay = backend.readEvent(scala.concurrent.duration.Duration(20, "millis"))
      println(s"HANDOVER result=$result replay=$replay")
    finally
      val _ = backend.close()

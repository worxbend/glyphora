package io.worxbend.tui.examples.procmon

import org.scalatest.funsuite.AnyFunSuite

final class PsProcessSourceSpec extends AnyFunSuite:
  test("real ps output parses this JVM's PID, user and numeric columns"):
    val source = PsProcessSource()
    assert(source.name == "ps")
    val rows   = source.sample()
    assert(rows.nonEmpty)
    val self   = rows.find(_.pid.toLong == ProcessHandle.current().pid()).get
    assert(self.user.nonEmpty)
    assert(self.command.nonEmpty)
    assert(self.cpuPercent >= 0.0)
    assert(self.memPercent >= 0.0)

  test("process output keeps spaced commands and rejects malformed numeric rows"):
    val process = ProcessBuilder(
      "sh",
      "-c",
      "printf '%s\\n' ' 1842 alice 128.4 1.9 command with spaces' 'bad root 2 3 x' '1 root nope 3 x' '2 root 2 nope x' 'short row'",
    ).start()
    assert(
      PsProcessSource.read(process, 2L, java.util.concurrent.TimeUnit.SECONDS) ==
        Vector(ProcessInfo(1842, "alice", 128.4, 1.9, "command with spaces"))
    )

  test("a process that closes stdout but remains alive is forcibly stopped at the timeout"):
    val process = ProcessBuilder("sh", "-c", "exec 1>&-; read answer").start()
    try
      assert(PsProcessSource.read(process, 0L, java.util.concurrent.TimeUnit.NANOSECONDS).isEmpty)
      assert(process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS))
      assert(!process.isAlive)
    finally { val _ = process.destroyForcibly() }

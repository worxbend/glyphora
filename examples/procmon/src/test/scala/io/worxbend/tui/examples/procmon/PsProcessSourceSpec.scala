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

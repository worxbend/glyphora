package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

/** FileDescriptor retains every attached FileOutputStream, even when its wrapper is otherwise unreachable. */
final class ConsoleDescriptorSpec extends AnyFunSuite:
  test("repeated Console.write calls retain one descriptor wrapper, not one per call"):
    Console.write("") // initialize before measuring
    val runtime          = Runtime.getRuntime
    def retained(): Long =
      System.gc()
      runtime.totalMemory() - runtime.freeMemory()
    val before           = retained()
    for _ <- 0 until 1000000 do Console.write("")
    val delta            = retained() - before
    info(s"one million Console.write calls retained $delta bytes")
    assert(delta < 16L * 1024 * 1024, s"one million empty writes retained $delta bytes")

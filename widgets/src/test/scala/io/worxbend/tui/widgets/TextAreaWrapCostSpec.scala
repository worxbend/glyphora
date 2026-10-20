package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}
import org.scalatest.funsuite.AnyFunSuite
import java.lang.management.ManagementFactory

final class TextAreaWrapCostSpec extends AnyFunSuite:
  test("steady wrapped edits and paints allocate for the viewport, not every document line"):
    val state  = TextAreaState(Vector.fill(50000)("line").mkString("\n"))
    val area   = Rect(0, 0, 40, 10)
    val buffer = Buffer(area)
    val widget = TextArea(options = TextAreaOptions(wrap = true))
    widget.render(area, buffer, state)
    val bean   = ManagementFactory.getThreadMXBean match
      case counters: com.sun.management.ThreadMXBean => counters
      case _                                         => fail("the pinned JDK must expose allocation counters")
    val thread = Thread.currentThread().threadId()
    for line <- Seq(0, 25000, 49999) do
      state.moveTo(TextPosition(line, 0))
      widget.render(area, buffer, state)
      val before    = bean.getThreadAllocatedBytes(thread)
      for _ <- 1 to 5 do
        state.insert("x")
        widget.render(area, buffer, state)
      val allocated = bean.getThreadAllocatedBytes(thread) - before
      info(s"five edits/renders at line $line allocated $allocated bytes")
      assert(allocated < 1500000L, "viewport editing allocated whole-document layout/splice objects")
    assert(state.lineCount == 50000)
    assert(state.lineText(0) == "xxxxxline")
    assert(state.lineText(25000) == "xxxxxline")
    assert(state.lineText(49999) == "xxxxxline")

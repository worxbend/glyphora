package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.widgets.ListState

import org.scalatest.funsuite.AnyFunSuite

final class SelectionListRebuildSpec extends AnyFunSuite:

  test("rebuilding a selection list reads only viewport rows, including after membership changes"):
    var reads                             = 0
    val items                             = new IndexedSeq[String]:
      def length: Int               = 100000
      def apply(index: Int): String =
        reads += 1
        s"item $index"
    val state                             = ListState(selected = Some(99990))
    val area                              = Rect(0, 0, 30, 10)
    def frame(selected: Set[Int]): Buffer =
      val buffer = Buffer(area)
      SelectionListElement(items, selected, _ => (), state).widget.render(area, buffer)
      buffer
    frame(Set.empty)
    reads = 0
    frame(Set.empty)
    info(s"rebuilt selectionList viewport reads: $reads")
    assert(reads <= 20)
    reads = 0
    val changed                           = frame(Set(99990))
    assert(reads <= 20)
    assert(
      (0 until 10).exists(y => (0 until 30).map(x => changed.get(x, y).symbol).mkString.contains("[x] item 99990"))
    )
    val replaced                          = Vector("replacement")
    val buffer                            = Buffer(area)
    SelectionListElement(replaced, Set(0), _ => (), state).widget.render(area, buffer)
    assert((0 until 30).map(x => buffer.get(x, 0).symbol).mkString.contains("[x] replacement"))

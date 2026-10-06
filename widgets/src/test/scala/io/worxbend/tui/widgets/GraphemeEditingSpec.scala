package io.worxbend.tui.widgets

import io.worxbend.tui.core.CharWidth

import org.scalatest.funsuite.AnyFunSuite

final class GraphemeEditingSpec extends AnyFunSuite:

  test("incremental input construction stores complete graphemes with right-affine cursors") {
    Seq(("e", "\u0301"), ("👍", "🏽"), ("👩", "\u200d💻"), ("🇺", "🇸")).foreach { (base, suffix) =>
      val input = TextInputState(base)
      input.insert(suffix)
      assert(input.clusterSeq == CharWidth.graphemeClusters(input.value).toVector)
      assert(input.cursor == input.clusterSeq.size)
      input.backspace()
      assert(input.value.isEmpty)

      val area = TextAreaState(base)
      area.insert(suffix)
      assert(area.clusterLines.head == CharWidth.graphemeClusters(area.value).toVector)
      assert(area.cursor == (0, area.clusterLines.head.size))
      area.backspace()
      assert(area.value.isEmpty)
    }
  }

  test("insertion joining the following text snaps right of the resulting grapheme") {
    val input = TextInputState("\u0301x")
    input.moveHome()
    input.insert("e")
    assert(input.clusterSeq == Vector("e\u0301", "x"))
    assert(input.cursor == 1)
    val area  = TextAreaState("\u0301x")
    area.moveHome()
    area.insert("e")
    assert(area.clusterLines.head == input.clusterSeq)
    assert(area.cursor == (0, 1))
  }

  test("deletion resegments regional indicators with right affinity at the splice") {
    Seq(false, true).foreach { backspace =>
      val input = TextInputState("🇺x🇸")
      input.moveHome()
      input.moveRight()
      if backspace then
        input.moveRight()
        input.backspace()
      else input.delete()
      assert(input.clusterSeq == Vector("🇺🇸"))
      assert(input.cursor == 1)
      val area  = TextAreaState("🇺x🇸")
      area.moveHome()
      area.moveRight()
      if backspace then
        area.moveRight()
        area.backspace()
      else area.delete()
      assert(area.clusterLines.head == input.clusterSeq)
      assert(area.cursor == (0, 1))
      area.undo()
      assert(area.value == "🇺x🇸")
      area.redo()
      assert(area.clusterLines.head == Vector("🇺🇸"))
      assert(area.cursor == (0, 1))
    }
  }

  test("both line-join directions resegment and snap right of the merged cluster") {
    val backward = TextAreaState("e\n\u0301x")
    backward.moveHome()
    backward.backspace()
    assert(backward.clusterLines == Vector(Vector("e\u0301", "x")))
    assert(backward.cursor == (0, 1))
    val forward  = TextAreaState("e\n\u0301x")
    forward.moveUp()
    forward.moveEnd()
    forward.delete()
    assert(forward.clusterLines == backward.clusterLines)
    assert(forward.cursor == backward.cursor)
  }

  test("multiline insertion resegments the first and last splice boundaries") {
    val area = TextAreaState("e\u0301x")
    area.moveHome()
    area.moveRight()
    area.insert("\u0301\ne\u0301\ne")
    assert(area.clusterLines == Vector(Vector("e\u0301\u0301"), Vector("e\u0301"), Vector("e", "x")))
    assert(area.cursor == (2, 1))
  }

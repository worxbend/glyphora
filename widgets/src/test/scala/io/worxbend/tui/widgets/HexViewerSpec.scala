package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Modifiers, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8

final class HexViewerSpec extends AnyFunSuite:

  private def bytes(text: String): IndexedSeq[Byte] = text.getBytes(UTF_8).toIndexedSeq

  test("rows read offset, two groups of eight bytes, then the printable ASCII"):
    val viewer = HexViewer(bytes("Hello, World!\nThis is glyphora."))
    assert(
      trimmedLines(rendered(viewer, HexViewerState(), 78, 3)) == Seq(
        "00000000  48 65 6c 6c 6f 2c 20 57  6f 72 6c 64 21 0a 54 68  |Hello, World!.Th|",
        "00000010  69 73 20 69 73 20 67 6c  79 70 68 6f 72 61 2e     |is is glyphora.|",
        "",
      )
    )

  test("bytes outside printable ASCII, a multi-byte UTF-8 character included, show as dots"):
    val viewer = HexViewer(bytes("é→") :+ 0.toByte :+ 0x7f.toByte)
    assert(
      trimmedLines(rendered(viewer, HexViewerState(), 78, 1)) == Seq(
        "00000000  c3 a9 e2 86 92 00 7f                              |.......|"
      )
    )

  test("the selected byte is highlighted in both the hex and the ASCII column"):
    val state  = HexViewerState()
    state.select(9, 32)
    val buffer = rendered(HexViewer(bytes("0123456789abcdef0123456789abcdef")), state, 78, 2)
    // byte 9 is the second of the second group: hex at x = 10 + 9 * 3 + 1, ASCII at x = 61 + 9
    assert(buffer.get(38, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(39, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(70, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(35, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("selection moves clamp to the data and the view scrolls to keep the selection visible"):
    val data  = IndexedSeq.fill(100)(0x41.toByte)
    val state = HexViewerState()
    state.moveBy(-5, data.size)
    assert(state.selected.contains(0))
    state.moveBy(1000, data.size)
    assert(state.selected.contains(99))
    val shown = trimmedLines(rendered(HexViewer(data), state, 78, 2))
    assert(shown.head.startsWith("00000050"))
    assert(shown(1).startsWith("00000060"))
    assert(state.offset == 5)
    state.moveBy(-16, data.size)
    assert(state.selected.contains(83))

  test("the viewer measures its rows and its full width"):
    val viewer = HexViewer(IndexedSeq.fill(33)(0.toByte))
    assert(viewer.heightAt(78).contains(3))
    assert(viewer.widthAt(3).contains(78))
    assert(HexViewer(IndexedSeq.empty).heightAt(78).contains(0))
    assert(HexViewer(IndexedSeq.fill(8)(0.toByte), bytesPerRow = 8).widthAt(1).contains(46))

  test("a narrow area clips each row and writes nothing outside it"):
    assert(lines(rendered(HexViewer(bytes("Hi")), HexViewerState(), 14, 1)) == Seq("00000000  48 6"))
    val buffer   = Buffer(Rect(0, 0, 20, 4))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(2, 1, 9, 2)
    HexViewer(IndexedSeq.fill(64)(1.toByte)).render(area, buffer, HexViewerState())
    for
      y <- 0 until 4
      x <- 0 until 20
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel)

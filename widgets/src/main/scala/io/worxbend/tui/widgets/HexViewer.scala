package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Measured, Rect, StatefulWidget, Style}

import java.util.Locale

/** Caller-owned state of a [[HexViewer]]: the selected byte and the first row on screen.
  *
  * The selection is a byte offset into the data; the scroll offset is a row index the viewer re-derives on every frame
  * so the selection stays visible, the way a [[ListView]]'s does. Render-thread-only, and mutating it does not by
  * itself schedule a frame; the DSL's `hexViewer` node mutates it from its key handler, which repaints.
  */
final class HexViewerState:

  /** The selected byte's offset, or `None` before anything is selected. */
  var selected: Option[Int] = None

  /** The first row drawn; written back by the viewer on each frame. */
  var offset: Int = 0

  /** Selects the byte at `index`, clamped to `0 until size`; an empty buffer clears the selection. */
  def select(index: Int, size: Int): Unit =
    selected = Option.when(size > 0)(math.max(0, math.min(size - 1, index)))

  /** Moves the selection `delta` bytes — a row is `bytesPerRow` of them — clamped to the data. With nothing selected
    * yet, the first move selects the first byte.
    */
  def moveBy(delta: Int, size: Int): Unit =
    select(selected.fold(0)(current => (current.toLong + delta).max(Int.MinValue).min(Int.MaxValue).toInt), size)

/** A classic hex dump — `00000010  48 65 6c 6c 6f 20 57 6f  72 6c 64 0a 00 00 00 00  |Hello World.....|` — over a byte
  * sequence: the row's offset in hex, the bytes in two groups, and the printable ASCII of the same bytes (anything
  * outside `0x20..0x7e` as `.`), with the selected byte highlighted in both columns.
  *
  * The layout is fixed-width and pure ASCII, so it is the same at every terminal width: the viewer measures exactly one
  * row per `bytesPerRow` bytes and a full row's width ([[Measured]]), and a narrower area clips each row on the right.
  * Only the rows on screen are formatted, so a large buffer costs its viewport, not its size.
  *
  * @param bytes
  *   the data; indexed, so a row is read without walking from the start. `ArraySeq.unsafeWrapArray(array)` views an
  *   `Array[Byte]` without copying it
  * @param bytesPerRow
  *   bytes on one row, at least 1; the hex column splits them into two halves with an extra blank between
  * @param style
  *   the bytes and the ASCII column
  * @param offsetStyle
  *   the offset column
  * @param highlightStyle
  *   layered over the selected byte, in both columns
  */
final case class HexViewer(
    bytes: IndexedSeq[Byte],
    bytesPerRow: Int = 16,
    style: Style = Style.Default,
    offsetStyle: Style = Style.Default.dim,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[HexViewerState]
    with Measured:

  private val perRow    = math.max(1, bytesPerRow)
  private val half      = (perRow + 1) / 2
  private val rowCount  = (bytes.size + perRow - 1) / perRow
  private val hexStart  = HexViewer.OffsetDigits + 2
  private val hexWidth  = perRow * 3 + (if perRow > half then 1 else 0)
  private val textStart = hexStart + hexWidth + 1

  override def heightAt(width: Int): Option[Int] = Some(rowCount)
  override def widthAt(height: Int): Option[Int] = Some(textStart + perRow + 2)

  def render(area: Rect, buffer: Buffer, state: HexViewerState): Unit =
    if state.selected.exists(_ >= bytes.size) then state.select(bytes.size - 1, bytes.size)
    state.offset = ScrollWindow.offsetFor(state.offset, state.selected.map(_ / perRow), rowCount, area.height)
    if !area.isEmpty then
      (0 until math.min(area.height, rowCount - state.offset)).foreach { line =>
        drawRow(area, buffer, state.offset + line, area.y + line, state.selected)
      }

  private def drawRow(area: Rect, buffer: Buffer, row: Int, y: Int, selected: Option[Int]): Unit =
    val first                                                  = row * perRow
    val count                                                  = math.min(perRow, bytes.size - first)
    def put(column: Int, text: String, cellStyle: Style): Unit =
      val room = area.width - column
      if room > 0 then buffer.setString(area.x + column, y, text, cellStyle, room)
    put(0, String.format(Locale.ROOT, "%08x", first), offsetStyle)
    (0 until count).foreach { index =>
      val byte      = bytes(first + index) & 0xff
      val cellStyle = if selected.contains(first + index) then style.patch(highlightStyle) else style
      put(hexColumn(index), String.format(Locale.ROOT, "%02x", byte), cellStyle)
      put(textStart + 1 + index, HexViewer.printable(byte), cellStyle)
    }
    put(textStart, "|", style)
    put(textStart + 1 + count, "|", style)

  /** The column of the `index`th byte of a row's hex group: three columns a byte, plus one between the halves. */
  private def hexColumn(index: Int): Int = hexStart + index * 3 + (if index >= half then 1 else 0)

object HexViewer:

  /** Hex digits in the offset column — eight, which addresses 4 GiB. */
  private val OffsetDigits = 8

  /** The byte as an ASCII character when it is printable, otherwise `.`. */
  private def printable(byte: Int): String = if byte >= 0x20 && byte < 0x7f then byte.toChar.toString else "."

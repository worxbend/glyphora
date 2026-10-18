package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class CellFilterSpec extends AnyFunSuite:

  private val red  = Style.Default.withFg(Color.Red)
  private val blue = Style.Default.withFg(Color.Blue)

  // "ab" in red on row 0, "cd" in blue on row 1, the rest of the 6x2 grid blank but on a green background
  private def mixedBuffer(): Buffer =
    val buffer = Buffer(Rect(0, 0, 6, 2))
    buffer.fill(buffer.area, Cell(" ", Style.Default.withBg(Color.Green)))
    buffer.setString(0, 0, "ab", red)
    buffer.setString(0, 1, "cd", blue)
    buffer

  private def paintAll(color: Color): Effect =
    new Effect:
      def duration: scala.concurrent.duration.Duration                                                 = 1.second
      def process(elapsed: scala.concurrent.duration.FiniteDuration, buffer: Buffer, area: Rect): Unit =
        area.foreachPosition((x, y) => buffer.set(x, y, Cell("#", Style.Default.withFg(color))))

  test("filtered(CellFilter.text) leaves blank cells untouched"):
    val before  = mixedBuffer()
    val after   = mixedBuffer()
    paintAll(Color.Yellow).filtered(CellFilter.text).process(0.millis, after, after.area)
    val changed = after.area.positions.filter(p => after.get(p) != before.get(p)).toSet
    assert(changed == Set(Position(0, 0), Position(1, 0), Position(0, 1), Position(1, 1)))

  test("fg and bg match on the rendered colour"):
    val after  = mixedBuffer()
    Effect.filtered(CellFilter.fg(Color.Red))(paintAll(Color.Yellow)).process(0.millis, after, after.area)
    assert(after.get(0, 0).symbol == "#" && after.get(1, 0).symbol == "#")
    assert(after.get(0, 1).symbol == "c")
    val bgOnly = mixedBuffer()
    paintAll(Color.Yellow).filtered(CellFilter.bg(Color.Green)).process(0.millis, bgOnly, bgOnly.area)
    assert(bgOnly.get(0, 0).symbol == "a") // "ab" was written over the green fill with no background of its own
    assert(bgOnly.get(5, 1).symbol == "#")

  test("inner and outer split the area at a margin"):
    val area  = Rect(0, 0, 6, 4)
    val inner = Buffer(area)
    paintAll(Color.Yellow).filtered(CellFilter.inner(1)).process(0.millis, inner, area)
    assert(inner.get(0, 0).isBlank && inner.get(5, 3).isBlank)
    assert(inner.get(1, 1).symbol == "#" && inner.get(4, 2).symbol == "#")
    val outer = Buffer(area)
    paintAll(Color.Yellow).filtered(CellFilter.outer(1)).process(0.millis, outer, area)
    assert(outer.get(0, 0).symbol == "#" && outer.get(5, 3).symbol == "#")
    assert(outer.get(1, 1).isBlank && outer.get(4, 2).isBlank)

  test("and, or, not and where compose"):
    val buffer = mixedBuffer()
    val filter = CellFilter.text.and(CellFilter.fg(Color.Blue).not).or(CellFilter.where(_.symbol == "d"))
    paintAll(Color.Yellow).filtered(filter).process(0.millis, buffer, buffer.area)
    assert(buffer.get(0, 0).symbol == "#") // text and not blue
    assert(buffer.get(0, 1).symbol == "c") // blue
    assert(buffer.get(1, 1).symbol == "#") // where matched
    assert(buffer.get(3, 0).isBlank)       // blank, so text failed
    assert(CellFilter.all.matches(Cell.Empty, 0, 0, Rect(0, 0, 1, 1)))

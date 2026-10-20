package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, GridLayout, GridPlacement, Measured, Rect, Style, Widget}
import org.scalatest.funsuite.AnyFunSuite

final class LayoutRegressionSpec extends AnyFunSuite:
  test("LAYOUT-5 rendering and build-window selection repair populated to shorter and empty data"):
    val state                  = LazyListState(Some(50), 40)
    val buffer                 = Buffer(Rect(0, 0, 20, 5))
    def draw(count: Int): Unit = LazyListView(count, 1, _ => None).render(buffer.area, buffer, state)
    draw(100)
    draw(10)
    assert(state.selected.contains(9))
    assert(state.offset == 5)
    draw(0)
    assert(state.selected.isEmpty)
    assert(state.offset == 0)
    val version                = state.version
    draw(0)
    assert(state.version == version)
    state.selected = Some(50)
    assert(state.buildWindow(10, 5, 1).contains(9))
    assert(state.selected.contains(9))
    assert(state.buildWindow(0, 5, 1).isEmpty)
    assert(state.selected.isEmpty && state.offset == 0)

  private final class Aspect extends Widget with Measured:
    var painted                                    = Rect.Zero
    def render(area: Rect, buffer: Buffer): Unit   =
      painted = area
      buffer.setString(area.x, area.y, "A", Style.Default)
    override def widthAt(height: Int): Option[Int] = Some(height * 2)
    override def heightAt(width: Int): Option[Int] = Some(math.max(1, width / 2))

  test("LAYOUT-7 Auto grid columns measure at the allocated row height including spanning rows"):
    for span <- Seq(1, 2) do
      val aspect = Aspect()
      val grid   = Grid(
        GridLayout(Seq(Constraint.Auto, Constraint.fill), Seq.fill(span)(Constraint.fill)),
        Seq(GridEntry(GridPlacement(0, 0, rowSpan = span), aspect)),
      )
      val buffer = Buffer(Rect(0, 0, 20, 6))
      grid.render(buffer.area, buffer)
      assert(aspect.painted == Rect(0, 0, 12, 6))

  test("LAYOUT-7 Row height measurement refines Auto widths at the measured height"):
    val aspect   = Aspect()
    val tall     = Paragraph(io.worxbend.tui.core.Text.raw("a\nb\nc\nd"))
    val wrapping = new Widget with Measured:
      def render(area: Rect, buffer: Buffer): Unit   = buffer.setString(area.x, area.y, "W", Style.Default)
      override def heightAt(width: Int): Option[Int] = Some(if width >= 8 then 2 else 5)
    val row      = Row(
      Seq(
        LayoutItem(Constraint.Auto, aspect),
        LayoutItem(Constraint.Length(1), tall),
        LayoutItem(Constraint.fill, wrapping),
      )
    )
    assert(row.heightAt(13).contains(5))
    val buffer   = Buffer(Rect(0, 0, 13, 5))
    row.render(buffer.area, buffer)
    assert(aspect.painted.width == 10)

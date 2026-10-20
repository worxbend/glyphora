package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Position, Rect, Style, Text, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class DialogSpec extends AnyFunSuite:

  test("published box and button geometry agrees with painting at a nonzero origin, including wide labels"):
    val dialog = Dialog("T", Text.raw("hi"), Seq("界", "NO"))
    val area   = Rect(3, 2, 30, 9)
    val buffer = Buffer(Rect(0, 0, 40, 15))
    dialog.render(area, buffer)
    val box    = dialog.boxIn(area)
    assert(box == Rect(8, 4, 20, 5))
    assert(dialog.buttonAt(Position(11, 7), area).contains(0))
    assert(dialog.buttonAt(Position(16, 7), area).contains(0))
    assert(dialog.buttonAt(Position(17, 7), area).isEmpty, "gap between buttons is not a button")
    assert(dialog.buttonAt(Position(18, 7), area).contains(1))
    assert(buffer.get(11, 7).symbol == "[")
    assert(buffer.get(18, 7).symbol == "[")
    assert(dialog.buttonAt(Position(box.x, box.y), area).isEmpty)
    assert(dialog.buttonAt(Position(0, 0), area).isEmpty)
    assert(dialog.boxIn(Rect(0, 0, 3, 3)).isEmpty)
    assert(dialog.buttonAt(Position(1, 1), Rect(0, 0, 3, 3)).isEmpty)

  test("a dialog paints a cleared, bordered box over existing content"):
    val underlying: Widget =
      (area, buffer) => (0 until area.height).foreach(y => buffer.setString(0, y, "#" * area.width, Style.Default))
    val dialog             = Dialog("Confirm", Text.raw("Delete file?"), Seq("Yes", "No"), selectedButton = 1)
    val combined: Widget   = (area, buffer) =>
      underlying.render(area, buffer)
      dialog.render(area, buffer)
    val lines              = trimmedLines(rendered(combined, 30, 9))
    assert(lines.exists(_.contains("╔Confirm")))
    assert(lines.exists(_.contains("Delete file?")))
    assert(lines.exists(_.contains("[ Yes ] [ No ]")))
    // the dialog interior is cleared: no '#' survives between its side borders on the message row
    val messageRow         = lines.find(_.contains("Delete file?")).getOrElse(fail("no message row"))
    val interior           = messageRow.substring(messageRow.indexOf("║"), messageRow.lastIndexOf("║"))
    assert(!interior.contains("#"))

  test("dialog buttons that do not fit the box are dropped, not painted outside it"):
    val dialog = Dialog("Confirm", Text.raw("Pick"), Seq("Alpha", "Bravo", "Charlie", "Delta"))
    val buffer = Buffer(Rect(0, 0, 40, 9))
    dialog.render(Rect(0, 0, 20, 9), buffer)
    assert(trimmedLines(buffer).forall(_.length <= 20))

  /** Geometry only — the box is centred on both axes and never escapes the area it was given. `Rect.centered` itself is
    * covered by core's `RectOpsSpec`; these pin that the dialog actually routes its box through it.
    */
  test("a dialog widens its box for buttons wider than the message, so a translated label is not dropped"):
    val dialog = Dialog("Salir", Text.raw("¿Seguro?"), Seq("Sí", "Cancelar"))
    val lines  = trimmedLines(rendered(dialog, 50, 9))
    assert(lines.exists(_.contains("[ Sí ] [ Cancelar ]")), lines.mkString("\n"))

  test("a dialog centres its box on both axes"):
    val lines  = trimmedLines(rendered(Dialog("T", Text.raw("hi")), 30, 9))
    val top    = lines.indexWhere(_.contains("╔"))
    val bottom = lines.indexWhere(_.contains("╚"))
    assert((top, bottom) == (2, 6))      // a 5-row box in 9 rows leaves 2 rows above and below
    assert(lines(top).indexOf("╔") == 5) // a 20-column box in 30 columns leaves 5 columns either side
    assert(lines(top).length == 25)

  test("a dialog too large for its area is clamped inside it, not overflowed"):
    val lines = trimmedLines(rendered(Dialog("T", Text.raw("hi")), 8, 6))
    assert(lines.forall(_.length <= 8))
    assert(lines.head.startsWith("╔") && lines.head.endsWith("╗"))

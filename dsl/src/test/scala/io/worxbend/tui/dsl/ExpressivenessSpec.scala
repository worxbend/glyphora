package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Modifiers, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** The things a terminal UI needs to be able to say: padding between a border and its content, a caption on each border
  * styled apart from the frame, and one row carrying more than one style.
  */
final class ExpressivenessSpec extends AnyFunSuite:

  test("panel padding puts blank cells between the border and the children"):
    val padded = panel("p")(text("x")).padded(Padding(left = 2, right = 0, top = 1, bottom = 0))
    val lines  = trimmedLines(rendered(padded.widget, 10, 6))
    assert(lines(1) == "│        │") // the padding row: borders only, no content
    assert(lines(2) == "│  x     │") // border, then the two padding columns, then the text

  test("the proportional builder doubles the horizontal count"):
    assert(panel(text("x")).padding(1).padding == Padding(left = 2, right = 2, top = 1, bottom = 1))

  test("padding is charged to the measured height as well as to the rendering"):
    val bare   = panel(text("one\ntwo"))
    val padded = bare.padded(Padding.vertical(2))
    assert(bare.intrinsicHeight(20).contains(4)) // two content rows plus the two borders
    assert(padded.intrinsicHeight(20).contains(8)) // plus two rows above and two below

  test("a bottom title lands on the bottom border without costing a content row"):
    val element = panel("name")(text("body")).titleBottom("3 items")
    val lines   = trimmedLines(rendered(element.widget, 20, 3))
    assert(lines.head.startsWith("┌name"))
    assert(lines(1).contains("body"))
    assert(lines.last.endsWith("3 items┘"))

  test("titleStyle colours the caption and leaves the border alone"):
    val element = panel("Errors")(text("x")).frameStyle(Style.fg(Color.Blue)).titleStyle(_.withFg(Color.Red))
    val buffer  = rendered(element.widget, 12, 3)
    assert(buffer.get(1, 0).style.fg.contains(Color.Red))  // the "E" of the title
    assert(buffer.get(0, 0).style.fg.contains(Color.Blue)) // the top-left corner glyph
    assert(buffer.get(0, 1).style.fg.contains(Color.Blue)) // the left border

  test("without a titleStyle the caption follows the frame style"):
    val buffer = rendered(PanelElement(Some("Errors"), Seq(text("x"))).frameStyle(_.withFg(Color.Blue)).widget, 12, 3)
    assert(buffer.get(1, 0).style.fg.contains(Color.Blue))

  test("frameStyle colours the border; style colours the content and leaves the border alone"):
    val element = panel("x")(text("body")).frameStyle(Style.fg(Color.Red)).style(Style.fg(Color.Green))
    val buffer  = rendered(element.widget, 12, 3)
    assert(buffer.get(0, 0).style.fg.contains(Color.Red))   // corner
    assert(buffer.get(0, 1).style.fg.contains(Color.Red))   // left border
    assert(buffer.get(1, 1).style.fg.contains(Color.Green)) // the "b" of the body
    assert(buffer.get(8, 1).style.fg.contains(Color.Green)) // a blank content cell nobody else styled

  test("a child's own style wins over the panel's content style"):
    val element = panel("x")(text("body").fg(Color.Yellow)).fg(Color.Green)
    val buffer  = rendered(element.widget, 12, 3)
    assert(buffer.get(1, 1).style.fg.contains(Color.Yellow))
    assert(buffer.get(0, 1).style.fg.contains(Theme.Dark.border.fg.getOrElse(Color.Reset)))

  test("line renders differently-styled runs in one row"):
    val element = line("Status: ".styled(identity), "OK".styled(_.withFg(Color.Green)))
    val buffer  = rendered(element.widget, 20, 1)
    assert(trimmedLines(buffer) == Seq("Status: OK"))
    assert(buffer.get(0, 0).style.fg.isEmpty)
    assert(buffer.get(8, 0).style.fg.contains(Color.Green))

  test("a plain String part is drawn unstyled beside a styled span"):
    val element = line("Status: ", "OK".styled(_.withFg(Color.Green)))
    val buffer  = rendered(element.widget, 20, 1)
    assert(trimmedLines(buffer) == Seq("Status: OK"))
    assert(buffer.get(0, 0).style.fg.isEmpty)
    assert(buffer.get(8, 0).style.fg.contains(Color.Green))

  test("a plain String part is measured in display columns, not characters"):
    // "日本" is four columns, so the "ok" that follows it starts at column 4, not column 2
    val element = line("日本", "ok".styled(identity))
    val buffer  = rendered(element.widget, 20, 1)
    assert(element.intrinsicWidth(1).contains(6))
    assert(buffer.get(4, 0).symbol == "o")

  test("plain String parts inherit the element's own style"):
    val buffer = rendered(line("a", "b").dim.widget, 4, 1)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Dim))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Dim))

  test("a line claims exactly one row and its measured display width"):
    // two columns per character, so a declared `.length(4)` would have been half the truth
    val element = line("日本".styled(identity), "ok".styled(identity))
    assert(element.intrinsicHeight(6).contains(1))
    assert(element.intrinsicWidth(1).contains(6))

  test("the element style is the base each span layers onto"):
    val element = line("a".styled(identity), "b".styled(_.withFg(Color.Red))).bold
    val buffer  = rendered(element.widget, 4, 1)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(1, 0).style.fg.contains(Color.Red))

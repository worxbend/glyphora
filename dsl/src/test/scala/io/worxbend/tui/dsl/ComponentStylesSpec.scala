package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Modifiers, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Component styles with state variants (`StateStyles` + `ComponentRole`), resolved by the DSL host so the widgets stay
  * theme-agnostic, and the hierarchical names they are looked up by.
  */
final class ComponentStylesSpec extends AnyFunSuite with PilotFixture:

  private val primary = StateStyles(
    base = Style.Default.withFg(Color.White).withBg(Color.Blue),
    focused = Style.Default.withBg(Color.Cyan).bold,
    hovered = Style.Default.underline,
    disabled = Style.Default.withFg(Color.Indexed(240)).withBg(Color.Black),
  )

  private val themed: Theme = Theme.Dark.withComponent(ButtonRole.Primary, primary)

  private def driving(content: View)(body: Pilot => Unit): Unit =
    val app     = new TuiApp:
      override def theme: Theme                     = themed
      def view(using ReactiveScope, Theme): Element = content
    val backend = HeadlessBackend(Size(20, 2))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot)

  test("a hierarchical name falls back to its parent, merging the fields the child leaves unset"):
    val theme    = Theme.Dark
      .withComponent(
        ButtonRole.Default,
        StateStyles(base = Style.Default.withFg(Color.Red), focused = Style.Default.bold),
      )
      .withComponent(ButtonRole.Primary, StateStyles(base = Style.Default.withBg(Color.Blue)))
    val resolved = theme.component(ButtonRole.Primary)
    assert(resolved.base == Style.Default.withFg(Color.Red).withBg(Color.Blue))
    assert(resolved.focused == Style.Default.bold)
    assert(theme.component(StyleName("button.primary.large")) == resolved)
    assert(theme.component(InputRole) == StateStyles())

  test("StyleName walks up its dotted segments"):
    assert(StyleName("list.selected").parent.contains(StyleName("list")))
    assert(StyleName("list").parent.isEmpty)
    assert(StyleName("a.b.c").lineage == Seq(StyleName("a"), StyleName("a.b"), StyleName("a.b.c")))

  test("a primary button draws its focused component style"):
    driving(column(button("Save")(()).variant(ButtonRole.Primary).autofocus.length(1), text("").fill)) { pilot =>
      val cell = pilot.cellAt(8, 0)
      assert(cell.style.bg.contains(Color.Cyan), cell.toString)
      assert(cell.style.fg.contains(Color.White))
      assert(cell.style.modifiers.hasAny(Modifiers.Bold))
      assert(!cell.style.modifiers.hasAny(Modifiers.Reverse), "the component's focus style replaces the theme cue")
      pilot.assertGoldenStyled("button-primary-focused")
    }

  test("a primary button draws its hovered component style under the pointer"):
    driving(
      column(
        row(button("Other")(()).autofocus, button("Save")(()).variant(ButtonRole.Primary).key("save")).length(1),
        text("").fill,
      )
    ) { pilot =>
      val x = (0 until 20).find(x => pilot.cellAt(x, 0).symbol == "S").getOrElse(fail("no Save on screen"))
      assert(pilot.cellAt(x, 0).style.bg.contains(Color.Blue))
      assert(!pilot.cellAt(x, 0).style.modifiers.hasAny(Modifiers.Underline))
      pilot.mouseMove(x, 0).waitForIdle()
      assert(pilot.cellAt(x, 0).style.modifiers.hasAny(Modifiers.Underline), pilot.cellAt(x, 0).toString)
      assert(pilot.cellAt(x, 0).style.bg.contains(Color.Blue))
      pilot.assertGoldenStyled("button-primary-hovered")
    }

  test("a disabled primary button draws its disabled component style"):
    driving(column(button("Save")(()).variant(ButtonRole.Primary).disabled().length(1), text("").fill)) { pilot =>
      val cell = pilot.cellAt(8, 0)
      assert(cell.style.fg.contains(Color.Indexed(240)), cell.toString)
      assert(cell.style.bg.contains(Color.Black))
      pilot.assertGoldenStyled("button-primary-disabled")
    }

  test("with no component in the theme a button draws exactly as before"):
    driving(column(button("Save")(()).autofocus.length(1), text("").fill)) { pilot =>
      assert(pilot.cellAt(8, 0).style == Style.Default.reverse)
    }

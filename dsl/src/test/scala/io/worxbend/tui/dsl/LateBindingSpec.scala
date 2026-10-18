package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Modifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Built by top-level definitions, outside any view, so the only theme they see is the ambient `Theme.default`. */
private def helperHeader: Element = panel("Head")(text("x"))

private def helperCaptionUnderlined: Element = panel("Head")(text("x")).titleStyle(_.underline)

private def helperProgress: Element = progressBar(1.0).bare

/** Late theme binding: an element a helper built against the ambient default theme paints in the app's theme. */
final class LateBindingSpec extends AnyFunSuite with PilotFixture:

  private def driving(appTheme: Theme)(content: => Element)(body: Pilot => Unit): Unit =
    val app     = new TuiApp:
      override def theme: Theme                     = appTheme
      def view(using ReactiveScope, Theme): Element = content
    val backend = HeadlessBackend(Size(12, 3))
    body(startPilot(backend)(app.runWith(backend)).waitForIdle())

  test("a panel built in a top-level def paints Theme.Light's border and caption inside a Light app"):
    driving(Theme.Light)(helperHeader) { pilot =>
      assert(pilot.cellAt(0, 0).style.fg.contains(Color.Indexed(248)), pilot.cellAt(0, 0).toString)
      val caption = pilot.cellAt(1, 0)
      assert(caption.symbol == "H")
      assert(caption.style.fg.contains(Color.Black), caption.toString)
    }

  test("the same helper under the default theme still paints the dark colours"):
    driving(Theme.Dark)(helperHeader) { pilot =>
      assert(pilot.cellAt(0, 0).style.fg.contains(Color.Indexed(244)))
    }

  test("a builder applied after the factory survives the rebinding, layered over the app theme's value"):
    driving(Theme.Light)(helperCaptionUnderlined) { pilot =>
      val caption = pilot.cellAt(1, 0)
      assert(caption.style.fg.contains(Color.Black), caption.toString)
      assert(caption.style.modifiers.hasAny(Modifiers.Underline))
    }

  test("an element handed an explicit theme keeps it"):
    driving(Theme.Light)(panel("Head")(text("x"))(using Theme.HighContrast)) { pilot =>
      assert(pilot.cellAt(0, 0).style.fg.contains(Color.White), pilot.cellAt(0, 0).toString)
    }

  test("a loading widget built outside a view takes the app theme's loading palette"):
    driving(Theme.Light)(helperProgress) { pilot =>
      assert(pilot.cellAt(0, 0).style.fg.contains(Color.Blue), pilot.cellAt(0, 0).toString)
    }

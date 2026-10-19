package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Cell, Color, Modifiers, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Search highlights take their styles from the theme: the `"search.match"` / `"search.current"` components when the
  * theme has them, the theme's warning and error colours as a highlight otherwise.
  */
final class SearchThemeSpec extends AnyFunSuite with PilotFixture:

  private def searched(appTheme: Theme)(build: (LogState, SearchState) => Element)(body: Pilot => Unit): Unit =
    val lines   = LogState()
    Seq("ok", "warn one", "ok", "warn two").foreach(lines.append)
    val search  = SearchState()
    search.query = "warn"
    val app     = new TuiApp:
      override def theme: Theme                     = appTheme
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = build(lines, search)
    val backend = HeadlessBackend(Size(16, 4))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot)
    pilot.press("f10")
    assert(pilot.awaitTermination())

  /** The `w` that starts the match on `row`. */
  private def matchOn(pilot: Pilot, row: Int): Cell = pilot.cellAt(0, row)

  test("a searched log in a Light app highlights in the theme's warning and error colours"):
    searched(Theme.Light)((lines, search) => log(lines).searchable(search)) { pilot =>
      val styles = Seq(matchOn(pilot, 1).style, matchOn(pilot, 3).style)
      assert(styles.exists(_.bg.contains(Color.Indexed(130))), styles.toString)
      assert(styles.exists(s => s.bg.contains(Color.Red) && s.modifiers.hasAny(Modifiers.Bold)), styles.toString)
    }

  test("a theme's search components replace the derived highlight"):
    val themed = Theme.Dark
      .withComponent(SearchRole.Match, StateStyles(base = Style.Default.withBg(Color.Blue)))
      .withComponent(SearchRole.Current, StateStyles(base = Style.Default.withBg(Color.Magenta)))
    searched(themed)((lines, search) => log(lines).searchable(search)) { pilot =>
      val styles = Seq(matchOn(pilot, 1).style, matchOn(pilot, 3).style)
      assert(styles.exists(_.bg.contains(Color.Blue)), styles.toString)
      assert(styles.exists(_.bg.contains(Color.Magenta)), styles.toString)
    }

  test("a searched scrollView and a searched markdown document use the same theme styles"):
    val themed = Theme.Dark.withComponent(SearchRole.Match, StateStyles(base = Style.Default.withBg(Color.Blue)))
    val scroll = ScrollViewState()
    searched(themed)((_, search) =>
      scrollView(column(text("ok"), text("warn one"), text("warn two")), scroll).searchable(search)
    ) { pilot =>
      assert(Seq(1, 2).exists(row => matchOn(pilot, row).style.bg.contains(Color.Blue)), pilot.screenText)
    }
    searched(themed)((_, search) => markdown("warn one\n\nwarn two").searchable(search)) { pilot =>
      val cells = (0 until 4).map(row => matchOn(pilot, row))
      assert(cells.exists(c => c.symbol == "w" && c.style.bg.contains(Color.Blue)), pilot.screenText)
    }

package io.worxbend.tui.examples.showcase

import io.worxbend.tui.core.{KeyCode, KeyModifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

final class ShowcaseAppSpec extends AnyFunSuite:

  private def withApp[A]()(body: (ShowcaseApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(70, 20))
    val app     = ShowcaseApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      pilot.pressKey(KeyCode.Enter).waitForIdle() // skip the splash
      body(app, pilot)
    }

  test("the shell renders top bar, sidebar, tabs, and status hints"):
    withApp() { (_, pilot) =>
      assert(pilot.screenText.contains("glyphora"))
      assert(pilot.screenText.contains("Menu"))
      assert(pilot.screenText.contains("Widgets │ Loading │ Log │ About"))
      assert(pilot.screenText.contains("ctrl+t switch theme"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("the sidebar mirrors the selected tab and steers it"):
    withApp() { (app, pilot) =>
      assert(app.sidebarList.selected.contains(0), "the sidebar highlight starts on the first tab")
      // focus starts on the sidebar list: its arrows step the tab ring, and the highlight follows on the next frame
      pilot.pressKey(KeyCode.Down).waitForIdle()
      assert(app.selectedTab.peek == 1)
      assert(app.sidebarList.selected.contains(1))
      // the mirror direction too: switching pages from the tab row moves the sidebar highlight
      pilot.pressKey(KeyCode.Tab).waitForIdle() // focus the tabbed content
      pilot.pressKey(KeyCode.Right).waitForIdle()
      assert(app.selectedTab.peek == 2)
      assert(app.sidebarList.selected.contains(2))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  /** The gallery renders every preset at once, so this doubles as a smoke test that none of them blows up or draws a
    * hole in a real app's layout rather than in an isolated buffer.
    */
  test("the loading gallery renders every preset"):
    withApp() { (_, pilot) =>
      pilot.pressKey(KeyCode.Tab).waitForIdle()
      pilot.pressKey(KeyCode.Right).waitForIdle()
      val screen = pilot.screenText
      assert(screen.contains("spinners"))
      assert(screen.contains("line"), "the spinner gallery should name its presets")
      // The gallery is taller than the terminal, so it scrolls and its tail is reachable rather than lost below the
      // fold. Asserted on the *first* section leaving the screen, not on `screenText` having changed at all: every
      // widget on this page animates, so two consecutive frames differ whether or not anything scrolled — which is how
      // this assertion went on passing while the page had silently stopped scrolling altogether.
      pilot.pressKey(KeyCode.Tab).waitForIdle() // past the tab bar, onto the scroll view itself
      pilot.pressKey(KeyCode.PageDown).waitForIdle()
      assert(
        !pilot.screenText.contains("── spinners"),
        "the gallery should scroll past its first section to reach the presets below the fold",
      )
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("theme switching, toasts, and the modal all work end to end"):
    withApp() { (app, pilot) =>
      // the theme lives behind `TuiApp.theme`, which the view never reads through a signal, so this also pins that
      // switching it repaints rather than only updating the counter
      val beforeSwitch = pilot.cellAt(0, 0).style
      pilot.pressKey(KeyCode.Char('t'), KeyModifiers.Ctrl).waitForIdle()
      assert(app.themeIndex.peek == 1)
      assert(
        pilot.cellAt(0, 0).style != beforeSwitch,
        "switching the theme must repaint the frame, not just the signal",
      )
      pilot.pressKey(KeyCode.Char('n'), KeyModifiers.Ctrl).waitForIdle()
      assert(pilot.screenText.contains("hello from glyphora"))
      pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
      assert(pilot.screenText.contains("About"))
      assert(pilot.screenText.contains("press Esc to close"))
      pilot.pressKey(KeyCode.Escape).waitForIdle()
      assert(!pilot.screenText.contains("press Esc to close"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

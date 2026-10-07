package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

final class LayerAutofocusSpec extends AnyFunSuite:

  private final class App(dialogAutofocusKey: String) extends TuiApp:
    val baseFirst   = TextInputState()
    val baseAuto    = TextInputState()
    val baseLast    = TextInputState()
    val dialogFirst = TextInputState()
    val dialogAuto  = TextInputState()
    val dialogLast  = TextInputState()

    override def bindings: KeyBindings = KeyBindings(
      binding("ctrl+o", "open")(pushScreen(Screen(dialog, dismissal = Dismissal.Escape))),
      binding("ctrl+u", "clear focus")(clearFocus()),
    )

    def view(using ReactiveScope, Theme): Element =
      column(input(baseFirst), input(baseAuto).autofocus.key("auto"), input(baseLast).key("last"))

    private def dialog: View = panel("Dialog")(
      input(dialogFirst),
      input(dialogAuto).autofocus.key(dialogAutofocusKey),
      input(dialogLast).key("dialog-last"),
    )

  test("closing the palette restores focus without replaying base autofocus"):
    val app = App("dialog-auto")
    Pilot.using(Size(50, 14))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("tab").typeText("before").waitForIdle()
      assert(app.baseLast.value == "before")
      pilot.press("ctrl+p").waitForIdle()
      assert(pilot.screenText.contains("Commands"))
      pilot.press("esc").waitForIdle().typeText("!").waitForIdle()
      assert(app.baseLast.value == "before!")
      assert(app.baseAuto.value == "")
    }

  test("closing a layer preserves deliberately cleared focus despite an existing autofocus request"):
    val app = App("dialog-auto")
    Pilot.using(Size(50, 14))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("ctrl+u").waitForIdle()
      pilot.press("ctrl+p").waitForIdle().press("esc").waitForIdle().typeText("!").waitForIdle()
      assert(app.baseAuto.value == "")
      assert(app.baseFirst.value == "")
      assert(app.baseLast.value == "")
    }

  test("a new autofocus request that appeared while covered still wins when the layer is restored"):
    val tracker = FocusTracker()
    val keys    = Seq(Some("first"), Some("new"), Some("last"))
    tracker.reconcile(keys, Some(AutofocusRequest(0, Some("first"))))
    assert(tracker.focusToKey("last"))
    tracker.pushLayer()
    tracker.reconcile(Seq(Some("dialog")), None)
    tracker.popLayer()
    tracker.reconcile(keys, Some(AutofocusRequest(1, Some("new"))))
    assert(tracker.focusedKey.contains("new"))

  test("popping without a saved layer resets autofocus history with the focus anchor"):
    val tracker = FocusTracker()
    val keys    = Seq(None, Some("auto"))
    val request = Some(AutofocusRequest(1, Some("auto")))
    tracker.reconcile(keys, request)
    tracker.popLayer()
    tracker.reconcile(keys, request)
    assert(tracker.focusedIndex == 1)

  test("dismissing a modal restores the base layer's autofocus history"):
    val app = App("dialog-auto")
    Pilot.using(Size(50, 14))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("tab").typeText("base").waitForIdle()
      assert(app.baseLast.value == "base")
      pilot.press("ctrl+o").waitForIdle().typeText("dialog").waitForIdle()
      assert(app.dialogAuto.value == "dialog")
      pilot.press("esc").waitForIdle().typeText("!").waitForIdle()
      assert(app.baseLast.value == "base!")
      assert(app.baseAuto.value == "")
    }

  test("an incoming layer honors autofocus even when its request matches the covered layer"):
    val app = App("auto")
    Pilot.using(Size(50, 14))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("tab").waitForIdle()
      pilot.press("ctrl+o").waitForIdle().typeText("dialog").waitForIdle()
      assert(app.dialogAuto.value == "dialog")
      assert(app.dialogFirst.value == "")
    }

  test("palette over modal restores each layer's focus and autofocus history independently"):
    val app = App("dialog-auto")
    Pilot.using(Size(50, 14))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("tab").typeText("base").waitForIdle()
      pilot.press("ctrl+o").waitForIdle().press("tab").typeText("dialog").waitForIdle()
      assert(app.dialogLast.value == "dialog")
      pilot.press("ctrl+p").waitForIdle()
      assert(pilot.screenText.contains("Commands"))
      pilot.press("esc").waitForIdle().typeText("!").waitForIdle()
      assert(app.dialogLast.value == "dialog!")
      assert(app.dialogAuto.value == "")
      pilot.press("esc").waitForIdle().typeText("!").waitForIdle()
      assert(app.baseLast.value == "base!")
      assert(app.baseAuto.value == "")
    }

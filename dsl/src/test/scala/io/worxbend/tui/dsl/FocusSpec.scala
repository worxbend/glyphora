package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{ScrollViewState, TextInputState}

import org.scalatest.funsuite.AnyFunSuite

/** Focus and event-routing acceptance tests: tab-order traversal, focused-first key dispatch, bubbling with
  * stop-propagation, and click-to-focus.
  */
final class FocusSpec extends AnyFunSuite with PilotFixture:

  /** Two inputs and a checkbox; whichever is focused receives typed characters. */
  private final class FormApp extends TuiApp:
    val first                                     = TextInputState()
    val second                                    = TextInputState()
    val agreed                                    = Signal(false)
    def view(using ReactiveScope, Theme): Element =
      column(
        input(first, placeholder = "first"),
        input(second, placeholder = "second"),
        checkbox("agree", agreed),
      ).onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), m) if m.hasAny(KeyModifiers.Ctrl) =>
          quit()
          true
        case _                                                             => false
      }

  private def startedApp(): (FormApp, Pilot) =
    val backend = HeadlessBackend(Size(30, 5))
    val app     = FormApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    (app, pilot)

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("typed characters go to the first focusable by default"):
    val (app, pilot) = startedApp()
    pilot.typeText("hi").waitForIdle()
    assert(app.first.value == "hi")
    assert(app.second.value == "")
    quitApp(pilot)

  test("Tab moves focus to the next element in depth-first order"):
    val (app, pilot) = startedApp()
    pilot.pressKey(KeyCode.Tab).typeText("yo").waitForIdle()
    assert(app.first.value == "")
    assert(app.second.value == "yo")
    quitApp(pilot)

  test("Shift+Tab moves focus backwards and wraps around"):
    val (app, pilot) = startedApp()
    pilot.pressKey(KeyCode.Tab, KeyModifiers.Shift).waitForIdle() // 0 -> wraps to last (checkbox)
    pilot.pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(app.agreed.peek)
    quitApp(pilot)

  test("tab cycles past the end back to the first element"):
    val (app, pilot) = startedApp()
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).typeText("x").waitForIdle()
    assert(app.first.value == "x")
    quitApp(pilot)

  test("space toggles the focused checkbox through its signal"):
    val (app, pilot) = startedApp()
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(app.agreed.peek)
    pilot.pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(!app.agreed.peek)
    quitApp(pilot)

  test("the focused input shows its cursor on screen"):
    val (_, pilot) = startedApp()
    pilot.typeText("ab").waitForIdle()
    assert(pilot.screenLines.head.startsWith("ab"))
    quitApp(pilot)

  test("clicking a focusable element focuses it"):
    val (app, pilot) = startedApp()
    pilot.click(2, 1).waitForIdle() // second input renders on row 1
    pilot.typeText("z").waitForIdle()
    assert(app.second.value == "z")
    assert(app.first.value == "")
    quitApp(pilot)

  test("an event unconsumed by the focused element bubbles to ancestors"):
    val (app, pilot) = startedApp()
    // Ctrl+Q is not editing input: the focused element declines it, the root handler quits
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
    assert(app.first.value == "")

  test("a consumed event stops at the focused element and never reaches ancestors"):
    val backend     = HeadlessBackend(Size(30, 5))
    var rootSawChar = false
    val field       = TextInputState()
    val app         = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(input(field)).onKeyEvent {
          case KeyEvent(KeyCode.Char('x'), _) =>
            rootSawChar = true
            true
          case KeyEvent(KeyCode.Char('q'), _) =>
            quit()
            true
          case _                              => false
        }
    val pilot       = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("x").waitForIdle()            // consumed by the focused input's editing handler
    assert(field.value == "x")
    assert(!rootSawChar)
    pilot.pressKey(KeyCode.Escape).waitForIdle() // input declines Escape; root also declines: harmless
    pilot.pressKey(KeyCode.Char('q'))            // 'q' would be typed into the input...
    pilot.waitForIdle()
    assert(field.value == "xq")                  // ...proving focused-first ordering
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("onKeyEvent on a control itself runs before that control's own editing behavior"):
    // every other key-ordering test in this suite hangs `onKeyEvent` on a *container* — a column, a panel, a body —
    // which has no built-in of its own, so it cannot tell "user first" from "built-in first". This is the arrangement
    // an ordinary form uses: `input(state).onKeyEvent { case KeyEvent(KeyCode.Enter, _) => submit(); true; case _ =>
    // false }`, where the handler must beat the input's own typing behavior for the keys it claims and lose to it for
    // every key it declines.
    val backend  = HeadlessBackend(Size(30, 5))
    val field    = TextInputState()
    var claimed  = 0
    var declined = 0
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        input(field).onKeyEvent {
          case KeyEvent(KeyCode.Char('!'), _) =>
            claimed += 1
            true
          case _                              =>
            declined += 1
            false
        }
    val pilot    = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("!").waitForIdle()
    assert(claimed == 1)
    assert(field.value == "", s"the claimed key was typed anyway: '${field.value}'")
    pilot.typeText("a").waitForIdle()
    // the other half: a key the handler declines still reaches the built-in, so the test cannot be satisfied by a
    // handler that simply always wins
    assert(declined >= 1)
    assert(field.value == "a", s"the declined key never reached the input: '${field.value}'")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a focus key keeps focus on the same element when the tree changes shape"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val showTop = io.worxbend.tui.runtime.Signal(false)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+o", "insert element above")(showTop.set(true)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        val top = if showTop.get then Seq(input(first).key("first")) else Seq.empty
        column((top :+ input(second).key("second"))*)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(second.value == "a") // 'second' is the only focusable, so it has focus
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle() // 'first' appears above it
    pilot.typeText("b").waitForIdle()
    assert(second.value == "ab") // focus stayed with the keyed element, not with position 0
    assert(first.value == "")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("Tab still moves focus when the elements carry focus keys"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(input(first).key("first"), input(second).key("second"))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Tab).typeText("hi").waitForIdle()
    // an explicit focus move must win over the remembered key, or Tab is a no-op on keyed trees
    assert(first.value == "")
    assert(second.value == "hi")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("clicking a keyed element focuses it"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(input(first).key("first"), input(second).key("second"))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.click(2, 1).waitForIdle()
    pilot.typeText("z").waitForIdle()
    assert(first.value == "")
    assert(second.value == "z")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the focused element renders with the theme focus style"):
    val backend = HeadlessBackend(Size(20, 3))
    val agreed  = io.worxbend.tui.runtime.Signal(false)
    val app     = new TuiApp:
      override def theme: Theme                     = Theme.HighContrast
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = checkbox("agree", agreed)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    val cell    = pilot.backend.lastDrawn.map(_.get(0, 0)).getOrElse(fail("no frame"))
    // HighContrast focus = reverse + bold: proves the theme's focus style is applied, not a hardcoded reverse
    assert(cell.style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Reverse))
    assert(cell.style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Bold))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  // ---- a modal covers the layer below it for input, not merely for tabbing ----

  test("a suppressed layer is inert to keys even when the layer above has no focusable"):
    val seen  = scala.collection.mutable.Buffer[String]()
    val base  = column(text("base")).onKeyEvent { _ =>
      seen += "base"
      true
    }
    val modal = centered(20, 3)(panel("Delete?")(text("d = confirm"))).onKeyEvent { _ =>
      seen += "modal"
      true
    }
    val tree  = layers(FocusPass.suppressFocus(base), modal)
    assert(EventRouter.dispatchKey(tree, KeyEvent(KeyCode.Char('d'), KeyModifiers.None)))
    assert(seen.toSeq == Seq("modal"))

  test("a suppressed layer is inert to mouse events"):
    var baseClicks = 0
    val base       = column(text("base")).onMouseEvent { _ =>
      baseClicks += 1
      true
    }
    val tracker    = FocusTracker()
    val suppressed = FocusPass.decorate(FocusPass.suppressFocus(base), tracker, Style.Default)
    // rendering once is what makes this assertion mean something: mouse delivery only reaches elements whose area was
    // recorded under the pointer, so without a render the handler would be skipped for want of an area rather than
    // for being inert
    val area       = Rect(0, 0, 10, 3)
    suppressed.widget.render(area, Buffer(area))
    assert(
      !EventRouter.dispatchMouse(suppressed, MouseEvent(Position(1, 1), MouseEventKind.Down, KeyModifiers.None), None)
    )
    assert(baseClicks == 0)

  test("a modal with no focusable element takes keys before the layer it covers"):
    val backend   = HeadlessBackend(Size(40, 8))
    val baseKeys  = scala.collection.mutable.Buffer[String]()
    val modalKeys = scala.collection.mutable.Buffer[String]()
    val app       = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+o", "open dialog")(openDialog()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        column(text("base screen")).onKeyEvent {
          case KeyEvent(KeyCode.Char(c), m) if !m.hasAny(KeyModifiers.Ctrl) =>
            baseKeys += Character.toString(c)
            true
          case _                                                            => false
        }
      // panel/text only: nothing in the dialog is focusable, so the whole tree has no focus path
      private def openDialog(): Unit                = pushScreen(Screen {
        centered(30, 5)(panel("Delete?")(text("d = confirm, esc = cancel"))).onKeyEvent {
          case KeyEvent(KeyCode.Escape, _)                                  =>
            popScreen()
            true
          case KeyEvent(KeyCode.Char(c), m) if !m.hasAny(KeyModifiers.Ctrl) =>
            modalKeys += Character.toString(c)
            true
          case _                                                            => false
        }
      })
    val pilot     = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("d").waitForIdle()
    assert(baseKeys.toSeq == Seq("d"))               // the base handler is live to begin with
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Delete?"))
    assert(pilot.screenText.contains("base screen")) // still visible beneath
    pilot.typeText("d").waitForIdle()
    assert(modalKeys.toSeq == Seq("d"))
    assert(baseKeys.toSeq == Seq("d"))               // unchanged: the covered layer saw nothing
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    pilot.typeText("d").waitForIdle()
    assert(baseKeys.toSeq == Seq("d", "d"))          // live again once the modal is popped
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("clicks never reach the layer a modal covers"):
    val backend     = HeadlessBackend(Size(40, 8))
    var baseClicks  = 0
    var modalClicks = 0
    val app         = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+o", "open dialog")(openDialog()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        column(text("base screen")).onMouseEvent { _ =>
          baseClicks += 1
          true
        }
      private def openDialog(): Unit                = pushScreen(Screen {
        centered(30, 5)(panel("Delete?")(text("confirm?"))).onMouseEvent { _ =>
          modalClicks += 1
          true
        }
      })
    val pilot       = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.click(0, 0).waitForIdle()
    val baseBefore  = baseClicks
    assert(baseBefore > 0)           // the base handler is live to begin with (click posts Down and Up)
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.click(20, 4).waitForIdle() // inside the dialog
    pilot.click(0, 0).waitForIdle()  // outside it
    assert(baseClicks == baseBefore) // the covered layer took neither click
    assert(modalClicks > 0)          // the top layer still receives mouse input
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("suppression reaches a responsive branch that resolves after the modal was composed"):
    val field    = TextInputState()
    val composed = layers(FocusPass.suppressFocus(responsive(_ => column(input(field)))), text("dialog"))
    val resolved = ResponsivePass.resolve(composed, Size(40, 8))
    assert(FocusPass.focusKeys(resolved).isEmpty)
    assert(!EventRouter.dispatchKey(resolved, KeyEvent(KeyCode.Char('x'), KeyModifiers.None)))
    assert(field.value == "")

  test("a focusable inside a responsive branch below a modal stops receiving keys"):
    val backend   = HeadlessBackend(Size(40, 8))
    val baseField = TextInputState()
    val app       = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+o", "open dialog")(openDialog()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = responsive(_ => column(input(baseField)))
      private def openDialog(): Unit = pushScreen(Screen(centered(30, 5)(panel("Delete?")(text("confirm?")))))
    val pilot     = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(baseField.value == "a")
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("b").pressKey(KeyCode.Tab).typeText("c").waitForIdle()
    assert(baseField.value == "a") // neither typing nor a Tab move reaches the covered branch
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** A built-in belongs to the *focused* element alone. Both key walks hand the event to unfocused elements as well —
    * the ancestors an unconsumed key bubbles through, and every node of the depth-first walk when nothing is focusable
    * — so without that gate an arrow key typed at an inner control would also drive whatever container encloses it.
    *
    * Both cases render before dispatching: `ScrollViewState.scrollDown` clamps against the content and viewport heights
    * the last render recorded, so on an unrendered view the offset cannot move and the negative case would pass for the
    * wrong reason.
    */
  private def scrollViewOverInput(focusedIndex: Int): (ScrollViewState, Element) =
    val scroll  = ScrollViewState()
    val tracker = FocusTracker()
    // content twice the viewport height, so there is somewhere to scroll to
    val root    = scrollView(column(input(TextInputState())), contentHeight = 8, scroll)
    tracker.reconcile(FocusPass.focusKeys(root), FocusPass.autofocusRequest(root))
    tracker.focusTo(focusedIndex)
    val tree    = FocusPass.decorate(root, tracker, Style.Default)
    val area    = Rect(0, 0, 20, 4)
    tree.widget.render(area, Buffer(area))
    (scroll, tree)

  test("a key bubbling past an unfocused ancestor does not trigger the ancestor's built-in"):
    // depth-first pre-order: 0 is the scroll view, 1 is the input inside it
    val (scroll, tree) = scrollViewOverInput(focusedIndex = 1)
    // the input ignores Down, so it bubbles to the scroll view — which is on the path but not focused
    assert(!EventRouter.dispatchKey(tree, KeyEvent(KeyCode.Down, KeyModifiers.None)))
    assert(scroll.offset == 0)

  test("the same key does scroll once the scroll view itself is focused"):
    val (scroll, tree) = scrollViewOverInput(focusedIndex = 0)
    assert(EventRouter.dispatchKey(tree, KeyEvent(KeyCode.Down, KeyModifiers.None)))
    assert(scroll.offset == 1)

  test("a focusable wrapped straight in a FilledElement still takes a tab stop and a hit-test area"):
    // FilledElement used to expose its wrapped node's *children* rather than the node, so the focus pass walked past
    // the node itself: a control handed straight to it got neither a focus index nor a recorded area.
    val tracker = FocusTracker()
    val root    = FilledElement(button("press me")(()), Style.Default)
    assert(FocusPass.focusKeys(root).size == 1)
    tracker.reconcile(FocusPass.focusKeys(root), FocusPass.autofocusRequest(root))
    val tree    = FocusPass.decorate(root, tracker, Style.Default)
    val area    = Rect(0, 0, 20, 1)
    tree.widget.render(area, Buffer(area))
    assert(tracker.hitTest(Position(2, 0)).contains(0))

  test("a FilledElement measures what it wraps unless it carries a length of its own"):
    assert(FilledElement(text("a\nb\nc"), Style.Default).intrinsicHeight(20).contains(3))
    assert(FilledElement(text("a\nb\nc"), Style.Default).length(1).intrinsicHeight(20).contains(1))

  test("a click resolves to the focusable painted last, not the one with the smallest area"):
    // Paint order, not rectangle size, decides which focusable a pointer lands on. Index 0 here is a small rectangle
    // recorded first (something drawn underneath) and index 1 a large one recorded after it that covers the same cell
    // (an overlay drawn on top). The overlay is what the user sees at (3, 3), so it is what the click belongs to.
    val tracker = FocusTracker()
    tracker.record(0, Rect(2, 2, 4, 2))
    tracker.record(1, Rect(0, 0, 20, 10))
    assert(tracker.hitTest(Position(3, 3)).contains(1))

  test("reversing the paint order reverses which focusable a click resolves to"):
    // The same two rectangles recorded the other way round: the small one is now painted last, so it wins. Without
    // this pair the previous test would also pass under the old "smallest covering area" rule for the wrong reason.
    val tracker = FocusTracker()
    tracker.record(0, Rect(0, 0, 20, 10))
    tracker.record(1, Rect(2, 2, 4, 2))
    assert(tracker.hitTest(Position(3, 3)).contains(1))

  test("a focusable painted inside another still wins the click, because a child paints after its parent"):
    // The nesting case the old rule got right has to keep working: rendering a real tree assigns the enclosing panel
    // its area before the button inside it, so the button carries the greater paint sequence.
    val tracker = FocusTracker()
    val root    = scrollView(column(button("press me")(())), contentHeight = 2, ScrollViewState())
    tracker.reconcile(FocusPass.focusKeys(root), FocusPass.autofocusRequest(root))
    val tree    = FocusPass.decorate(root, tracker, Style.Default)
    val area    = Rect(0, 0, 20, 4)
    tree.widget.render(area, Buffer(area))
    assert(tracker.hitTest(Position(2, 0)).contains(1)) // 0 is the scroll view, 1 the button drawn inside it

  test("clearing the recorded areas restarts the paint sequence for the next frame"):
    // The counter has to be reset with the areas. If it kept climbing across frames the comparison would still be
    // correct, but the first frame after a very long session could overflow; resetting keeps it bounded by the number
    // of focusables in one frame.
    val tracker = FocusTracker()
    tracker.record(0, Rect(0, 0, 20, 10))
    tracker.record(1, Rect(2, 2, 4, 2))
    tracker.clearAreas()
    tracker.record(0, Rect(0, 0, 20, 10))
    assert(tracker.hitTest(Position(3, 3)).contains(0))
    assert(tracker.areaOf(1).isEmpty)

  test("an area recorded through a scroll viewport is hit-testable at its screen position"):
    // A record that clips to nothing is not stored and must not consume a paint sequence either; one that survives the
    // clip is stored at its translated screen rectangle.
    val tracker = FocusTracker()
    tracker.pushViewport(ViewportTransform(0, -5, Rect(0, 0, 20, 4)))
    tracker.record(0, Rect(0, 6, 20, 1)) // content row 6, two rows into the viewport once the offset is applied
    tracker.record(1, Rect(0, 0, 20, 1)) // content row 0 is scrolled above the viewport and clips away
    tracker.popViewport()
    assert(tracker.areaOf(0).contains(Rect(0, 1, 20, 1)))
    assert(tracker.areaOf(1).isEmpty)
    assert(tracker.hitTest(Position(3, 1)).contains(0))

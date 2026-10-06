package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** Responsive-layout acceptance tests: a view that branches on [[TuiApp.terminalSize]], a tree that swaps subtrees
  * through [[Element.responsive]], and the focus/mouse routing that has to keep working across the swap.
  */
final class ResponsiveSpec extends AnyFunSuite with PilotFixture:

  /** Wide: a sidebar beside a detail pane. Narrow: the sidebar is gone and the detail input is the only focusable —
    * different components, not merely different constraints.
    */
  private final class AdaptiveApp extends TuiApp:
    val sidebarQuery                              = TextInputState()
    val detailQuery                               = TextInputState()
    var resizes: List[Size]                       = Nil
    override def onResize(size: Size): Unit       = resizes = size :: resizes
    def view(using ReactiveScope, Theme): Element =
      val body =
        if terminalSize.width < 60 then column(text("compact"), input(detailQuery, placeholder = "detail"))
        else
          row(
            panel("Sidebar")(input(sidebarQuery, placeholder = "sidebar")).percent(30),
            panel("Detail")(input(detailQuery, placeholder = "detail")).fill,
          )
      body.onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), m) if m.hasAny(KeyModifiers.Ctrl) =>
          quit()
          true
        case _                                                             => false
      }

  /** The same swap, expressed with a `responsive` node nested inside a panel rather than an `if` at the top of `view`.
    */
  private final class NestedResponsiveApp extends TuiApp:
    val query                                     = TextInputState()
    def view(using ReactiveScope, Theme): Element =
      panel("Wrapper")(
        responsive {
          case size if size.width < 60 => text("narrow branch")
          case _                       => column(text("wide branch"), input(query, placeholder = "wide input"))
        }
      ).onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), m) if m.hasAny(KeyModifiers.Ctrl) =>
          quit()
          true
        case _                                                             => false
      }

  private def start(app: TuiApp, size: Size): Pilot =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  // ---- Breakpoint ----

  test("breakpoint bands split at the widths terminals actually cluster around"):
    assert(Breakpoint.ofWidth(0) == Breakpoint.XSmall)
    assert(Breakpoint.ofWidth(59) == Breakpoint.XSmall)
    assert(Breakpoint.ofWidth(60) == Breakpoint.Small)
    assert(Breakpoint.ofWidth(79) == Breakpoint.Small)
    assert(Breakpoint.ofWidth(80) == Breakpoint.Medium)
    assert(Breakpoint.ofWidth(119) == Breakpoint.Medium)
    assert(Breakpoint.ofWidth(120) == Breakpoint.Large)
    assert(Breakpoint.of(Size(200, 3)) == Breakpoint.Large) // width-only: a short terminal is still wide

  test("breakpoint comparisons are cumulative"):
    assert(Breakpoint.Large.atLeast(Breakpoint.Medium))
    assert(Breakpoint.Medium.atLeast(Breakpoint.Medium))
    assert(!Breakpoint.Small.atLeast(Breakpoint.Medium))
    assert(Breakpoint.XSmall.isBelow(Breakpoint.Small))

  // ---- terminalSize in view ----

  test("a view branching on terminalSize picks the wide layout at the starting size"):
    val app   = AdaptiveApp()
    val pilot = start(app, Size(100, 10))
    assert(pilot.screenText.contains("Sidebar"))
    assert(pilot.screenText.contains("Detail"))
    quitApp(pilot)

  test("shrinking the terminal re-evaluates the view and swaps in the compact layout"):
    val app   = AdaptiveApp()
    val pilot = start(app, Size(100, 10))
    pilot.resize(40, 10).waitForIdle()
    assert(pilot.screenText.contains("compact"))
    assert(!pilot.screenText.contains("Sidebar"))
    quitApp(pilot)

  test("growing the terminal back restores the wide layout"):
    val app   = AdaptiveApp()
    val pilot = start(app, Size(40, 10))
    assert(pilot.screenText.contains("compact"))
    pilot.resize(100, 10).waitForIdle()
    assert(pilot.screenText.contains("Sidebar"))
    quitApp(pilot)

  test("onResize sees the new size, and does not fire for the initial frame"):
    val app   = AdaptiveApp()
    val pilot = start(app, Size(100, 10))
    assert(app.resizes.isEmpty)
    pilot.resize(40, 12).waitForIdle()
    assert(app.resizes.headOption.contains(Size(40, 12)))
    quitApp(pilot)

  test("the tab order follows the swap: focusables that only exist when wide disappear when narrow"):
    val app   = AdaptiveApp()
    val pilot = start(app, Size(100, 10))
    pilot.typeText("wide").waitForIdle()
    assert(app.sidebarQuery.value == "wide")  // the sidebar input is the first focusable while wide
    pilot.resize(40, 10).waitForIdle()
    pilot.typeText("narrow").waitForIdle()
    assert(app.detailQuery.value == "narrow") // the only remaining focusable takes the keys
    assert(app.sidebarQuery.value == "wide")  // and the vanished one receives nothing
    quitApp(pilot)

  // ---- the responsive element ----

  test("a nested responsive node resolves against the terminal size, not its own area"):
    val app   = NestedResponsiveApp()
    val pilot = start(app, Size(100, 10))
    // the node sits inside a bordered panel, so its own area is narrower than the terminal; media-query semantics
    // mean the terminal's 100 columns decide the branch
    assert(pilot.screenText.contains("wide branch"))
    pilot.resize(40, 10).waitForIdle()
    assert(pilot.screenText.contains("narrow branch"))
    quitApp(pilot)

  test("focusables inside a responsive branch join the tab order and receive keys"):
    val app   = NestedResponsiveApp()
    val pilot = start(app, Size(100, 10))
    pilot.typeText("typed").waitForIdle()
    assert(app.query.value == "typed")
    quitApp(pilot)

  test("clicks hit-test into a responsive branch"):
    val app   = ClickApp()
    val pilot = start(app, Size(100, 10))
    pilot.click(2, 0).waitForIdle()
    assert(app.clicks == 1)
    quitApp(pilot)

  private final class ClickApp extends TuiApp:
    var clicks                                    = 0
    def view(using ReactiveScope, Theme): Element =
      responsive(_ => column(button("press")(clicks += 1))).onKeyEvent {
        case KeyEvent(KeyCode.Char('q'), m) if m.hasAny(KeyModifiers.Ctrl) =>
          quit()
          true
        case _                                                             => false
      }

  // ---- resolution-pass mechanics ----

  /** Renders `element` into a `width` x `height` buffer and reads back its first row.
    *
    * The buffer is deliberately narrower than the size the pass resolved against, so a node that fell back to building
    * from its own render area reports the buffer's width and the resolved one reports the pass's — the two paths are
    * distinguishable rather than both merely "some text".
    */
  private def firstRow(element: Element, width: Int, height: Int): String =
    val buffer = Buffer(Rect(0, 0, width, height))
    element.widget.render(Rect(0, 0, width, height), buffer)
    (0 until width).map(x => buffer.get(x, 0).symbol).mkString.trim

  test("a responsive node whose builder returns another responsive node resolves through to the innermost branch"):
    val tree     = responsive(_ => responsive(size => text(s"w=${size.width}")))
    val resolved = ResponsivePass.resolve(tree, Size(80, 24))
    assert(firstRow(resolved, 12, 1) == "w=80")

  test("a builder that returns itself stops at the nesting limit instead of hanging"):
    lazy val cyclic: ResponsiveElement = responsive(_ => cyclic)
    val resolved                       = ResponsivePass.resolve(cyclic, Size(80, 24))
    // degraded to its own render-time fallback, not an infinite loop
    resolved match
      case _: ResponsiveElement => ()
      case other                => fail(s"expected the unresolved node itself, got $other")

  test("responsive nodes nested inside containers are resolved too"):
    val tree     = column(panel("outer")(responsive(size => text(s"w=${size.width}"))))
    val resolved = ResponsivePass.resolve(tree, Size(72, 24))
    assert(firstRow(resolved, 12, 3).contains("outer"))
    assert(firstRow(resolved.children.head.children.head, 12, 1) == "w=72")

  test("an unresolved responsive node falls back to building against its own render area"):
    assert(firstRow(responsive(size => text(s"w=${size.width}")), 20, 1) == "w=20")

  test("the branch's own layout claim becomes the node's when none is set on it"):
    val resolved = ResponsivePass.resolve(responsive(_ => text("one row")), Size(80, 24))
    assert(resolved.intrinsicHeight(80).contains(1))

  test("a constraint set on the responsive node wins over the branch's own claim"):
    val resolved = ResponsivePass.resolve(responsive(_ => text("one row")).length(3), Size(80, 24))
    assert(resolved.props.constraint.contains(Constraint.Length(3)))
    assert(resolved.intrinsicHeight(80).contains(3))

  test("a handler set on the responsive node still fires for keys its branch did not consume"):
    var seen     = 0
    val tree     = responsive(_ => text("body")).onKeyEvent { _ =>
      seen += 1
      true
    }
    val resolved = ResponsivePass.resolve(tree, Size(80, 24))
    assert(EventRouter.dispatchKey(resolved, KeyEvent(KeyCode.Char('x'), KeyModifiers.None)))
    assert(seen == 1)

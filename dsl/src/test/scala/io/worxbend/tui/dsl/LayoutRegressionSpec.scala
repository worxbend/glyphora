package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  Color,
  Constraint,
  GlyphSupport,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  Rect,
  Size,
  Style,
}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.BufferAssertions.lines
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w
import org.scalatest.funsuite.AnyFunSuite

final class LayoutRegressionSpec extends AnyFunSuite:
  private given ReactiveScope                                                        = ReactiveScope.untracked
  private def draw(host: ElementHost, view: View, theme: Theme = Theme.Dark): Buffer =
    val buffer = Buffer(Rect(0, 0, 30, 6))
    host.render(buffer.area, buffer, theme, view)
    buffer
  private def press(host: ElementHost, key: KeyCode): Boolean = host.dispatchKey(KeyEvent(key, KeyModifiers.None))

  test("LAYOUT-1 a virtualized keyed action cannot activate a different record while absent"):
    val host       = ElementHost()
    val state      = LazyListState()
    var activated  = Vector.empty[Int]
    val view: View = lazyList(1000, 1, state)(i => button(s"delete $i") { activated :+= i }.key(s"row$i"))
    draw(host, view)
    assert(host.focusToKey("row1"))
    draw(host, view)
    state.selected = Some(500)
    draw(host, view)
    assert(host.focusedKey.contains("row1"))
    assert(!press(host, KeyCode.Enter))
    assert(activated.isEmpty)
    state.selected = Some(1)
    draw(host, view)
    assert(press(host, KeyCode.Enter))
    assert(activated == Vector(1))

  test("LAYOUT-1 wheel-scrolling a focused keyed action away never transfers Enter to another item"):
    var activated = Vector.empty[Int]
    val state     = LazyListState()
    final class ActionApp extends TuiApp:
      def focusRow(): Boolean                       = focusTo("row1")
      def view(using ReactiveScope, Theme): Element =
        lazyList(1000, 1, state)(i => button(s"delete $i") { activated :+= i }.key(s"row$i"))
    val app = ActionApp()
    Pilot.using(Size(30, 4))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      assert(pilot.readOnRenderThread(app.focusRow()))
      pilot.waitForIdle()
      (1 to 12).foreach(_ => pilot.scrollDown(1, 1).waitForIdle())
      assert(!pilot.screenText.contains("delete 1 ]"))
      pilot.press("enter").waitForIdle()
      assert(pilot.readOnRenderThread(activated).isEmpty)
    }

  private def containers: Seq[(String, Element => Element)] = Seq(
    "row"         -> (child => row(child)),
    "column"      -> (child => column(child)),
    "grid"        -> (child => grid(Seq(Constraint.fill), Seq(Constraint.fill))(child)),
    "flow"        -> (child => flow()(child)),
    "layers"      -> (child => layers(child)),
    "dock"        -> (child => dock()(child)),
    "scrollView"  -> (child => scrollView(child, ScrollViewState())),
    "lazyList"    -> (child => lazyList(1, 1, LazyListState())(_ => child)),
    "accordion"   -> (child => accordion(w.AccordionState(Some(0)))(AccordionSection("heading", child))),
    "windowStack" -> (child => windowStack(window("title", w.WindowStack().window(0, 0, 20, 5))(child))),
    "panel"       -> (child => panel(child)),
    "container"   -> (child => container(child)((area, _) => Seq(area))),
  )

  for (name, wrap) <- containers do
    test(s"LAYOUT-2 $name cascades content style and preserves a child's explicit color"):
      val host     = ElementHost()
      val frame    = draw(host, wrap(text("Q")).fg(Color.Red))
      val q        = for y <- 0 until frame.area.height; x <- 0 until frame.area.width if frame.get(x, y).symbol == "Q"
      yield frame.get(x, y)
      assert(q.nonEmpty)
      assert(q.forall(_.style.fg.contains(Color.Red)))
      val explicit = draw(host, wrap(text("Q").fg(Color.Blue)).fg(Color.Red))
      val child    = for
        y <- 0 until explicit.area.height; x <- 0 until explicit.area.width if explicit.get(x, y).symbol == "Q"
      yield explicit.get(x, y)
      assert(child.nonEmpty && child.forall(_.style.fg.contains(Color.Blue)))

  for name <- Seq("grid", "container") do
    test(s"LAYOUT-4 $name unplaced controls are not keyboard targets"):
      var activated = Vector.empty[String]
      val one       = button("one") { activated :+= "one" }.key("one")
      val two       = button("two") { activated :+= "two" }.key("two")
      val element   = if name == "grid" then grid(Seq(Constraint.fill), Seq(Constraint.fill))(one, two)
      else container(one, two)((area, _) => Seq(area))
      val host      = ElementHost()
      draw(host, element)
      assert(!host.focusNext())
      draw(host, element)
      assert(host.focusedKey.contains("one"))
      assert(!host.focusToKey("two"))
      assert(press(host, KeyCode.Enter))
      assert(activated == Vector("one"))

  test("LAYOUT-4 an unplaced window cannot capture arrangement keys"):
    val host       = ElementHost()
    val state      = w.WindowStack().window(0, 0, 12, 4)
    state.mode = w.WindowMode.Moving
    val view: View =
      container(window("hidden", state)(text("Q")), button("visible") {})((area, _) => Seq(Rect.Zero, area))
    draw(host, view)
    val before     = state.rect
    assert(!press(host, KeyCode.Right))
    assert(state.rect == before)

  test("LAYOUT-6 an explicit frame style obeys an ASCII glyph floor"):
    val theme        = Theme.Dark.withGlyphs(GlyphSupport.Ascii)
    val host         = ElementHost()
    val framed: View = text("Q").border(w.BorderType.Rounded, Style.fg(Color.Red))
    assert(lines(draw(host, framed, theme)).forall(_.forall(_ <= '~')))

  test("LAYOUT-6 windows degrade their border and resize grip under an ASCII theme"):
    val theme          = Theme.Dark.withGlyphs(GlyphSupport.Ascii)
    val host           = ElementHost()
    val state          = w.WindowStack().window(0, 0, 20, 5)
    val floating: View = window("title", state)(text("Q"))
    for mode <- Seq(w.WindowMode.Normal, w.WindowMode.Moving, w.WindowMode.Resizing) do
      state.mode = mode
      val frame = lines(draw(host, floating, theme))
      assert(frame.forall(_.forall(_ <= '~')))
      assert(frame.exists(_.contains("Q")))

  test("LAYOUT-10 custom container chrome retains its own frame color"):
    val host  = ElementHost()
    val frame = draw(
      host,
      container(text("Q"))(
        (area, _) => Seq(area.inset(1)),
        chrome = Some(w.Block()),
      ).fg(Color.Red),
    )
    assert(frame.get(0, 0).style.fg.isEmpty)
    assert(frame.get(1, 1).style.fg.contains(Color.Red))

  test("LAYOUT-11 a custom container can measure its horizontal Auto allocation"):
    val host    = ElementHost()
    host.enableBoundsRecording()
    val content = container(text("Q"))(
      (area, _) => Seq(area),
      measureWidth = (height, kids) => kids.head.intrinsicWidth(height).map(_ + 3),
    ).auto.key("custom")
    draw(host, row(content, text("after").key("after")))
    assert(host.boundsOf("custom").exists(_.width == 4))
    assert(host.boundsOf("after").exists(_.x == 4))

  test("LAYOUT-12 hosted lazy misses resolve once before painting and becoming interactive"):
    val host       = ElementHost()
    val state      = LazyListState()
    val scrolling  = ScrollViewState()
    var activated  = Vector.empty[Int]
    val view: View =
      scrollView(lazyList(1000, 1, state)(i => button(s"row $i") { activated :+= i }.key(s"row$i")), scrolling)
        .followFocus(false)
    draw(host, view)
    scrolling.offset = 300
    val jumped     = draw(host, view)
    assert(!lines(jumped).head.contains("row 300"))
    assert(!host.focusToKey("row300"))
    assert(host.pollStates())
    val resolved   = draw(host, view)
    assert(lines(resolved).head.contains("row 300"))
    assert(host.focusToKey("row300"))
    draw(host, view)
    assert(press(host, KeyCode.Enter))
    assert(activated == Vector(300))

  test("STANDARDS-4 grid coordinates have named row and column fields through the public props"):
    val element                    = text("Q").at(2, 3)
    val position: Option[GridCell] = element.props.gridSpot.position
    assert(position.contains(GridCell(row = 2, col = 3)))
    assert(position.exists(cell => cell.row == 2 && cell.col == 3))

  test("LAYOUT-2 lazy children inherit content styles after owed resolution"):
    val host       = ElementHost()
    val scrolling  = ScrollViewState()
    val state      = LazyListState()
    val view: View = scrollView(lazyList(1000, 1, state)(_ => text("Q")), scrolling).fg(Color.Red)
    draw(host, view)
    scrolling.offset = 300
    val missed     = draw(host, view)
    assert(missed.get(0, 0).symbol != "Q")
    assert(host.pollStates())
    val frame      = draw(host, view)
    assert(frame.get(0, 0).symbol == "Q")
    assert(frame.get(0, 0).style.fg.contains(Color.Red))

  test("LAYOUT-4 a roving group remains a Tab stop when its remembered member is not painted"):
    val host          = ElementHost()
    val view: View    = column(
      toolbar(
        button("New") {}.key("New"),
        button("Open") {}.key("Open"),
        button("Save") {}.key("Save"),
        button("Quit") {}.key("Quit"),
      ).length(1),
      button("after") {}.key("after"),
    )
    val buffer        = Buffer(Rect(0, 0, 20, 3))
    def frame(): Unit = host.render(buffer.area, buffer, Theme.Dark, view)
    frame()
    (1 to 3).foreach { _ => assert(press(host, KeyCode.Right)); frame() }
    assert(host.focusedKey.contains("Quit"))
    assert(host.focusNext())
    frame()
    assert(host.focusedKey.contains("after"))
    assert(host.focusNext())
    frame()
    assert(host.focusedKey.contains("New"))

  test("LAYOUT-13 descendant-declined navigation reaches lazy-list selection before outer handlers"):
    val host       = ElementHost()
    val state      = LazyListState()
    var bubbled    = 0
    val view: View = column(lazyList(100, 1, state)(i => button(s"row $i") {}.key(s"row$i")))
      .onKeyEvent { _ => bubbled += 1; false }
    draw(host, view)
    assert(host.focusToKey("row2"))
    draw(host, view)
    assert(press(host, KeyCode.PageDown))
    assert(state.selected.contains(6))
    assert(bubbled == 0)

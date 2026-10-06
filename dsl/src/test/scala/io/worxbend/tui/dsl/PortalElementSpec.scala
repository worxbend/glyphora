package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions
import io.worxbend.tui.widgets.{MenuEntry, MenuState, TextInputState}

import org.scalatest.funsuite.AnyFunSuite

/** What `portal(…)` is for: an overlay anchored inside a pane that has to paint past the pane's border.
  *
  * Every case here drives a real [[TuiApp]] through `Pilot`, because a portal only escapes its container once a frame
  * root has collected and drawn it — rendering the element by hand exercises only the in-place fallback, which is
  * itself the subject of one deliberate case below.
  */
final class PortalElementSpec extends AnyFunSuite with PilotFixture:

  /** An app with a two-line panel and an overlay anchored inside it, wide and tall enough to run past the border. */
  private final class PanelApp(useEscapingPortal: Boolean) extends TuiApp:
    def view(using ReactiveScope, Theme): Element =
      val overlay =
        if useEscapingPortal then portal(dx = 2, dy = 1, width = 12, height = 3)(text("OVERLAYXX"))
        else positioned(dx = 2, dy = 1, width = 12, height = 3)(text("OVERLAYXX"))
      panel("Pane")(layers(text("body"), overlay))

  test("a portal paints over the panel border its positioned equivalent is clipped by"):
    val clipped  = framesOf(useEscapingPortal = false)
    val escaping = framesOf(useEscapingPortal = true)
    // the terminal is 12 columns wide, so the panel's right border sits at column 11 while the overlay, anchored two
    // columns into the panel and twelve wide, wants columns 3 to 14
    assert(clipped.exists(_.contains("OVERLAY")))
    assert(escaping.exists(_.contains("OVERLAYXX")))
    // the clipped one loses the tail past the border; the escaping one keeps it and overwrites the border cell
    assert(!clipped.exists(_.contains("OVERLAYXX")))

  private def framesOf(useEscapingPortal: Boolean): Seq[String] =
    val backend = HeadlessBackend(Size(12, 6))
    val app     = PanelApp(useEscapingPortal)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val lines   = pilot.screenLines
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    lines

  test("a portal in the left pane draws over the right pane's content"):
    val backend = HeadlessBackend(Size(24, 4))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        row(
          layers(text("left"), portal(dx = 4, dy = 0, width = 12, height = 1)(text("OVEROVEROVER"))),
          text("rightrightri"),
        )
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val top     = pilot.screenLines.head
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    // the left pane owns columns 0..11, so the portal's twelve columns start at 4 and run four columns into the right
    // pane, painting over the head of its text
    assert(top.startsWith("leftOVEROVEROVER"))
    assert(!top.contains("rightright"))

  test("a portal running off the terminal draws the part that fits and does not throw"):
    val backend = HeadlessBackend(Size(10, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        layers(text("base"), portal(dx = 7, dy = 2, width = 20, height = 8)(text("ABCDEFGH")))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val lines   = pilot.screenLines
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    assert(lines.size == 3)
    assert(lines(2).endsWith("ABC")) // three columns are all that is left of the terminal

  test("rendered outside a frame a portal falls back to the clipped in-place behaviour"):
    val area                         = Rect(0, 0, 8, 3)
    def paint(node: Element): Buffer =
      val buffer = Buffer(area)
      node.widget.render(area, buffer)
      buffer
    val viaPortal                    = paint(portal(1, 1, 20, 1)(text("hello world")))
    val viaPositioned                = paint(positioned(1, 1, 20, 1)(text("hello world")))
    BufferAssertions.assertEquals(viaPortal, viaPositioned, "portal fallback matches positioned")

  test("a portal inside portal content draws above the portal that contains it"):
    val backend = HeadlessBackend(Size(16, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        layers(
          text("base"),
          portal(dx = 0, dy = 1, width = 16, height = 1)(
            layers(text("OUTEROUTEROUTER"), portal(dx = 5, dy = 0, width = 6, height = 1)(text("INNER")))
          ),
        )
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val middle  = pilot.screenLines(1)
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    assert(middle.startsWith("OUTERINNEROUTER")) // the inner portal wins the five columns it was anchored at

  test("portal nesting deeper than the round cap stops draining instead of spinning the render thread"):
    val depth                      = 12 // deeper than MaxPortalRounds, so the tail must be dropped rather than drawn
    val backend                    = HeadlessBackend(Size(12, depth + 1))
    // each level draws its own label one row lower and opens the next level as a portal of its own
    def level(index: Int): Element =
      val label = text(s"L$index")
      if index == depth - 1 then label
      else layers(label, portal(dx = 0, dy = 1, width = 12, height = 1)(level(index + 1)))
    val app                        = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        layers(text(""), portal(dx = 0, dy = 0, width = 12, height = 1)(level(0)))
    val pilot                      = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val lines                      = pilot.screenLines
    pilot.interrupt()
    val _                          = pilot.awaitTermination()
    (0 until 8).foreach(index => assert(lines(index).startsWith(s"L$index")))
    assert(lines(8).trim.isEmpty) // the ninth round never runs, which is what keeps the frame finite

  test("a menu inside a portal is clickable at the coordinates it was drawn at"):
    val backend  = HeadlessBackend(Size(30, 8))
    val state    = MenuState()
    var picked   = -1
    val app      = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        panel("Pane")(
          layers(
            text("body"),
            portal(dx = 0, dy = 0, width = 12, height = 4)(
              menu(Seq(MenuEntry.Item("first"), MenuEntry.Item("second")), state)(index => picked = index)
            ),
          )
        ).length(4)
    val pilot    = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    // the menu is drawn at the panel's inner top-left, which is screen (1, 1); the second entry is one row below
    val menuText = pilot.screenText
    assert(menuText.contains("second"))
    val secondY  = pilot.screenLines.indexWhere(_.contains("second"))
    val secondX  = pilot.screenLines(secondY).indexOf("second")
    pilot.click(secondX, secondY).waitForIdle()
    pilot.interrupt()
    val _        = pilot.awaitTermination()
    assert(picked == 1)

  test("focus traversal reaches an input inside a portal"):
    val backend = HeadlessBackend(Size(30, 6))
    val outside = TextInputState()
    val inside  = TextInputState()
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          input(outside),
          layers(text("body"), portal(dx = 0, dy = 1, width = 20, height = 1)(input(inside))),
        )
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    pilot.press("tab").waitForIdle()
    pilot.typeText("b").waitForIdle()
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    assert(outside.value == "a")
    assert(inside.value == "b")

  test("a portal captures the pointer over a sibling focusable it was painted on top of"):
    val backend = HeadlessBackend(Size(24, 6))
    val state   = MenuState()
    var picked  = -1
    var pressed = false
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        row(
          layers(
            text("left"),
            portal(dx = 0, dy = 0, width = 22, height = 4)(
              menu(Seq(MenuEntry.Item("first"), MenuEntry.Item("second")), state)(index => picked = index)
            ),
          ),
          column(button("B") { pressed = true }, text("x")),
        )
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    // the portal is 22 columns wide, so its menu covers the right pane's button; column 14 is inside both, and
    // row 2 is the menu's second entry
    val secondY = pilot.screenLines.indexWhere(_.contains("second"))
    pilot.click(14, secondY).waitForIdle()
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    // the click belongs to what the user can see, which is the portal — not to the button hidden underneath it
    assert(!pressed, "the button under the portal must not receive a click the portal covers")
    assert(picked == 1, "the portal's own menu entry is what was clicked")

  test("a portal running off the left of the terminal is clipped rather than shifted"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        layers(text("base"), portal(dx = -5, dy = 0, width = 10, height = 1)(text("ABCDEFGHIJ")))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val top     = pilot.screenLines.head
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    // five columns ran off the left edge, so the five characters that were drawn there are gone and what
    // survives is the tail of the content, still in the columns it was laid out in
    assert(top.startsWith("FGHIJ"), s"expected the clipped tail, got '$top'")

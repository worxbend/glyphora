package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** Containers and structural nodes: how children are arranged, layered, scrolled, isolated and reused. */
object LayoutPages:

  private val Family = "Layout"

  private def page(factory: String, summary: String)(view: View): GalleryPage =
    GalleryPage(factory, Family, summary, view)

  private def boxed(label: String): View = panel(text(label)).rounded

  /** A subtree that fails while it is built — what `errorBoundary` exists to contain. */
  private def broken: Element = throw IllegalStateException("broken widget")

  def all(): Vector[GalleryPage] =
    val split    = Signal(40)
    val scroll   = ScrollViewState()
    val lazily   = LazyListState()
    val longText = (1 to 30).map(i => s"line $i of a view taller than its viewport").mkString("\n")
    val counter  = Signal(0)
    Vector(
      page("column", "children stacked top to bottom")(column(boxed("first"), boxed("second"), boxed("third"))),
      page("row", "children side by side")(row(boxed("left").fill, boxed("middle").fill, boxed("right").fill)),
      page("spacer", "empty space that pushes siblings apart")(
        row(text("start").length(5), spacer, text("end").length(3))
      ),
      page("panel", "a bordered, titled box")(panel("Settings")(text("theme: dark"), text("font: mono")).rounded),
      page("layers", "overlays painted on top of a base view")(
        layers(text("base view underneath " * 10), positioned(4, 1, 20, 3)(panel(text("overlay")).rounded))
      ),
      page("positioned", "content placed at an offset inside the parent")(
        layers(spacer, positioned(10, 2, 24, 3)(panel(text("at (10, 2)")).rounded))
      ),
      page("portal", "content painted relative to the screen, above the tree")(
        column(text("the portal escapes this column"), portal(20, 4, 22, 3)(panel(text("portal")).rounded))
      ),
      page("container", "a layout written by the application")(
        container(boxed("A"), boxed("B"), boxed("C"))((area, children) =>
          children.map { child =>
            val i = child.index
            Rect(area.x + i * 8, area.y + i * 2, 14, 3)
          }
        )
      ),
      page("dock", "panes docked to the edges around a centre")(
        dock(
          top = text("── top ──").length(1),
          bottom = text("── bottom ──").length(1),
          left = text("L").length(1),
          right = text("R").length(1),
        )(text("centre").fill)
      ),
      page("flow", "children wrapped onto as many rows as they need")(
        flow(gap = 1, rowGap = 1)((1 to 12).map(i => text(s"[chip $i]"))*)
      ),
      page("grid", "a two-dimensional track layout")(
        grid(Seq(Constraint.Fill(1), Constraint.Fill(1)), Seq(Constraint.Length(3), Constraint.Length(3)), gap = 1)(
          boxed("one"),
          boxed("two"),
          boxed("three"),
          boxed("four"),
        )
      ),
      page("responsive", "a view that picks its shape from the space it gets")(
        responsive(size =>
          if size.width >= 40 then row(boxed("wide: side").fill, boxed("by side").fill)
          else column(boxed("narrow"), boxed("stacked"))
        )
      ),
      page("splitPane", "two panes with a draggable divider")(
        splitPane(boxed("first pane").fill, boxed("second pane").fill, split)
      ),
      page("scrollView", "a viewport over content taller than itself")(scrollView(text(longText), 30, scroll)),
      page("lazyList", "a list that builds only the rows on screen")(
        lazyList(10_000, 1, lazily)(i => text(s"row $i of 10 000"))
      ),
      page("focusGroup", "arrow keys move between the members, Tab leaves")(
        focusGroup(button("one")(()), button("two")(()), button("three")(()))
      ),
      page("each", "a keyed list of children, one per item")(
        column(each(Seq("alpha", "beta", "gamma"))(identity)(name => text(s"• $name")).toSeq*)
      ),
      page("memo", "a subtree rebuilt only when its inputs change")(
        memo(counter.peek)(panel(text(s"built for counter = ${counter.peek}")).rounded)
      ),
      page("errorBoundary", "a subtree that throws is replaced by its fallback")(
        errorBoundary(error => notice(s"could not draw: ${error.getMessage}", NoticeLevel.Error))(
          broken
        )
      ),
      page("widget", "any raw widget wrapped as an element")(
        widget((area, buffer) => buffer.setString(area.x, area.y, "painted by a raw Widget", Style.Default.bold))
      ),
      page("interactiveWidget", "a raw widget with key, paste and mouse handlers")(
        interactiveWidget(
          counter,
          InteractiveHandlers[Signal[Int]](onKey = (state, _) => { state.update(_ + 1); true }),
        )((state, _) =>
          (area, buffer) => buffer.setString(area.x, area.y, s"keys pressed: ${state.peek}", Style.Default)
        )
      ),
      page("clear", "blanks its area before an overlay paints")(
        layers(text(Seq.fill(6)("#" * 64).mkString("\n")), positioned(8, 1, 20, 3)(clear()))
      ),
      page("rule", "a horizontal divider with an optional label")(column(text("above"), rule("section"), text("below"))),
    )

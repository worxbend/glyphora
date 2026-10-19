package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Color, Position, Rect}
import io.worxbend.tui.runtime.{A11yAudit, A11yFinding, A11yRule}
import io.worxbend.tui.widgets.{Image, PixelImage}

/** The accessibility lint behind `pilot.a11yReport(app)` (research idea I4). Four rules over the last frame:
  *
  *   - [[A11yRule.NoFocusCue]] — when the tab order holds two or more controls, each focusable on screen is painted
  *     twice into a scratch buffer, focused and not, and flagged when the two are identical: the theme's focus cue
  *     never reached it. Scrollables are exempt — the collections of the list role (`list`, `tree`, `menu` and kin),
  *     `dataTable`, `treeTable`, `log`, `hexViewer`, `diffView`, `splitPane` and `scrollView` — because by design they
  *     take no whole-body focus styling: their selection highlight or scroll position is the cue, and it paints the
  *     same either way, so the rule cannot judge it.
  *   - [[A11yRule.LowContrast]] — every printed cell whose foreground and background are both set is measured with
  *     `Color.contrastRatio`, and each colour pair below [[MinimumContrast]] is reported once with the cells it covers.
  *     That is what makes colour-only status ("red means failed") unreadable to anyone who cannot tell the hue apart. A
  *     cell left on the terminal's default foreground or background is skipped: its colour is the user's choice and not
  *     knowable here. So is a cell inside a picture (`pixelImage`, `image`): its colours are two pixels, not text.
  *   - [[A11yRule.MissingCaret]] — the focused node is a text input that moves the hardware caret, yet the frame parked
  *     none.
  *   - [[A11yRule.ModalWithoutEscape]] — the modal on top of the screen stack neither closes on `Esc` (its
  *     `Dismissal`), nor binds the close-modal key among its screen bindings, nor shows a built-in dialog that handles
  *     `Esc` itself. A modal that handles `Esc` only inside an `onKeyEvent` handler cannot be seen from here; give it
  *     `dismissal = Dismissal.Escape` or an `esc` binding and the lint — and the help overlay — can.
  *
  * It is a lint: an empty report means none of these four failures was found, not that the app is accessible.
  *
  * '''Threads.''' Render-thread only: it walks the decorated tree of the frame the app last painted.
  */
private[dsl] object A11yLint:

  /** WCAG's minimum for user-interface components and large text. Body text should aim for 4.5; terminal cells are one
    * weight and one size, so the lint holds the floor rather than guessing which cells are "large".
    */
  val MinimumContrast: Double = 3.0

  /** The instance `Pilot.a11yReport` resolves for every `TuiApp`, through `TuiApp`'s companion. */
  val audit: A11yAudit[TuiApp] = (app, frame, cursor) =>
    app.currentHost.fold(Vector.empty[A11yFinding]) { host =>
      val nodes = Inspector.nodes(host)
      focusCues(nodes) ++ contrast(frame, pictures(nodes)) ++ caret(nodes, cursor) ++ modal(app, host)
    }

  /** Only where there is a choice to see: a lone focusable — a confirm dialog's own key handling, a single editor — is
    * where every key goes whatever it looks like.
    */
  private def focusCues(nodes: Vector[InspectedNode]): Vector[A11yFinding] =
    val focusables = nodes.filter(_.focusIndex.isDefined)
    if focusables.size < 2 then Vector.empty
    else
      focusables.flatMap { inspected =>
        for
          _    <- inspected.focusIndex
          if !cueIsSelection(inspected.node)
          rect <- inspected.layout.filterNot(_.isEmpty)
          if paint(inspected.node, rect, focused = true).diff(paint(inspected.node, rect, focused = false)).isEmpty
        yield A11yFinding(
          A11yRule.NoFocusCue,
          s"${describe(inspected.node)} at ${show(rect)} looks the same focused and unfocused",
          Some(rect),
        )
      }

  /** The scrollables whose documented focus cue is their selection or scroll position rather than a restyle — the nodes
    * that apply no whole-body focus style on purpose. A new scrollable node with the same convention joins here.
    */
  private def cueIsSelection(node: Element): Boolean =
    node match
      case _: ScrollViewElement | _: LogElement | _: DataTableElement[?] | _: SplitPaneElement | _: HexViewerElement |
          _: TreeTableElement[?] | _: DiffViewElement | _: LogConsoleElement =>
        true
      case other => other.componentRole.contains(ListRole)

  private def paint(node: Element, rect: Rect, focused: Boolean): Buffer =
    val buffer = Buffer(rect)
    val props  = node.props
    node.withProps(props.copy(focusState = props.focusState.copy(focused = focused))).widget.render(rect, buffer)
    buffer

  /** Where pictures were painted: a `pixelImage` or `image` node's half-block cells pair two neighbouring *pixels* as
    * foreground and background, so their contrast says nothing about whether text can be read.
    */
  private def pictures(nodes: Vector[InspectedNode]): Vector[Rect] =
    nodes.flatMap { inspected =>
      inspected.node.widget match
        case _: PixelImage | _: Image | _: PicturePainter => inspected.layout
        case _                                            => None
    }

  private def contrast(frame: Buffer, exempt: Vector[Rect]): Vector[A11yFinding] =
    val low = Vector.newBuilder[(Color, Color, Position)]
    frame.foreach { (x, y, cell) =>
      if !cell.isBlank && !exempt.exists(_.contains(x, y)) then
        for
          fg <- cell.style.fg.filterNot(_ == Color.Reset)
          bg <- cell.style.bg.filterNot(_ == Color.Reset)
          if Color.contrastRatio(fg, bg) < MinimumContrast
        do low += ((fg, bg, Position(x, y)))
    }
    low
      .result()
      .groupBy((fg, bg, _) => (fg, bg))
      .toVector
      .sortBy((_, cells) => (cells.head._3.y, cells.head._3.x))
      .map { case ((fg, bg), cells) =>
        val positions = cells.map(_._3)
        val area      = boundingBox(positions)
        A11yFinding(
          A11yRule.LowContrast,
          s"${positions.size} cells of $fg on $bg at ${show(area)} have contrast ${ratio(Color.contrastRatio(fg, bg))}:1, " +
            s"below ${ratio(MinimumContrast)}:1",
          Some(area),
        )
      }

  /** One decimal place, under `Locale.ROOT` so the message reads the same on every machine. */
  private def ratio(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

  private def boundingBox(cells: Vector[Position]): Rect =
    val left   = cells.map(_.x).min
    val top    = cells.map(_.y).min
    val right  = cells.map(_.x).max
    val bottom = cells.map(_.y).max
    Rect(left, top, right - left + 1, bottom - top + 1)

  private def caret(nodes: Vector[InspectedNode], cursor: Option[Position]): Vector[A11yFinding] =
    if cursor.isDefined then Vector.empty
    else
      nodes.collect {
        case inspected
            if inspected.node.props.focused && isTextInput(inspected.node) && inspected.layout.isDefined &&
              inspected.node.props.caretStyle.moves =>
          A11yFinding(
            A11yRule.MissingCaret,
            s"focused ${describe(inspected.node)} parked no terminal cursor",
            inspected.layout,
          )
      }

  private def isTextInput(node: Element): Boolean =
    node match
      case _: PromptComposerElement => true
      case other                    => other.componentRole.contains(InputRole)

  private def modal(app: TuiApp, host: ElementHost): Vector[A11yFinding] =
    app.topScreenNow.filter(_.presentation == Presentation.Modal).toVector.flatMap { screen =>
      val closeKey  = app.frameworkKeys.closeModal
      val bound     = screen.bindings.bindings.exists(_.triggers.exists(_.keys == Vector(closeKey)))
      val dialog    =
        Inspector.nodes(host).exists(inspected => !inspected.node.props.inert && handlesEscape(inspected.node))
      val escapable = screen.dismissal.byEscape || bound || dialog
      Option.unless(escapable)(
        A11yFinding(
          A11yRule.ModalWithoutEscape,
          s"modal ${screen.label.getOrElse("<unlabelled>")} has no Esc: no Dismissal.Escape and no esc binding",
        )
      )
    }

  /** Whether `node` is a built-in dialog whose own key handling closes it on `Esc`. */
  private def handlesEscape(node: Element): Boolean =
    node match
      case _: ConfirmDialogElement => true
      case _                       => false

  private def describe(node: Element): String =
    (Vector(node.debugName) ++ node.props.focusKey.map(key => s"key=$key")).mkString(" ")

  private def show(rect: Rect): String = s"${rect.x},${rect.y} ${rect.width}x${rect.height}"

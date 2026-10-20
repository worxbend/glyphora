package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Constraint, Text}
import io.worxbend.tui.widgets as w

/** The app-chrome presets: top bar, status bar, sidebar, scaffold, help overlay, and layout helpers. All of them are
  * plain element builders over the ambient [[Theme]] — nothing here bypasses the widget layer.
  */

/** A one-row title bar over the theme surface: title left, optional tabs center, optional right-side text. */
def topBar(title: String, tabs: Seq[String] = Seq.empty, selectedTab: Int = 0, right: String = "")(using
    ambient: Theme
): Element = LateBinding.composite(ambient) { theme =>
  // the reserved cells must be *display* columns, or a CJK/emoji title asks for fewer cells than it renders into.
  // the gaps are `spacer`s, not spaces baked into the strings: a spacer inside the `FilledElement` still paints the
  // surface, and unlike " $title " it cannot drift out of step with the `.length(...)` beside it
  val parts = Seq.newBuilder[Element]
  parts += Element.spacer(1)
  parts += Element.text(title).styled(_ => theme.surface.bold).length(CharWidth.of(title))
  parts += Element.spacer(1)
  if tabs.nonEmpty then
    val tabsWidth = tabs.map(CharWidth.of).sum + (tabs.size - 1) * 3 // " │ " between titles
    parts += Element.spacer(2)
    parts += Element.tabs(tabs, selectedTab).styled(_ => theme.surface).length(tabsWidth)
  parts += Element.spacer
  if right.nonEmpty then
    parts += Element.text(right).styled(_ => theme.surface).length(CharWidth.of(right))
    parts += Element.spacer(1)
  FilledElement(Element.row(parts.result()*), theme.surface).length(1)
}

/** A one-row status bar of `key description` hints over the theme surface. */
def statusBar(hints: Seq[(String, String)])(using ambient: Theme): Element = LateBinding.composite(ambient) { theme =>
  val content = hints.map((key, description) => s"$key $description").mkString("  │  ")
  // a leading `spacer`, not a leading space in the string — see `topBar`
  val row     = Element.row(Element.spacer(1), Element.text(content).styled(_ => theme.surface).fill)
  FilledElement(row, theme.surface).length(1)
}

/** Status bar fed directly from the app's declared [[KeyBindings]]: the live hints, led by the current mode as
  * `[normal]` when the bindings declare modes. While a key sequence is half typed the hints give way to the pending
  * prefix as `g …` followed by the keys that may come next, so the bar answers "what now?" even with the which-key
  * popup switched off.
  */
def statusBar(bindings: KeyBindings)(using Theme): Element =
  val mode  = Option.when(bindings.modes.nonEmpty)(bindings.mode).flatten.map(id => (s"[$id]", ""))
  val hints = bindings.pending match
    case Some(prefix) =>
      (s"${prefix.spelling} ${Messages.active.whichKeyEllipsis}", "") +: bindings
        .continuations(prefix.keys)
        .map(hint => (hint.keys, hint.description))
    case None         => bindings.hints
  statusBar(mode.toSeq ++ hints)

/** Which edge of the content a [[Sidebar]] sits against. */
enum Side:
  case Left, Right

/** Vertical placement of a block inside the area it is drawn in — the down-the-screen counterpart of [[Alignment]]. */
enum VerticalAlignment:
  case Top, Middle, Bottom

/** Sidebar configuration for [[scaffold]]. */
final case class Sidebar(content: Element, width: Int = 24, side: Side = Side.Left)

def sidebar(content: Element, width: Int = 24, side: Side = Side.Left): Sidebar =
  Sidebar(content, width, side)

/** The application shell: optional top bar, optional sidebar (left or right of the content), the content filling the
  * middle, and an optional status bar.
  */
def scaffold(
    topBar: Option[Element] = None,
    sidebar: Option[Sidebar] = None,
    statusBar: Option[Element] = None,
)(content: Element): Element =
  val middle = sidebar match
    case None       => content.fill
    case Some(pane) =>
      val sideElement = pane.content.length(pane.width)
      val mainElement = content.fill
      val ordered     = pane.side match
        case Side.Left  => Seq(sideElement, mainElement)
        case Side.Right => Seq(mainElement, sideElement)
      Element.row(ordered*).fill
  val rows   = topBar.toSeq ++ Seq(middle) ++ statusBar.toSeq
  Element.column(rows*)

/** Cells between a key label and its description in [[helpOverlay]], and the width past which a long key spec stops
  * widening the key column (a longer one runs into its own description rather than pushing the whole table wider).
  */
private val HelpColumnGap    = 3
private val MaxHelpKeyColumn = 24

/** A centered help dialog listing every hinted binding — render it last (over the view) while visible. */
def helpOverlay(bindings: KeyBindings, title: String = Messages.active.helpTitle)(using ambient: Theme): Element =
  LateBinding.composite(ambient)(theme => helpOverlayIn(bindings, title, theme))

private def helpOverlayIn(bindings: KeyBindings, title: String, theme: Theme): Element =
  // bindings with modes list every mode, each under a `name:` heading, after the mode-less ones — the overlay is where
  // a user learns what the other modes do, so it is not narrowed to the live one the way the status bar is
  val sections = bindings.helpSections
  val rows     = sections.flatMap(_.hints)
  // display columns, not UTF-16 lengths: a binding labelled with a CJK or emoji key otherwise asks for fewer cells
  // than it renders into and the second column stops lining up
  val width    = rows
    .map(hint => CharWidth.of(hint.keys) + CharWidth.of(hint.description) + HelpColumnGap)
    .maxOption
    .getOrElse(4)
  val column   = math.min(width, MaxHelpKeyColumn)
  val lines    = sections.flatMap { section =>
    section.mode.map(mode => s"$mode:").toSeq ++ section.hints.map { hint =>
      hint.keys + " " * math.max(0, column - CharWidth.of(hint.keys)) + hint.description
    }
  }
  Element.widget(
    w.Dialog(title, Text.raw(lines.mkString("\n")), buttons = Seq.empty, style = theme.primary)
  )

// ---- layout presets ----

/** Side pane + main pane.
  *
  * The pane is `pane`, not `side`, because [[Side]] and `Sidebar.side` fifty lines above already own that word for
  * *which edge*; one name for two ideas in one file is a reading trap.
  */
def sidebarLayout(pane: Element, main: Element, paneWidth: Int = 24): Element =
  Element.row(pane.length(paneWidth), main.fill)

/** The classic list-left, detail-right split. */
def masterDetail(master: Element, detail: Element, masterWidth: Int = 30): Element =
  sidebarLayout(master, detail, masterWidth)

/** `content` laid over a backdrop that reports a mouse press no part of `content` took, so a dialog can be closed by
  * clicking away from it.
  *
  * How it works, and what it therefore means. [[EventRouter]] resolves a press against the *topmost* sibling subtree
  * that covers the pointer and never offers it to the ones underneath, and it only considers elements that recorded an
  * area — that is, elements that are focusable or carry a mouse handler of their own. `content` is the later of the two
  * layers here, so a press that lands on any of its controls is resolved there and the backdrop never hears about it. A
  * press that lands where the dialog has no control resolves to nothing inside `content` and falls through to the
  * backdrop, which is this node itself.
  *
  * What the backdrop then does is decided by where the frame painted things. The host records the painted rect of every
  * node (see `NodeBounds`), and a press that lands on a node of `content` that draws something — the dialog's border,
  * its caption, a label inside it — is *inside* the dialog and is absorbed; a press that lands only on nodes that
  * arrange space and draw nothing — the `row`, `column` and `spacer`s that `centered` places the dialog with — is
  * outside it and calls `onOutsidePress`. So "outside" means "outside the dialog's frame", which is what the user sees:
  * the frame of the thing being used is part of the thing, and used to count as outside it because a border carries no
  * control.
  *
  * A dialog that wants to absorb presses on blank cells it owns but does not draw — a borderless `column` of labels,
  * say — still says so at its root, exactly as before:
  *
  * {{{
  * column(text("Really?"), button("OK")(ok())).onMouseEvent(_ => true)
  * }}}
  *
  * Recording is switched on by this node's presence in the tree: a frame rendered before recording started (the first
  * frame of a hand-layered overlay on a real terminal) falls back to treating every press that reaches the backdrop as
  * outside, which is the old behaviour. `TuiApp` wires this for any modal `Screen` whose [[Dismissal]] includes a click
  * outside and records from the frame that first shows it; call it directly only for a dialog an application layers
  * itself.
  */
def dismissibleOverlay(content: Element)(onOutsidePress: () => Unit): Element =
  DismissibleOverlayElement(content, onOutsidePress)

/** `content` at a fixed size, centered both ways in whatever space is available. */
def centered(width: Int, height: Int)(content: Element): Element =
  place(width, height)(content)

/** Positions `content` (sized `width` x `height`) inside whatever area it is given, aligned `horizontal` x `vertical`
  * (centered on both axes by default — the [[centered]] case). Pass `backdrop` to paint the surrounding whitespace with
  * a style (Lip Gloss `Place`-style), e.g. a dimmed area behind a dialog.
  *
  * The parameter is `backdrop`, not `fill`, because `Element.fill` already means something else in every snippet this
  * appears in: there it is the layout extension that claims a row's or column's leftover space.
  *
  * Each axis is named in its own vocabulary: `horizontal` takes [[Alignment]] (`Left`/`Center`/`Right`, the same enum
  * `Block` titles and `Paragraph` text use) and `vertical` takes [[VerticalAlignment]] (`Top`/`Middle`/`Bottom`).
  */
def place(
    width: Int,
    height: Int,
    horizontal: Alignment = Alignment.Center,
    vertical: VerticalAlignment = VerticalAlignment.Middle,
    backdrop: Option[Style] = None,
)(content: Element): Element =
  // a `spacer` before the block pushes it off the near edge, one after it off the far edge, and both together centre it
  def bracket(align: Alignment | VerticalAlignment, block: Element): Seq[Element] =
    align match
      case Alignment.Left | VerticalAlignment.Top      => Seq(block, Element.spacer)
      case Alignment.Center | VerticalAlignment.Middle => Seq(Element.spacer, block, Element.spacer)
      case Alignment.Right | VerticalAlignment.Bottom  => Seq(Element.spacer, block)
  val sized = content.withProps(content.props.copy(constraint = Some(Constraint.Length(width))))
  val row    = Element.row(bracket(horizontal, sized)*).length(height)
  val placed = Element.column(bracket(vertical, row)*)
  backdrop match
    case Some(style) => FilledElement(placed, style)
    case None        => placed

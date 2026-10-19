package io.worxbend.tui.dsl

import io.worxbend.tui.core.Style

/** A dotted, hierarchical style name — `"button"`, `"button.primary"`, `"list.selected"` — that a [[Theme]] looks its
  * [[StateStyles]] up by.
  *
  * The hierarchy is Brick's attribute-map idea, kept typed and without selectors: `"list.selected"` falls back to
  * `"list"`, and every field the child leaves unset is taken from the parent through
  * [[io.worxbend.tui.core.Style.patch]]. So a theme can say "every list is on a grey surface" once, at `list`, and the
  * selected row adds only what makes it selected. There is no matching against the element tree and no per-frame cost
  * beyond one map lookup per segment.
  *
  * A name is a value and needs no validation beyond "not blank": an empty segment (`"a..b"`) is dropped, so a name
  * always has at least one segment and its [[parent]] chain always ends.
  */
final case class StyleName private (segments: Vector[String]):

  /** The name as written, segments joined by dots. */
  def value: String = segments.mkString(".")

  /** The name one level up — `"list"` for `"list.selected"` — or `None` at the top. */
  def parent: Option[StyleName] = Option.when(segments.size > 1)(StyleName(segments.dropRight(1)))

  /** Every name from the root down to this one: `a`, `a.b`, `a.b.c`. The order a lookup merges in. */
  def lineage: Seq[StyleName] = (1 to segments.size).map(n => StyleName(segments.take(n)))

  /** A child name: `StyleName("button") / "primary"` is `"button.primary"`. */
  def /(child: String): StyleName = StyleName(value + "." + child)

  override def toString: String = value

object StyleName:

  /** The name `value` spells. Blank segments are dropped; a wholly blank name throws, because a style name is a static
    * declaration and a typo there is a programmer error — use [[parse]] for text read at run time.
    */
  def apply(value: String): StyleName =
    parse(value).fold(message => throw IllegalArgumentException(message), identity)

  /** The name `value` spells, or a message saying why it names nothing. */
  def parse(value: String): Either[String, StyleName] =
    val segments = value.split('.').iterator.map(_.trim).filter(_.nonEmpty).toVector
    if segments.isEmpty then Left(s"'$value' is not a style name: it has no segment")
    else if segments.exists(_.exists(c => c.isWhitespace || c == '=' || c == ':' || c == '#')) then
      Left(s"'$value' is not a style name: segments are words without spaces, '=', ':' or '#'")
    else Right(new StyleName(segments))

/** The styles one component draws in, per interaction state.
  *
  * `base` is the resting style, layered *under* whatever the element itself sets (an explicit `.fg(...)` still wins).
  * The other four are layered over it while their state holds — `focused` while the element has the keyboard, `hovered`
  * while the pointer rests on it, `pressed` from a button going down on it to the release, `disabled` while it or an
  * ancestor is `.disabled`. A state left at [[io.worxbend.tui.core.Style.Default]] says nothing, and the element falls
  * back to what it drew before component styles existed: the theme's `focus` cue, the element's own
  * `.hoverStyle`/`.pressedStyle`, the theme's `disabled` style.
  *
  * The precedence when several states hold is the one the focus pass has always used: disabled replaces everything, and
  * otherwise focused, then hovered, then pressed are layered in that order.
  *
  * A plain value, read on the render thread by the DSL host; the widgets never see it — they get the resolved `Style`.
  */
final case class StateStyles(
    base: Style = Style.Default,
    focused: Style = Style.Default,
    hovered: Style = Style.Default,
    pressed: Style = Style.Default,
    disabled: Style = Style.Default,
):

  /** `other`'s choices layered over these, field by field — how a child name inherits from its parent. */
  def patch(other: StateStyles): StateStyles =
    StateStyles(
      base.patch(other.base),
      focused.patch(other.focused),
      hovered.patch(other.hovered),
      pressed.patch(other.pressed),
      disabled.patch(other.disabled),
    )

  /** Whether every state is silent — a theme with no entry for a role resolves to this. */
  def isEmpty: Boolean = this == StateStyles.Empty

object StateStyles:

  /** Every state silent: the element draws exactly as it would with no component styles at all. */
  val Empty: StateStyles = StateStyles()

  /** `style` when it says something, `None` when it is [[io.worxbend.tui.core.Style.Default]]. */
  private[dsl] def stated(style: Style): Option[Style] = Option.when(style != Style.Default)(style)

/** A typed handle on a component's [[StateStyles]] in a [[Theme]]: `theme.withComponent(ButtonRole.Primary, …)` and
  * `button("Save").variant(ButtonRole.Primary)`.
  *
  * A role is a [[StyleName]] with a type, so a variant is checked at compile time and a theme file can still name it as
  * text. The built-in roles are the enums and objects below; an application adds its own with [[ComponentRole.named]],
  * which is how a `"button.toolbar"` variant gets in without the library knowing about it.
  */
trait ComponentRole:

  /** The name the theme stores this role's styles under. */
  def styleName: StyleName

object ComponentRole:

  /** A role of the application's own, named by a dotted [[StyleName]]. */
  def named(name: String): ComponentRole = Named(StyleName(name))

  private final case class Named(styleName: StyleName) extends ComponentRole

/** The variants of `button`. `Default` is what every button resolves when no `.variant(...)` is given, so a theme that
  * styles `"button"` styles every button and `"button.primary"` only refines it.
  */
enum ButtonRole(val styleName: StyleName) extends ComponentRole:
  case Default   extends ButtonRole(StyleName("button"))
  case Primary   extends ButtonRole(StyleName("button.primary"))
  case Secondary extends ButtonRole(StyleName("button.secondary"))
  case Danger    extends ButtonRole(StyleName("button.danger"))

/** The text-entry fields — `textInput`, `passwordInput`, `textArea` and the like — under `"input"`. */
case object InputRole extends ComponentRole:
  val styleName: StyleName = StyleName("input")

/** The scrollable "pick a row" collections under `"list"`; [[ListRole.Selected]] is the selected row's highlight, which
  * falls back to the theme's `focus` cue when the theme leaves it silent.
  */
case object ListRole extends ComponentRole:
  val styleName: StyleName = StyleName("list")

  /** The selected row, `"list.selected"`, inheriting whatever `"list"` sets. */
  val Selected: ComponentRole = ComponentRole.named("list.selected")

/** The header row of a `table`, `"table.header"`; its `base` replaces the widget's own bold default when it says
  * something.
  */
case object TableHeaderRole extends ComponentRole:
  val styleName: StyleName = StyleName("table.header")

/** The modal boxes — `dialog` and `confirmDialog` — under `"dialog"`. Its `base` is layered over the theme's `primary`
  * for the frame, title and message; [[DialogRole.Selected]] is the selected button, which falls back to the theme's
  * `focus` cue (on a `dialog`) or the focus cue / resting underline (on a `confirmDialog`) when the theme leaves it
  * silent.
  */
case object DialogRole extends ComponentRole:
  val styleName: StyleName = StyleName("dialog")

  /** The selected button, `"dialog.selected"`: its `base` at rest, its `focused` style while a `confirmDialog` has the
    * keyboard. Inherits whatever `"dialog"` sets, as every hierarchical name does.
    */
  val Selected: ComponentRole = ComponentRole.named("dialog.selected")

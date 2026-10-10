package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent}
import io.worxbend.tui.runtime.{ReactiveScope, Signal}
import io.worxbend.tui.widgets.TextInputState

/** The fuzzy command palette behind [[TuiApp.openPalette]]: a filter box over the app's declared [[KeyBindings]].
  *
  * `declared` is a thunk rather than a value because `TuiApp.bindings` is a `def` an app overrides — reading it once at
  * construction would capture whatever the trait's initializer saw, which is before the app's own fields exist.
  *
  * One of these belongs to one [[TuiApp]] instance and every method runs on that app's render thread: `open`/`close`
  * from event handlers, `element` from the view evaluation.
  */
private[dsl] final class CommandPalette(declared: () => KeyBindings):

  private val opened: Signal[Boolean] = Signal(false)
  private val query: TextInputState   = TextInputState()
  private var selected: Int           = 0
  private var activation: Long        = 0L

  def activationNow: Option[Long] = Option.when(isOpenNow)(activation)

  /** Whether the palette is showing, as a tracked read — the view that asks re-evaluates when it opens or closes. */
  def isOpen(using ReactiveScope): Boolean = opened.get

  /** Whether the palette is showing, without subscribing — for the event router, which runs outside a view. */
  def isOpenNow: Boolean = opened.peek

  /** Opens the palette on an empty filter with the first match selected. */
  def open(): Unit =
    activation += 1
    query.clear()
    selected = 0
    opened.set(true)

  def close(): Unit =
    opened.set(false)

  /** The bindings whose description matches the current filter, in declaration order. */
  def matches: Seq[KeyBinding] =
    val accepts = Fuzzy.matcher(query.value)
    declared().bindings.filter(bound => accepts(bound.description))

  /** The palette panel, sized to its matches and centered over the view it layers on.
    *
    * Building the panel is also where the selection is clamped: the filter can shrink the match list under a selection
    * the user already moved, and this is the one place that sees both.
    */
  def element(using theme: Theme): Element =
    val visible = matches
    selected = math.max(0, math.min(selected, math.max(0, visible.size - 1)))
    val listing = visible.zipWithIndex.map { (bound, index) =>
      val marker = if index == selected then "> " else "  "
      val style  = if index == selected then theme.focus else theme.primary
      Element.text(s"$marker${bound.label}  ${bound.description}").styled(_ => style).length(1)
    }
    val body    = Element
      .panel("Commands")(
        (Element.input(query, placeholder = "type to filter…").length(1) +: listing)*
      )
      .styled(_ => theme.accent)
      .onKeyEvent(handleKey)
    centered(PaletteWidth, math.min(PaletteChrome + visible.size, PaletteMaxHeight))(body)

  /** The palette's own keys: `Esc` closes, `↑`/`↓` move the selection, `Enter` runs the selected command. Everything
    * else — the printable characters that drive the filter — is declined so the input box beneath sees it.
    */
  private def handleKey(key: KeyEvent): Boolean =
    key match
      case KeyEvent(KeyCode.Escape, _) =>
        close()
        true
      case KeyEvent(KeyCode.Down, _)   =>
        selected += 1
        true
      case KeyEvent(KeyCode.Up, _)     =>
        selected = math.max(0, selected - 1)
        true
      case KeyEvent(KeyCode.Enter, _)  =>
        matches.lift(selected).foreach { bound =>
          close()
          bound.action()
        }
        true
      case _                           => false

/** Dimensions of the built-in command palette overlay: how wide the panel is, how many rows its chrome costs on top of
  * the matches (the panel border plus the filter input), and the height it stops growing at.
  */
private val PaletteWidth     = 46
private val PaletteChrome    = 4
private val PaletteMaxHeight = 14

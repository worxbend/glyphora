package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers}

import scala.util.DynamicVariable

/** The keys `TuiApp` reserves for itself once nothing else claimed an event, and the PageUp/PageDown step of every
  * scrollable — each key under a [[BindingId]], so a user keymap moves them the way it moves an app's own bindings.
  *
  * A framework key fires when the pressed key is the same key holding at least the declared modifiers, so the default
  * `ctrl+p` also answers to Ctrl+Alt+P, as it always has. Each is a single key: the framework does not take part in
  * sequence dispatch. Rebinding `quit` moves the `Ctrl+C` *key event*; a terminal that delivers Ctrl+C as SIGINT still
  * quits through `onInterrupt`.
  *
  * An immutable value; `TuiApp.frameworkKeys` is read on the render thread for every key that reaches this stage.
  */
final case class FrameworkKeys(
    focusNext: KeyEvent = KeyEvent.of(KeyCode.Tab),
    focusPrevious: KeyEvent = KeyEvent(KeyCode.Tab, KeyModifiers.Shift),
    commandPalette: KeyEvent = KeyEvent(KeyCode.Char('p'), KeyModifiers.Ctrl),
    quit: KeyEvent = KeyEvent(KeyCode.Char('c'), KeyModifiers.Ctrl),
    closeModal: KeyEvent = KeyEvent.of(KeyCode.Escape),
    pageStep: Int = 10,
    inspector: KeyEvent = KeyEvent.of(KeyCode.F(12)),
):
  require(pageStep > 0, s"pageStep must be positive, was $pageStep")

  /** Each framework key with its id and a description, in the order the framework tries them. */
  def entries: Seq[(BindingId, KeyEvent, String)] = Seq(
    (FrameworkKeys.FocusPrevious, focusPrevious, "focus the previous control"),
    (FrameworkKeys.FocusNext, focusNext, "focus the next control"),
    (FrameworkKeys.CommandPalette, commandPalette, "open the command palette"),
    (FrameworkKeys.Quit, quit, "quit"),
    (FrameworkKeys.CloseModal, closeModal, "close the modal on top"),
    (FrameworkKeys.Inspector, inspector, "cycle the inspector overlay"),
  )

  /** These keys with every id in `keymap` moved to its sequence; ids that name no framework key are ignored, so one
    * keymap can serve both this and `KeyBindings.rebind`. Throws `IllegalArgumentException` for a multi-key sequence.
    */
  def rebind(keymap: Map[BindingId, KeySequence]): FrameworkKeys =
    def key(id: BindingId, current: KeyEvent): KeyEvent =
      keymap.get(id).fold(current) { sequence =>
        sequence.keys match
          case Vector(single) => single
          case _              =>
            throw IllegalArgumentException(s"framework key '$id' takes a single key, not \"${sequence.spelling}\"")
      }
    copy(
      focusNext = key(FrameworkKeys.FocusNext, focusNext),
      focusPrevious = key(FrameworkKeys.FocusPrevious, focusPrevious),
      commandPalette = key(FrameworkKeys.CommandPalette, commandPalette),
      quit = key(FrameworkKeys.Quit, quit),
      closeModal = key(FrameworkKeys.CloseModal, closeModal),
      inspector = key(FrameworkKeys.Inspector, inspector),
    )

  /** Whether `event` is `key` held with at least `key`'s modifiers. */
  def fires(key: KeyEvent, event: KeyEvent): Boolean =
    event.code == key.code && event.modifiers.hasAll(key.modifiers)

object FrameworkKeys:

  val FocusNext: BindingId      = BindingId("focus-next")
  val FocusPrevious: BindingId  = BindingId("focus-previous")
  val CommandPalette: BindingId = BindingId("command-palette")
  val Quit: BindingId           = BindingId("quit")
  val CloseModal: BindingId     = BindingId("close-modal")

  /** F12 by default: cycles the inspector overlay — node card, card plus element tree, off. */
  val Inspector: BindingId = BindingId("inspector")

  /** Tab, Shift+Tab, Ctrl+P, Ctrl+C, Esc, a page step of 10 and F12 for the inspector. */
  val default: FrameworkKeys = FrameworkKeys()

  /** The page step the key dispatch on this thread runs under. `TuiApp` binds it around each dispatch to its own
    * [[FrameworkKeys.pageStep]]; per thread, so two runners in one JVM keep their own.
    */
  private[dsl] val currentPageStep: DynamicVariable[Int] = DynamicVariable(default.pageStep)

/** User keymaps: a plain-text file of `id = spec` lines read into the `Map[BindingId, KeySequence]` that
  * `KeyBindings.rebind` and `FrameworkKeys.rebind` take, and a Markdown export of the keys an app ends up with.
  *
  * {{{
  * # ~/.config/myapp/keys
  * save      = ctrl+s
  * go-to-top = g g
  * quit      = ctrl+q
  * }}}
  */
object Keymap:

  /** Reads a keymap. Blank lines and lines whose first non-blank character is `#` are skipped; every other line is
    * `id = spec`, split at its first `=` with both sides trimmed, the spec in [[KeySequence.parse]]'s vocabulary. A
    * line that is not of that shape, a spec that does not parse, or an id given twice is a `Left` naming the line.
    */
  def parse(text: String): Either[String, Map[BindingId, KeySequence]] =
    val entries = text.linesIterator.zipWithIndex.collect {
      case (line, index) if line.trim.nonEmpty && !line.trim.startsWith("#") => (line, index + 1)
    }
    entries.foldLeft[Either[String, Map[BindingId, KeySequence]]](Right(Map.empty)) { case (acc, (line, number)) =>
      acc.flatMap { parsed =>
        line.indexOf('=') match
          case -1 => Left(s"line $number: expected 'id = keys', got '${line.trim}'")
          case at =>
            val (left, right) = line.splitAt(at)
            val id            = BindingId(left.trim)
            val spec          = right.drop(1).trim
            if id.value.isEmpty || spec.isEmpty then Left(s"line $number: expected 'id = keys', got '${line.trim}'")
            else if parsed.contains(id) then Left(s"line $number: '$id' is bound twice")
            else KeySequence.parse(spec).left.map(problem => s"line $number: $problem").map(seq => parsed + (id -> seq))
      }
    }

  /** The bindings as a Markdown table — key, action, id, and the mode when the bindings declare modes — for a help page
    * or a README. Shows the bindings as rebound, so it documents the keys the user actually has.
    */
  def markdown(bindings: KeyBindings): String = table(bindings, Seq.empty)

  /** [[markdown]] followed by the framework's own keys, listed under their ids. */
  def markdown(bindings: KeyBindings, framework: FrameworkKeys): String =
    table(bindings, framework.entries.map((id, key, description) => (None, KeySequence.show(key), description, id)))

  private def table(bindings: KeyBindings, extra: Seq[(Option[ModeId], String, String, BindingId)]): String =
    val rows   = bindings.bindings.map(bound =>
      (bound.mode, bound.triggers.map(_.spelling).mkString(", "), bound.description, bound.id)
    ) ++ extra
    val modal  = bindings.modes.nonEmpty
    val header =
      if modal then Seq("| Mode | Key | Action | Id |", "|---|---|---|---|")
      else Seq("| Key | Action | Id |", "|---|---|---|")
    val lines  = rows.map { (mode, keys, description, id) =>
      val cells = Seq(code(keys), cell(description), code(id.value))
      (if modal then cell(mode.fold("any")(_.name)) +: cells else cells).mkString("| ", " | ", " |")
    }
    (header ++ lines).mkString("", "\n", "\n")

  private def cell(text: String): String = text.replace("|", "\\|")

  private def code(text: String): String = s"`${cell(text)}`"

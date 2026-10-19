package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers}

import scala.util.DynamicVariable

/** The keys `TuiApp` reserves for itself once nothing else claimed an event, and the PageUp/PageDown step of every
  * scrollable — each key under a [[BindingId]], so a user keymap moves them the way it moves an app's own bindings.
  *
  * A framework key fires when the pressed key is the same key holding at least the declared modifiers, so the default
  * `ctrl+p` also answers to Ctrl+Alt+P, as it always has. The keys `TuiApp` handles itself — focus traversal, the
  * command palette, quit, close-modal, the inspector and copy-selection — may be sequences (`ctrl+x ctrl+c`): they take
  * part in the same pending-prefix dispatch as the app's bindings, after them, so an app binding that shares a prefix
  * wins. `menu-bar`, `window-move` and `window-resize` are read by an element while it handles one key, so each stays a
  * single key. [[KeySequence.unbound]] takes a key away (see [[unbind]]). Rebinding `quit` moves the `Ctrl+C` *key
  * event*; a terminal that delivers Ctrl+C as SIGINT still quits through `onInterrupt`.
  *
  * An immutable value; `TuiApp.frameworkKeys` is read on the render thread for every key that reaches this stage.
  */
final case class FrameworkKeys(
    focusNext: KeySequence = KeySequence.of(KeyEvent.of(KeyCode.Tab)),
    focusPrevious: KeySequence = KeySequence.of(KeyEvent(KeyCode.Tab, KeyModifiers.Shift)),
    commandPalette: KeySequence = KeySequence.of(KeyEvent(KeyCode.Char('p'), KeyModifiers.Ctrl)),
    quit: KeySequence = KeySequence.of(KeyEvent(KeyCode.Char('c'), KeyModifiers.Ctrl)),
    closeModal: KeySequence = KeySequence.of(KeyEvent.of(KeyCode.Escape)),
    pageStep: Int = 10,
    menuBar: KeySequence = KeySequence.of(KeyEvent.of(KeyCode.F(10))),
    windowMove: KeySequence = KeySequence.of(KeyEvent(KeyCode.Char('m'), KeyModifiers.Alt)),
    windowResize: KeySequence = KeySequence.of(KeyEvent(KeyCode.Char('r'), KeyModifiers.Alt)),
    inspector: KeySequence = KeySequence.of(KeyEvent.of(KeyCode.F(12))),
    copySelection: KeySequence = KeySequence.of(KeyEvent(KeyCode.Char('c'), KeyModifiers.Ctrl | KeyModifiers.Shift)),
):
  require(pageStep > 0, s"pageStep must be positive, was $pageStep")
  Seq(
    menuBar      -> FrameworkKeys.MenuBar,
    windowMove   -> FrameworkKeys.WindowMove,
    windowResize -> FrameworkKeys.WindowResize,
  )
    .foreach((sequence, id) =>
      require(sequence.steps.sizeIs <= 1, s"framework key '$id' takes a single key, not \"${sequence.spelling}\"")
    )

  /** Each framework key with its id and a description, in the order the framework tries them. The descriptions come
    * from the [[Messages]] table in force on the calling thread, so a translated app documents its keys in its own
    * language.
    */
  def entries: Seq[KeymapEntry] =
    val labels                          = Messages.active
    def describe(id: BindingId): String = labels.frameworkKeyDescription(id)
    Seq(
      KeymapEntry(FrameworkKeys.FocusPrevious, focusPrevious, describe(FrameworkKeys.FocusPrevious)),
      KeymapEntry(FrameworkKeys.FocusNext, focusNext, describe(FrameworkKeys.FocusNext)),
      KeymapEntry(FrameworkKeys.CommandPalette, commandPalette, describe(FrameworkKeys.CommandPalette)),
      KeymapEntry(FrameworkKeys.Quit, quit, describe(FrameworkKeys.Quit)),
      KeymapEntry(FrameworkKeys.CloseModal, closeModal, describe(FrameworkKeys.CloseModal)),
      KeymapEntry(FrameworkKeys.MenuBar, menuBar, describe(FrameworkKeys.MenuBar)),
      KeymapEntry(FrameworkKeys.WindowMove, windowMove, describe(FrameworkKeys.WindowMove)),
      KeymapEntry(FrameworkKeys.WindowResize, windowResize, describe(FrameworkKeys.WindowResize)),
      KeymapEntry(FrameworkKeys.Inspector, inspector, describe(FrameworkKeys.Inspector)),
      KeymapEntry(FrameworkKeys.CopySelection, copySelection, describe(FrameworkKeys.CopySelection)),
    )

  /** These keys with every id in `keymap` moved to its sequence; ids that name no framework key are ignored, so one
    * keymap can serve both this and `KeyBindings.rebind`. Throws `IllegalArgumentException` for a multi-key sequence
    * given to `menu-bar`, `window-move` or `window-resize`.
    */
  def rebind(keymap: Map[BindingId, KeySequence]): FrameworkKeys =
    def key(id: BindingId, current: KeySequence): KeySequence = keymap.getOrElse(id, current)
    copy(
      focusNext = key(FrameworkKeys.FocusNext, focusNext),
      focusPrevious = key(FrameworkKeys.FocusPrevious, focusPrevious),
      commandPalette = key(FrameworkKeys.CommandPalette, commandPalette),
      quit = key(FrameworkKeys.Quit, quit),
      closeModal = key(FrameworkKeys.CloseModal, closeModal),
      menuBar = key(FrameworkKeys.MenuBar, menuBar),
      windowMove = key(FrameworkKeys.WindowMove, windowMove),
      windowResize = key(FrameworkKeys.WindowResize, windowResize),
      inspector = key(FrameworkKeys.Inspector, inspector),
      copySelection = key(FrameworkKeys.CopySelection, copySelection),
    )

  /** These keys with each of `ids` bound to nothing ([[KeySequence.unbound]]): the action it named no longer fires —
    * `FrameworkKeys.default.unbind(FrameworkKeys.Inspector)` turns the F12 overlay off.
    */
  def unbind(ids: BindingId*): FrameworkKeys = rebind(ids.map(_ -> KeySequence.unbound).toMap)

  /** Whether `event`, pressed with no prefix pending, fires `key`: `key` is a single key and `event` is that key held
    * with at least its modifiers. A sequence never fires on one key; it goes through [[completes]].
    */
  def fires(key: KeySequence, event: KeyEvent): Boolean =
    key.keys match
      case Vector(single) => FrameworkKeys.matches(single, event)
      case _              => false

  /** Whether the keys typed so far, `typed`, are the whole of `key` — each step matched as [[fires]] matches one. */
  def completes(key: KeySequence, typed: Vector[KeyEvent]): Boolean =
    key.keys.sizeIs == typed.size && key.keys.nonEmpty && key.keys.lazyZip(typed).forall(FrameworkKeys.matches)

  /** Whether `typed` is a proper prefix of one of the framework's sequences — the dispatch keeps waiting for the rest.
    */
  def continues(typed: Vector[KeyEvent]): Boolean =
    Seq(focusPrevious, focusNext, commandPalette, quit, closeModal, inspector, copySelection).exists { key =>
      key.keys.sizeIs > typed.size && key.keys.lazyZip(typed).forall(FrameworkKeys.matches)
    }

object FrameworkKeys:

  val FocusNext: BindingId      = BindingId("focus-next")
  val FocusPrevious: BindingId  = BindingId("focus-previous")
  val CommandPalette: BindingId = BindingId("command-palette")
  val Quit: BindingId           = BindingId("quit")
  val CloseModal: BindingId     = BindingId("close-modal")
  val MenuBar: BindingId        = BindingId("menu-bar")
  val WindowMove: BindingId     = BindingId("window-move")
  val WindowResize: BindingId   = BindingId("window-resize")
  val CopySelection: BindingId  = BindingId("copy-selection")

  /** F12 by default: cycles the inspector overlay — node card, card plus element tree, off. */
  val Inspector: BindingId = BindingId("inspector")

  private def matches(key: KeyEvent, event: KeyEvent): Boolean =
    event.code == key.code && event.modifiers.hasAll(key.modifiers)

  /** Tab, Shift+Tab, Ctrl+P, Ctrl+C, Esc, a page step of 10 and F12 for the inspector. */
  val default: FrameworkKeys = FrameworkKeys()

  /** The framework keys the key dispatch on this thread runs under — what a `menuBar` reads its `menu-bar` key, a
    * `window` its arrange keys and every scrollable its page step from. `TuiApp` binds it around each dispatch to its
    * own [[TuiApp.frameworkKeys]]; an embedding host that binds nothing gets [[default]].
    */
  private[dsl] val current: DynamicVariable[FrameworkKeys] = DynamicVariable(default)

/** One framework key as [[FrameworkKeys.entries]] lists it: the id a keymap rebinds it by, the key it is bound to now
  * and what it does.
  */
final case class KeymapEntry(id: BindingId, key: KeySequence, description: String)

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
    * `id = spec`, split at its first `=` with both sides trimmed, the spec in [[KeySequence.parse]]'s vocabulary. A `#`
    * that starts a word of the spec — at its start or after a space — ends the line as a comment, so `save = ctrl+s #
    * the usual one` binds `ctrl+s`; the `#` key itself is spelled `\#` there (`mark = \#`), while a `#` inside a step
    * (`ctrl+#`) is the key already. A line that is not of that shape, a spec that does not parse, or an id given twice
    * is a [[LineError]] naming the line.
    */
  def parse(text: String): Either[LineError, Map[BindingId, KeySequence]] =
    KeyValueLines.read(text, "id = keys").flatMap { lines =>
      lines.foldLeft[Either[LineError, Map[BindingId, KeySequence]]](Right(Map.empty)) { (acc, entry) =>
        acc.flatMap { parsed =>
          val line = entry.copy(value = withoutComment(entry.value))
          val id   = BindingId(line.key)
          if id.value.isEmpty || line.value.isEmpty then
            Left(LineError(line.number, s"expected 'id = keys', found '${line.key} = ${line.value}'"))
          else if parsed.contains(id) then Left(LineError(line.number, s"'$id' is bound twice"))
          else
            KeySequence
              .parse(line.value)
              .left
              .map(problem => LineError(line.number, problem))
              .map(sequence => parsed + (id -> sequence))
        }
      }
    }

  /** `spec` up to its first comment — a `#` at the start of a word — trimmed, with each `\#` read as the `#` key. */
  private def withoutComment(spec: String): String =
    val words = spec.split("\\s+").toVector.filter(_.nonEmpty).takeWhile(word => !word.startsWith("#"))
    words.map(_.replace("\\#", "#")).mkString(" ")

  /** The bindings as a Markdown table — key, action, id, and the mode when the bindings declare modes — for a help page
    * or a README. Shows the bindings as rebound, so it documents the keys the user actually has.
    */
  def markdown(bindings: KeyBindings): String = table(bindings, Seq.empty)

  /** [[markdown]] followed by the framework's own keys, listed under their ids. */
  def markdown(bindings: KeyBindings, framework: FrameworkKeys): String =
    table(bindings, framework.entries.map(entry => (None, entry.key.spelling, entry.description, entry.id)))

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

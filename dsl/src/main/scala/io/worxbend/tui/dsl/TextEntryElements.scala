package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, KeyCode, KeyEvent, KeyModifiers, Rect, Widget}
import io.worxbend.tui.widgets as w

/** Which characters a [[NumberInputElement]] accepts: whole numbers only, or a single decimal point as well. */
enum NumberFormat:
  case Integer, Decimal

/** Single-line editors preserve pasted words without retaining terminal control line breaks. */
private def singleLineText(text: String): String = text.replace("\r", "").replace("\n", " ")

/** Single-line text input. Editing state (value + cursor) is app-owned; editing keys are handled by the built-in
  * handler while focused, and any consumed key triggers a redraw.
  */
final case class InputElement(
    state: w.TextInputState,
    placeholder: String = "",
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = InputElement
  def widget: Widget                                                         =
    val input = w.TextInput(placeholder, showCursor = props.focused, style = props.style)
    (area, buffer) => input.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): InputElement              = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.insert(singleLineText(text))
      true
    else false
  }

  private def handleKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Char(c) if isPlainTyping(event) =>
        state.insert(Character.toString(c))
        true
      case _                                       => cursorKeys(state)(event)

/** A text input with a live suggestion dropdown: typing filters `suggestions` (subsequence match), Up/Down move the
  * highlight, Enter accepts it into the input and fires `onAccept`.
  */
final case class AutocompleteElement(
    state: AutocompleteState,
    suggestions: Seq[String],
    onAccept: String => Unit = _ => (),
    maxSuggestions: Int = 5,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = AutocompleteElement

  /** Caps how many matches the dropdown shows at once. */
  def maxSuggestions(count: Int): AutocompleteElement = copy(maxSuggestions = count)

  private def matches: Seq[String] =
    val query = state.input.value
    if query.isEmpty then Seq.empty
    else suggestions.filter(Fuzzy.matcher(query)).take(maxSuggestions)

  /** The row the highlight actually lands on: `state.highlighted` survives edits that shorten the match list, so it is
    * clamped here rather than at every place that writes it. Both the rendered highlight and the row Enter accepts go
    * through this, which is what keeps them the same row.
    */
  private def highlightedIndex(visible: Seq[String]): Int =
    math.max(0, math.min(state.highlighted, math.max(0, visible.size - 1)))

  def widget: Widget                                                         =
    val visible   = matches
    val highlight = highlightedIndex(visible)
    val input     = w.TextInput(showCursor = props.focused, style = props.style)
    (area, buffer) =>
      input.render(Rect(area.x, area.y, area.width, 1), buffer, state.input)
      visible.zipWithIndex.foreach { (candidate, index) =>
        val rowStyle = if index == highlight && props.focused then focusStyled(props) else props.style.dim
        buffer.setString(area.x + 2, area.y + 1 + index, candidate, rowStyle)
      }
  private[dsl] def withProps(props: ElementProps): AutocompleteElement       = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.rows(1 + matches.size)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      insertText(singleLineText(text))
      true
    else false
  }

  private def insertText(text: String): Unit =
    state.input.insert(text)
    state.highlighted = 0

  private def handleKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Char(c) if isPlainTyping(event) =>
        insertText(Character.toString(c))
        true
      case KeyCode.Backspace                       =>
        state.input.backspace()
        state.highlighted = 0
        true
      case KeyCode.Down                            =>
        state.highlighted = math.min(state.highlighted + 1, math.max(0, matches.size - 1))
        true
      case KeyCode.Up                              =>
        state.highlighted = math.max(0, state.highlighted - 1)
        true
      case KeyCode.Enter                           =>
        val visible = matches
        visible.lift(highlightedIndex(visible)) match
          case Some(choice) =>
            state.accept(choice)
            onAccept(choice)
            true
          case None         => false
      case KeyCode.Left                            =>
        state.input.moveLeft()
        true
      case KeyCode.Right                           =>
        state.input.moveRight()
        true
      case _                                       => false

/** A text input restricted to numbers: an optional single leading minus and, in [[NumberFormat.Decimal]], one dot. */
final case class NumberInputElement(
    state: w.TextInputState,
    format: NumberFormat = NumberFormat.Integer,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = NumberInputElement

  /** Also accepts a single decimal point. */
  def decimal: NumberInputElement = copy(format = NumberFormat.Decimal)

  def widget: Widget                                                     =
    val input = w.TextInput(showCursor = props.focused, style = props.style)
    (area, buffer) => input.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): NumberInputElement    = copy(props = props)
  private[dsl] override def claim: SizeClaim                             = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] = Some(handleKey)

  /** A paste is accepted as one edit or rejected whole; rejected payload never becomes application key commands. */
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      insertNumber(text)
      true
    else false
  }

  // Rejected printable characters are consumed too: they must not bubble as global keys while typing.
  private def handleKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Char(c) if isPlainTyping(event) =>
        insertNumber(Character.toString(c))
        true
      case _                                       => cursorKeys(state)(event)

  /** Check the prospective value, not just the inserted character: a digit before an existing minus would otherwise
    * turn a valid number into `3-12`. The cursor is a grapheme index, including for caller-supplied initial text.
    */
  private def insertNumber(text: String): Unit =
    val clusters = CharWidth.graphemeClusters(state.value).toVector
    val proposed = clusters.patch(state.cursor, Seq(text), 0).mkString
    val points   = proposed.codePoints().toArray
    val valid    = points.zipWithIndex.forall { (point, index) =>
      Character.isDigit(point) || (point == '-' && index == 0) ||
      (point == '.' && format == NumberFormat.Decimal)
    } && points.count(_ == '.') <= 1
    if valid then state.insert(text)

/** A template-driven input (`##/##/####`): `#` accepts a digit, `A` a letter, literals insert themselves. */
final case class TemplateInputElement(
    state: w.TextInputState,
    template: String,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TemplateInputElement
  def widget: Widget                                                         =
    val input = w.TextInput(placeholder = template, showCursor = props.focused, style = props.style)
    (area, buffer) => input.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TemplateInputElement      = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      text.codePoints().forEach(point => typeChar(point))
      true
    else false
  }
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case event @ KeyEvent(KeyCode.Char(c), _) if isPlainTyping(event) => typeChar(c)
        case KeyEvent(KeyCode.Backspace, _)                               => eraseSlot()
      }
    )

  /** The template split into grapheme clusters, which is the unit [[currentLength]] counts the typed value in. Indexing
    * the raw `String` instead would mix UTF-16 code units with clusters and read the wrong slot for any template
    * holding a non-BMP or combining character.
    */
  private val slots: IndexedSeq[String] = CharWidth.graphemeClusters(template).toIndexedSeq

  private def typeChar(codePoint: Int): Unit =
    state.moveEnd()
    var position = currentLength
    // literals between fillable slots insert themselves
    while position < slots.size && !isSlot(slots(position)) do
      state.insert(slots(position))
      position += 1
    if position < slots.size && slotAccepts(slots(position), codePoint) then state.insert(Character.toString(codePoint))

  private def eraseSlot(): Unit =
    state.moveEnd()
    state.backspace()
    while currentLength > 0 && currentLength <= slots.size && !isSlot(slots(currentLength - 1)) do state.backspace()

  private def currentLength: Int = CharWidth.clusterCount(state.value)

  /** The fillable slot markers and which typed character each accepts. */
  private val DigitSlot                                = "#"
  private val LetterSlot                               = "A"
  private val slotKinds: Seq[(String, Int => Boolean)] = Seq(
    DigitSlot  -> ((codePoint: Int) => Character.isDigit(codePoint)),
    LetterSlot -> ((codePoint: Int) => Character.isLetter(codePoint)),
  )

  private def isSlot(slot: String): Boolean = slotKinds.exists((marker, _) => slot == marker)

  private def slotAccepts(slot: String, codePoint: Int): Boolean =
    slotKinds.exists((marker, accepts) => slot == marker && accepts(codePoint))

/** Multi-line editor element. While focused it consumes printable characters, Enter (newline), Backspace, Delete,
  * arrows, Home/End, Ctrl+Z (undo) and Ctrl+Y (redo) — Tab stays free for focus traversal. A bracketed paste lands as
  * one edit, with carriage returns stripped.
  */
final case class TextAreaElement(
    state: w.TextAreaState,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TextAreaElement
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.insert(text.replace("\r", ""))
      true
    else false
  }
  def widget: Widget                                                         =
    val editor = w.TextArea(showCursor = props.focused, style = props.style)
    (area, buffer) => editor.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TextAreaElement           = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case KeyEvent(KeyCode.Char('z'), modifiers) if modifiers.hasAny(KeyModifiers.Ctrl) => state.undo()
        case KeyEvent(KeyCode.Char('y'), modifiers) if modifiers.hasAny(KeyModifiers.Ctrl) => state.redo()
        case event @ KeyEvent(KeyCode.Char(c), _) if isPlainTyping(event)                  =>
          state.insert(Character.toString(c))
        case KeyEvent(KeyCode.Enter, _)                                                    =>
          state.newline()
        case KeyEvent(KeyCode.Backspace, _)                                                =>
          state.backspace()
        case KeyEvent(KeyCode.Delete, _)                                                   => state.delete()
        case KeyEvent(KeyCode.Left, _)                                                     =>
          state.moveLeft()
        case KeyEvent(KeyCode.Right, _)                                                    =>
          state.moveRight()
        case KeyEvent(KeyCode.Up, _)                                                       => state.moveUp()
        case KeyEvent(KeyCode.Down, _)                                                     =>
          state.moveDown()
        case KeyEvent(KeyCode.Home, _)                                                     =>
          state.moveHome()
        case KeyEvent(KeyCode.End, _)                                                      => state.moveEnd()
      }
    )

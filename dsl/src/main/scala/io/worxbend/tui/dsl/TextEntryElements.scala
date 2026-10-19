package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Constraint, KeyCode, KeyEvent, KeyModifiers, Position, Rect, Versioned, Widget}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

/** The I-beam every text-entry node shows unless `.pointer(shape)` said otherwise. */
private def textPointer(props: ElementProps): Option[PointerShape] = props.pointerShape.orElse(Some(PointerShape.Text))

/** Which characters a [[NumberInputElement]] accepts: whole numbers only, or a single decimal point as well. */
enum NumberFormat:
  case Integer, Decimal

/** Single-line editors preserve pasted words without retaining terminal control line breaks. */
private def singleLineText(text: String): String = text.replace("\r", "").replace("\n", " ")

/** The single-line widget a text node draws with: the painted cursor shows only while the node is focused *and* its
  * caret style paints — the one place that rule is spelled, so every input node agrees with [[reportCaret]].
  */
private def focusedInput(props: ElementProps, placeholder: String = ""): w.TextInput =
  w.TextInput(placeholder, showCursor = props.focused && props.caretStyle.paints, style = componentStyled(props))

/** Hands the caret cell to the frame's [[CaretRegister]] when this node is the focused one and its caret style moves
  * the terminal cursor. Called from inside the node's render, after the widget painted, with the widget's own answer.
  */
private def reportCaret(props: ElementProps, cell: => Option[Position]): Unit =
  if props.focused && props.caretStyle.moves then cell.foreach(CaretRegister.report)

/** Which of the two carets a focused text node shows — see [[CaretStyle]]. Defined for every element so that the
  * builder chains like any other, but read only by the text-entry nodes; on anything else it is inert.
  */
extension [E <: Element](element: E)
  def caretStyle(style: CaretStyle): element.Self =
    element.withProps(element.props.copy(caretStyle = style))

/** The editing chords every free-text node shares, over the [[w.EditBuffer]] both editor states are:
  *
  *   - `Shift` + arrows / `Home` / `End` select; `Ctrl+Left`/`Ctrl+Right` (and `Alt+B`/`Alt+F`) move by word, with
  *     `Shift` selecting; `Ctrl+Up`/`Ctrl+Down` move by paragraph; `Ctrl+A` selects all
  *   - `Ctrl+C` / `Ctrl+X` / `Ctrl+V` copy, cut and paste through the app clipboard ([[AppServices.clipboard]]); with
  *     nothing selected `Ctrl+C` and `Ctrl+X` are left to bubble, so `Ctrl+C` still quits
  *   - `Ctrl+K` / `Ctrl+U` kill to the end / start of the line, `Ctrl+W` and `Alt+Backspace` kill the word before the
  *     caret, `Alt+D` the word after it; `Ctrl+Y` yanks the newest kill and `Alt+Y` cycles it through the ring
  *   - `Ctrl+Z` undoes, `Ctrl+Shift+Z` redoes
  *
  * Answers whether the event was one of them.
  */
/** The letters whose `Alt+<letter>` chord [[editingChords]] gives a text field: word left and right, delete the word
  * after the caret, cycle the kill ring. A focused field keeps these ahead of a `menuBar` mnemonic.
  */
private[dsl] val TextEntryAltChords: Set[Char] = Set('b', 'd', 'f', 'y')

private def editingChords(state: w.EditBuffer)(event: KeyEvent): Boolean =
  val mods                        = event.modifiers
  val select                      = mods.hasAny(KeyModifiers.Shift)
  val ctrl                        = mods.hasAny(KeyModifiers.Ctrl)
  val alt                         = mods.hasAny(KeyModifiers.Alt)
  def did(body: => Unit): Boolean =
    body
    true
  event.code match
    case KeyCode.Left if ctrl            => did(state.moveWordLeft(select))
    case KeyCode.Right if ctrl           => did(state.moveWordRight(select))
    case KeyCode.Up if ctrl              => did(state.moveParagraphUp(select))
    case KeyCode.Down if ctrl            => did(state.moveParagraphDown(select))
    case KeyCode.Left if select          => did(state.moveLeft(select = true))
    case KeyCode.Right if select         => did(state.moveRight(select = true))
    case KeyCode.Up if select            => did(state.moveUp(select = true))
    case KeyCode.Down if select          => did(state.moveDown(select = true))
    case KeyCode.Home if select          => did(state.moveHome(select = true))
    case KeyCode.End if select           => did(state.moveEnd(select = true))
    case KeyCode.Backspace if alt        => did(state.deleteWordBackward())
    case KeyCode.Char(c) if ctrl && !alt =>
      Character.toLowerCase(c) match
        case 'a'           => did(state.selectAll())
        case 'c'           => state.selection.isDefined && did(state.copy(AppClipboard.current))
        case 'x'           => state.selection.isDefined && did(state.cut(AppClipboard.current))
        case 'v'           => did(state.paste(AppClipboard.current))
        case 'k'           => did(state.killToEndOfLine())
        case 'u'           => did(state.killToStartOfLine())
        case 'w'           => did(state.deleteWordBackward())
        case 'y'           => did(state.yank())
        case 'z' if select => did(state.redo())
        case 'z'           => did(state.undo())
        case _             => false
    case KeyCode.Char(c) if alt && !ctrl =>
      Character.toLowerCase(c) match
        case 'b' => did(state.moveWordLeft(select))
        case 'f' => did(state.moveWordRight(select))
        case 'd' => did(state.deleteWordForward())
        case 'y' => did(state.yankPop())
        case _   => false
    case _                               => false

/** Single-line text input. Editing state (value + cursor) is app-owned; editing keys are handled by the built-in
  * handler while focused, and any consumed key triggers a redraw.
  */
final case class InputElement(
    state: w.TextInputState,
    placeholder: String = "",
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = InputElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "input"
  private[dsl] override def editsText: Boolean                               = true
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(InputRole)
  override def pointerShape: Option[PointerShape]                            = textPointer(props)
  def widget: Widget                                                         =
    val input = focusedInput(props, placeholder)
    (area, buffer) =>
      input.render(area, buffer, state)
      reportCaret(props, input.caretAt(area, state))
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
      case _                                       => editingChords(state)(event) || cursorKeys(state)(event)

/** The query input of a search: a one-row text field over the caller-owned [[w.SearchState]]'s own
  * [[w.SearchState.input]], so every keystroke changes the query the searched view (`log(…).searchable(state)`,
  * `scrollView(…).searchable(state)`, `markdown(…).searchable(state)`) finds on its next frame — the search is live.
  *
  * While focused: typing and the shared editing chords edit the query; Enter (or Down) steps to the next match and
  * Shift+Enter (or Up) to the previous one; Esc clears the query and the highlight. With nothing to clear, Esc is left
  * to bubble.
  */
final case class SearchBarElement(
    state: w.SearchState,
    placeholder: String = Messages.active.searchPlaceholder,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = SearchBarElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "searchBar"
  private[dsl] override def editsText: Boolean                               = true
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(InputRole)
  override def pointerShape: Option[PointerShape]                            = textPointer(props)
  def widget: Widget                                                         =
    val input = focusedInput(props, placeholder)
    (area, buffer) =>
      input.render(area, buffer, state.input)
      reportCaret(props, input.caretAt(area, state.input))
  private[dsl] def withProps(props: ElementProps): SearchBarElement          = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.input.insert(singleLineText(text))
      true
    else false
  }

  /** A step is consumed whether or not there was a match to move to: Enter in a search field means "next", never more.
    */
  private def stepped(move: => Boolean): Boolean =
    val _ = move
    true

  private def handleKey(event: KeyEvent): Boolean =
    val shift = event.modifiers.hasAny(KeyModifiers.Shift)
    event.code match
      case KeyCode.Escape                          =>
        val had = state.isActive
        state.clear()
        had
      case KeyCode.Enter if shift                  => stepped(state.previous())
      case KeyCode.Enter | KeyCode.Down            => stepped(state.next())
      case KeyCode.Up                              => stepped(state.previous())
      case KeyCode.Char(c) if isPlainTyping(event) =>
        state.input.insert(Character.toString(c))
        true
      case _ => editingChords(state.input)(event) || cursorKeys(state.input)(event)

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

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]        = Seq(state)
  override def debugName: String                                 = "autocomplete"
  private[dsl] override def componentRole: Option[ComponentRole] = Some(InputRole)

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
    val input     = focusedInput(props)
    (area, buffer) =>
      val row = Rect(area.x, area.y, area.width, 1)
      input.render(row, buffer, state.input)
      reportCaret(props, input.caretAt(row, state.input))
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

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]        = Seq(state)
  override def debugName: String                                 = "numberInput"
  private[dsl] override def componentRole: Option[ComponentRole] = Some(InputRole)
  override def pointerShape: Option[PointerShape]                = textPointer(props)

  /** Also accepts a single decimal point. */
  def decimal: NumberInputElement = copy(format = NumberFormat.Decimal)

  def widget: Widget                                                     =
    val input = focusedInput(props)
    (area, buffer) =>
      input.render(area, buffer, state)
      reportCaret(props, input.caretAt(area, state))
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

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "templateInput"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(InputRole)
  override def pointerShape: Option[PointerShape]                            = textPointer(props)
  def widget: Widget                                                         =
    val input = focusedInput(props, placeholder = template)
    (area, buffer) =>
      input.render(area, buffer, state)
      reportCaret(props, input.caretAt(area, state))
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
  * arrows, Home/End and the shared editing chords ([[editingChords]]: selection, word motion, clipboard, kill ring,
  * `Ctrl+Z` undo, `Ctrl+Shift+Z` redo) — Tab stays free for focus traversal. A bracketed paste lands as one edit, with
  * carriage returns stripped.
  *
  * Search: `Ctrl+F` opens the `/query` prompt (so does `/` in a [[readOnly]] area); typing searches incrementally,
  * `Enter` keeps the match and closes the prompt, `Esc` cancels back to where the search started. Afterwards `F3` /
  * `Shift+F3` (and `n` / `N` when read-only) step to the next and previous match.
  *
  * [[searchable]] hands the search to a caller-owned [[w.SearchState]] instead, the path `log`, `scrollView` and
  * `markdown` take: a `searchBar` over the same state types the query, the matches are highlighted in the theme's
  * search styles, and every move of the search puts the caret on the current match. `F3` / `Shift+F3` (and `n` / `N`
  * when read-only) then step that shared search, and `Ctrl+F` / `/` are left to bubble to the app, which owns the bar.
  */
final case class TextAreaElement(
    state: w.TextAreaState,
    options: w.TextAreaOptions = w.TextAreaOptions(),
    editable: Boolean = true,
    props: ElementProps = ElementProps(focusable = true),
    search: Option[w.SearchState] = None,
) extends Element:
  type Self = TextAreaElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]        = Seq(state) ++ search.toSeq
  override def debugName: String                                 = "textArea"
  private[dsl] override def editsText: Boolean                   = true
  private[dsl] override def componentRole: Option[ComponentRole] = Some(InputRole)
  override def pointerShape: Option[PointerShape]                = textPointer(props)

  /** Highlights the lines through `lexer` — see `TextAreaOptions.lexer`. */
  def lexer(lexer: w.Lexer): TextAreaElement = copy(options = options.copy(lexer = Some(lexer)))

  /** Soft-wraps long lines instead of scrolling sideways. */
  def wrap: TextAreaElement = copy(options = options.copy(wrap = true))

  /** Draws a line-number gutter. */
  def lineNumbers: TextAreaElement = copy(options = options.copy(lineNumbers = true))

  /** Replaces every widget option at once. */
  def options(options: w.TextAreaOptions): TextAreaElement = copy(options = options)

  /** A viewer: motion, selection, copy and search work, edits do not — and `/`, `n`, `N` search like `less`. */
  def readOnly: TextAreaElement = copy(editable = false)

  /** Searches this area through `state` — the shared [[w.SearchState]] path; see the class notes. Pair it with a
    * [[searchBar]] over the same state (or over the state a [[w.SearchState.view]] came from).
    */
  def searchable(state: w.SearchState): TextAreaElement = copy(search = Some(state))

  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused && editable then
      state.insert(text.replace("\r", ""))
      true
    else false
  }
  def widget: Widget                                                         =
    val editor =
      w.TextArea(
        showCursor = props.focused && props.caretStyle.paints,
        style = componentStyled(props),
        options = options,
      )
    (area, buffer) =>
      search match
        case Some(found) =>
          val styles = SearchStyles.of(props.focusState.theme)
          editor.render(area, buffer, state, found, styles.matched, styles.current)
        case None        => editor.render(area, buffer, state)
      reportCaret(props, editor.caretAt(area, state))
  private[dsl] def withProps(props: ElementProps): TextAreaElement           = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(event =>
      search match
        case Some(found)                    => sharedSearchKey(found, event) || editKey(event)
        case None if state.search.isDefined => searchPromptKey(event)
        case None                           => searchKey(event) || editKey(event)
    )

  /** The step keys of a [[searchable]] area: they move the shared search, whose next frame moves the caret. */
  private def sharedSearchKey(found: w.SearchState, event: KeyEvent): Boolean =
    val mods                               = event.modifiers
    def stepped(forward: Boolean): Boolean =
      val _ = if forward then found.next() else found.previous()
      true
    found.isActive && (event.code match
      case KeyCode.F(3) if mods.hasAny(KeyModifiers.Shift)        => stepped(forward = false)
      case KeyCode.F(3)                                           => stepped(forward = true)
      case KeyCode.Char('n') if !editable && mods.isEmpty         => stepped(forward = true)
      case KeyCode.Char('N') if !editable && isPlainTyping(event) => stepped(forward = false)
      case _                                                      => false)

  private def searchPromptKey(event: KeyEvent): Boolean =
    val query = state.search.getOrElse("")
    event.code match
      case KeyCode.Char(c) if isPlainTyping(event) =>
        state.updateSearch(query + Character.toString(c))
        true
      case KeyCode.Backspace                       =>
        state.updateSearch(CharWidth.graphemeClusters(query).toVector.dropRight(1).mkString)
        true
      case KeyCode.Enter                           =>
        state.closeSearch()
        true
      case KeyCode.Escape                          =>
        state.cancelSearch()
        true
      case _                                       => false

  private def searchKey(event: KeyEvent): Boolean =
    val mods = event.modifiers
    event.code match
      case KeyCode.Char('f') if mods == KeyModifiers.Ctrl         => opened()
      case KeyCode.Char('/') if !editable && isPlainTyping(event) => opened()
      case KeyCode.Char('n') if !editable && mods.isEmpty         => state.nextMatch()
      case KeyCode.Char('N') if !editable && isPlainTyping(event) => state.previousMatch()
      case KeyCode.F(3) if mods.hasAny(KeyModifiers.Shift)        => state.previousMatch()
      case KeyCode.F(3)                                           => state.nextMatch()
      case _                                                      => false

  private def opened(): Boolean =
    state.openSearch()
    true

  /** Keys that only move, select or copy — the whole vocabulary of a read-only area. */
  private def isNonEditing(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Left | KeyCode.Right | KeyCode.Up | KeyCode.Down | KeyCode.Home | KeyCode.End => true
      case KeyCode.Char(c) if event.modifiers.hasAny(KeyModifiers.Ctrl)                          =>
        Character.toLowerCase(c) == 'a' || Character.toLowerCase(c) == 'c'
      case KeyCode.Char(c) if event.modifiers.hasAny(KeyModifiers.Alt)                           =>
        Character.toLowerCase(c) == 'b' || Character.toLowerCase(c) == 'f'
      case _                                                                                     => false

  private def editKey(event: KeyEvent): Boolean =
    if !editable && !isNonEditing(event) then false
    else
      editingChords(state)(event) || keys {
        case event @ KeyEvent(KeyCode.Char(c), _) if isPlainTyping(event) =>
          state.insert(Character.toString(c))
        case KeyEvent(KeyCode.Enter, _)                                   =>
          state.newline()
        case KeyEvent(KeyCode.Backspace, _)                               =>
          state.backspace()
        case KeyEvent(KeyCode.Delete, _)                                  => state.delete()
        case KeyEvent(KeyCode.Left, _)                                    =>
          state.moveLeft()
        case KeyEvent(KeyCode.Right, _)                                   =>
          state.moveRight()
        case KeyEvent(KeyCode.Up, _)                                      => state.moveUp()
        case KeyEvent(KeyCode.Down, _)                                    =>
          state.moveDown()
        case KeyEvent(KeyCode.Home, _)                                    =>
          state.moveHome()
        case KeyEvent(KeyCode.End, _)                                     => state.moveEnd()
      }(event)

/** A tag (chip) field over a caller-owned [[w.TagInputState]]: committed tags draw as chips that wrap onto further rows
  * through [[w.Flow]], followed by the text being typed. While focused, typing edits the text with every key a text
  * field knows; Enter or a comma commits it as a tag, Backspace on an empty field removes the last tag, and Tab (or
  * Right at the end of the text) accepts the dim suggestion when there is one. `onChange` hears the tags after every
  * commit or removal. An Enter on a blank field, and a Tab with nothing to accept, keep bubbling.
  *
  * Measures as many rows as its chips wrap onto at the width it is given, and reports its caret like every text node.
  */
final case class TagInputElement(
    state: w.TagInputState,
    onChange: Seq[String] => Unit,
    placeholder: String = "",
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TagInputElement
  override private[dsl] def watchedStates: Seq[Versioned]            = Seq(state)
  override def debugName: String                                     = "tagInput"
  private[dsl] override def editsText: Boolean                       = true
  private[dsl] override def componentRole: Option[ComponentRole]     = Some(InputRole)
  override def pointerShape: Option[PointerShape]                    = textPointer(props)
  private def field: w.TagInput                                      =
    w.TagInput(placeholder, showCursor = props.focused && props.caretStyle.paints, style = componentStyled(props))
  def widget: Widget                                                 =
    val current = field
    (area, buffer) =>
      current.render(area, buffer, state)
      reportCaret(props, current.caretAt(area, state))
  private[dsl] def withProps(props: ElementProps): TagInputElement   = copy(props = props)
  private[dsl] override def claim: SizeClaim                         = SizeClaim(Constraint.Auto, Constraint.Fill(1))
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(Some(field.heightAt(width, state)))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.input.insert(singleLineText(text))
      true
    else false
  }

  private def changed(): Unit = onChange(state.tags)

  private def handleKey(event: KeyEvent): Boolean =
    val empty = state.input.value.isEmpty
    event.code match
      case KeyCode.Char(',') if isPlainTyping(event)                                    =>
        if state.commit() then changed()
        true
      case KeyCode.Enter if event.modifiers.isEmpty && state.input.value.trim.nonEmpty  =>
        if state.commit() then changed()
        true
      case KeyCode.Backspace if event.modifiers.isEmpty && empty && state.tags.nonEmpty =>
        state.removeLast()
        changed()
        true
      case KeyCode.Tab if event.modifiers.isEmpty                                       => state.acceptSuggestion()
      case KeyCode.Right if event.modifiers.isEmpty && state.input.cursor == clusters   => state.acceptSuggestion()
      case KeyCode.Char(c) if isPlainTyping(event)                                      =>
        state.input.insert(Character.toString(c))
        true
      case _ => editingChords(state.input)(event) || cursorKeys(state.input)(event)

  private def clusters: Int = CharWidth.clusterCount(state.input.value)

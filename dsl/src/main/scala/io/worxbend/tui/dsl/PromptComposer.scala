package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, CharWidth, KeyCode, KeyEvent, KeyModifiers, Position, Rect, Versioned, Widget}
import io.worxbend.tui.runtime.{Async, Cancelable, ReactiveScope, Signal, TaskScope}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Caller-owned state of a [[promptComposer]]: the multi-line draft, the submission history, the open reverse search or
  * completion popup, and the large pastes folded into placeholders.
  *
  * Create it once outside `view`. Render-thread-only, like every widget state; the completion provider is the one piece
  * that runs elsewhere — on a background worker, debounced by `debounce` and latest-wins through a [[TaskScope]], so a
  * slow provider never blocks typing and a stale answer never replaces a fresher one.
  *
  * @param initialHistory
  *   earlier submissions, oldest first; each submission is appended
  * @param completions
  *   answers the candidates for a trigger (`'/'` at the start of the prompt, `'@'` at the start of a word) and the
  *   query typed after it. Runs on a worker thread, so it may block on IO; it must not touch UI state.
  * @param debounce
  *   how long typing must pause before the provider is asked
  * @param pasteLines
  *   a bracketed paste of at least this many lines is shown as a `[Pasted N lines]` placeholder and expanded on submit
  * @param maxRows
  *   the most rows the draft grows to before it scrolls
  *
  * [[Versioned]] over its own fields as well as the draft: [[version]] is the editor's version plus a count of this
  * state's own writes (history recall, the reverse search, the completion popup, pastes, submits), so any of them made
  * between frames schedules the frame that shows it.
  */
final class PromptComposerState(
    initialHistory: Seq[String] = Nil,
    val completions: (Char, String) => Seq[String] = (_, _) => Nil,
    val debounce: FiniteDuration = 150.millis,
    val pasteLines: Int = 10,
    val maxRows: Int = 8,
) extends Versioned:
  /** The draft being edited — every [[w.EditBuffer]] operation is available on it. */
  val editor: w.TextAreaState = w.TextAreaState()

  private val entries                      = mutable.ArrayBuffer.from(initialHistory)
  private var recalled: Option[Int]        = None
  private var draft: String                = ""
  private val pastes                       = mutable.LinkedHashMap.empty[String, String]
  private var reverseOpen: Option[Reverse] = None
  private var popupOpen: Option[Popup]     = None
  private var scope: Option[TaskScope]     = None
  private var pending: Option[Cancelable]  = None
  private var writes: Long                 = 0L

  def version: Long = writes + editor.version

  private def touch(): Unit = writes += 1

  private[dsl] def reverse: Option[Reverse] = reverseOpen

  private[dsl] def reverse_=(next: Option[Reverse]): Unit =
    reverseOpen = next
    touch()

  private[dsl] def popup: Option[Popup] = popupOpen

  private[dsl] def popup_=(next: Option[Popup]): Unit =
    popupOpen = next
    touch()

  /** Bumped by an async completion result, so the frame that shows it is scheduled. */
  private[dsl] val revision: Signal[Int] = Signal(0)

  /** The draft as typed, placeholders unexpanded. */
  def value: String = editor.value

  /** Submissions so far, oldest first. */
  def history: Seq[String] = entries.toSeq

  /** The draft with every paste placeholder replaced by the text it stands for — what a submission hands on. */
  def expandedValue: String = pastes.foldLeft(editor.value)((text, entry) => text.replace(entry._1, entry._2))

  /** Takes the expanded draft, records it in the history and clears the composer; `None` when the draft is blank. */
  def submit(): Option[String] =
    val text = expandedValue
    closePopup()
    reverse = None
    recalled = None
    editor.clear()
    pastes.clear()
    touch()
    Option.when(text.trim.nonEmpty) {
      if !entries.lastOption.contains(text) then entries += text
      text
    }

  // --- history ---------------------------------------------------------------------------------------------------

  /** Steps one entry back in the history, keeping the draft to come back to. */
  def historyPrevious(): Unit =
    if entries.nonEmpty then
      val index = recalled.fold(entries.size - 1)(current => math.max(0, current - 1))
      if recalled.isEmpty then draft = editor.value
      recalled = Some(index)
      touch()
      editor.setValue(entries(index))

  /** Steps one entry forward; past the newest, the draft comes back. */
  def historyNext(): Unit =
    recalled.foreach { current =>
      touch()
      if current + 1 < entries.size then
        recalled = Some(current + 1)
        editor.setValue(entries(current + 1))
      else
        recalled = None
        editor.setValue(draft)
    }

  // --- reverse search --------------------------------------------------------------------------------------------

  /** `Ctrl+R`: opens the reverse search, or steps to the next older match when it is open. */
  def reverseSearch(): Unit =
    reverse = Some(reverse.fold(Reverse("", None))(open => open.copy(hit = findOlder(open.query, open.hit))))

  private[dsl] def updateReverse(query: String): Unit =
    reverse = Some(Reverse(query, findOlder(query, None)))

  /** Loads the match into the draft and closes the search. */
  private[dsl] def acceptReverse(): Unit =
    reverse.flatMap(_.hit).foreach(index => editor.setValue(entries(index)))
    reverse = None

  private def findOlder(query: String, before: Option[Int]): Option[Int] =
    val from = before.fold(entries.size - 1)(_ - 1)
    (from to 0 by -1).find(index => entries(index).contains(query)).orElse(before)

  private[dsl] def reverseMatch: Option[String] = reverse.flatMap(_.hit).map(entries)

  // --- ghost suggestion ------------------------------------------------------------------------------------------

  /** The rest of the newest history entry that starts with the draft, offered dim after the caret — only while the
    * caret is at the end of a non-empty draft and nothing else is open.
    */
  def ghost: Option[String] =
    val typed = CharWidth.graphemeClusters(editor.value).toVector
    if typed.isEmpty || !editor.isAtEnd || popup.isDefined || reverse.isDefined then None
    else
      entries.reverseIterator.map(entry => CharWidth.graphemeClusters(entry).toVector).collectFirst {
        case clusters if clusters.size > typed.size && clusters.startsWith(typed) => clusters.drop(typed.size).mkString
      }

  /** Inserts the [[ghost]] suggestion; answers whether there was one. */
  def acceptGhost(): Boolean =
    ghost.exists { rest =>
      editor.insert(rest)
      true
    }

  // --- completion ------------------------------------------------------------------------------------------------

  /** Re-reads the draft after an edit: opens the popup on a fresh trigger, follows the query, or closes it. */
  private[dsl] def refreshCompletion(): Unit =
    val caret = editor.caret
    val line  = lineClusters(caret.line)
    popup match
      case Some(open) =>
        val query = line.slice(open.start.column + 1, caret.column)
        if caret.line != open.start.line || caret.column <= open.start.column ||
          query.exists(_.codePoints().allMatch(Character.isWhitespace))
        then closePopup()
        else request(open.copy(query = query.mkString))
      case None       =>
        val typed = line.lift(caret.column - 1)
        val start = w.TextPosition(caret.line, caret.column - 1)
        val opens = typed match
          case Some("/") => caret.line == 0 && caret.column == 1
          case Some("@") =>
            caret.column == 1 || line.lift(caret.column - 2).exists(_.codePoints().allMatch(Character.isWhitespace))
          case _         => false
        if opens then request(Popup(typed.fold('@')(_.head), start, "", Vector.empty, 0))

  /** Replaces the trigger and query with the highlighted candidate and a trailing space. */
  private[dsl] def acceptCompletion(): Boolean =
    popup.flatMap(open => open.items.lift(open.highlighted).map(open -> _)) match
      case Some((open, item)) =>
        val end = editor.caret
        editor.moveTo(open.start)
        editor.moveTo(end, select = true)
        editor.insert(s"${open.trigger}$item ")
        closePopup()
        true
      case None               => false

  private[dsl] def moveHighlight(delta: Int): Unit =
    popup =
      popup.map(open => open.copy(highlighted = math.max(0, math.min(open.items.size - 1, open.highlighted + delta))))

  private[dsl] def closePopup(): Unit =
    pending.foreach(_.cancel())
    pending = None
    popup = None

  private def request(next: Popup): Unit =
    popup = Some(next)
    pending.foreach(_.cancel())
    val tasks = taskScope
    pending = tasks.map { scope =>
      scope.after(debounce) {
        val _ = scope.latest(this)(completions(next.trigger, next.query)) {
          case Right(items) if popup.exists(open => open.start == next.start && open.query == next.query) =>
            popup = popup.map(_.copy(items = items.toVector, highlighted = 0))
            revision.set(revision.peek + 1)
          case _                                                                                          => ()
        }
      }
    }

  /** The scope completions run under, created on first use on the render thread; `None` outside a running app. */
  private def taskScope: Option[TaskScope] =
    if scope.isEmpty then scope = Async.scopeIfRunning()
    scope

  // --- large paste -----------------------------------------------------------------------------------------------

  /** Inserts a bracketed paste, folding one of at least [[pasteLines]] lines into a placeholder. */
  def paste(text: String): Unit =
    val clean = text.replace("\r", "")
    val lines = clean.split("\n", -1).length
    if lines >= pasteLines then
      val base        = Messages.active.pastedLines(lines)
      // a repeat is numbered inside the closing bracket when the template has one: `[Pasted 3 lines #2]`
      val numbered    =
        if base.endsWith("]") then s"${base.dropRight(1)} #${pastes.size + 1}]" else s"$base #${pastes.size + 1}"
      val placeholder = if pastes.contains(base) then numbered else base
      pastes(placeholder) = clean
      touch()
      editor.insert(placeholder)
    else editor.insert(clean)

  private def lineClusters(line: Int): Vector[String] = CharWidth.graphemeClusters(editor.lineText(line)).toVector

/** An open `Ctrl+R` search: what was typed and the history index it matched. */
private[dsl] final case class Reverse(query: String, hit: Option[Int])

/** An open completion popup: the trigger, where it was typed, the query after it, and the provider's answer. */
private[dsl] final case class Popup(
    trigger: Char,
    start: w.TextPosition,
    query: String,
    items: Vector[String],
    highlighted: Int,
)

/** The LLM-CLI prompt: a multi-line editor with history, reverse search, ghost suggestions, trigger completion and
  * paste compaction. `Enter` submits (`onSubmit` gets the expanded text), `Shift+Enter` or `Alt+Enter` inserts a
  * newline; `Up`/`Down` on the first/last line walk the history; `Ctrl+R` searches it; a dim suggestion from history is
  * accepted with `Tab` or `Right`; `/` and `@` open the completion popup (`Up`/`Down`, `Enter`/`Tab`, `Esc`). Every
  * [[editingChords]] chord works too.
  */
final case class PromptComposerElement(
    state: PromptComposerState,
    onSubmit: String => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = PromptComposerElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned] = Seq(state)
  override def debugName: String                          = "promptComposer"
  override def pointerShape: Option[PointerShape]         = textPointer(props)

  private def editorRows: Int = math.max(1, math.min(state.maxRows, state.editor.lineCount))

  private[dsl] override def claim: SizeClaim = SizeClaim.rows(editorRows + state.reverse.size)

  def widget: Widget =
    val editor = w.TextArea(
      showCursor = props.focused && props.caretStyle.paints && state.reverse.isEmpty,
      style = props.style,
      options = w.TextAreaOptions(wrap = true),
    )
    (area, buffer) =>
      val text  = Rect(area.x, area.y, area.width, math.max(0, area.height - state.reverse.size))
      editor.render(text, buffer, state.editor)
      val caret = editor.caretAt(text, state.editor)
      for cell <- caret; rest <- state.ghost do paintGhost(buffer, text, cell.x, cell.y, rest)
      state.reverse.foreach { open =>
        val bar = s"${Messages.active.composerReverseSearch}`${open.query}': ${state.reverseMatch.getOrElse("")}"
        buffer.setString(area.x, area.bottom - 1, CharWidth.substringByWidth(bar, area.width), props.style)
      }
      reportCaret(props, if state.reverse.isDefined then None else caret)
      for open <- state.popup if open.items.nonEmpty && !props.inert; cell <- caret do
        floatPopup(area, buffer, cell, open)

  /** The suggestion's first line, dim, from the caret on; the caret cell keeps its cursor style. */
  private def paintGhost(buffer: Buffer, text: Rect, x: Int, y: Int, rest: String): Unit =
    val shown = CharWidth.substringByWidth(rest.takeWhile(_ != '\n'), text.right - x)
    buffer.setString(x, y, shown, props.style.dim)
    if props.focused && props.caretStyle.paints && shown.nonEmpty then
      val cell = buffer.get(x, y)
      buffer.set(x, y, cell.copy(style = cell.style.reverse))

  /** Floats the candidates under the caret's row, starting at the trigger's column — above the row when the frame has
    * less room below than the list needs — through the portal, so a pane edge does not clip them.
    */
  private def floatPopup(area: Rect, buffer: Buffer, caret: Position, open: Popup): Unit =
    val x         = math.max(area.x, caret.x - CharWidth.of(open.query) - 1)
    val width     = math.min(area.width, open.items.map(CharWidth.of).max + 2)
    val height    = open.items.size
    val screen    = FrameCoordinates.translate(Rect(x, caret.y, width, 1))
    val frame     = FrameCoordinates.frameBoundary.getOrElse(Rect(screen.x, screen.y, width, 1 + height))
    val roomBelow = frame.bottom - (screen.y + 1)
    val below     = roomBelow >= height || roomBelow >= screen.y - frame.y
    val top       = if below then caret.y + 1 else caret.y - height
    val target    = Rect(x, top, width, height)
    val list      = WidgetElement((rect: Rect, into: Buffer) => paintItems(rect, into, open))
    if PortalQueue.isCollecting then PortalQueue.offer(target, list) else list.widget.render(target, buffer)

  private def paintItems(rect: Rect, buffer: Buffer, open: Popup): Unit =
    open.items.take(rect.height).zipWithIndex.foreach { (item, index) =>
      val style = if index == open.highlighted then props.style.reverse else props.style
      val cell  = CharWidth.substringByWidth(" " + item, rect.width)
      buffer.setString(rect.x, rect.y + index, cell + " " * math.max(0, rect.width - CharWidth.of(cell)), style)
    }

  private[dsl] def withProps(props: ElementProps): PromptComposerElement = copy(props = props)

  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.paste(text)
      true
    else false
  }

  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] = Some { event =>
    if state.reverse.isDefined then reverseKey(event)
    else if state.popup.exists(_.items.nonEmpty) && popupKey(event) then true
    else composerKey(event)
  }

  private def reverseKey(event: KeyEvent): Boolean =
    val query = state.reverse.fold("")(_.query)
    event.code match
      case KeyCode.Char('r') if event.modifiers == KeyModifiers.Ctrl => state.reverseSearch()
      case KeyCode.Char(c) if isPlainTyping(event) => state.updateReverse(query + Character.toString(c))
      case KeyCode.Backspace                       =>
        state.updateReverse(CharWidth.graphemeClusters(query).toVector.dropRight(1).mkString)
      case KeyCode.Escape                          => state.reverse = None
      case _                                       => state.acceptReverse()
    true

  private def popupKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Up                  => { state.moveHighlight(-1); true }
      case KeyCode.Down                => { state.moveHighlight(1); true }
      case KeyCode.Enter | KeyCode.Tab => state.acceptCompletion()
      case KeyCode.Escape              =>
        state.closePopup()
        true
      case _                           => false

  private def composerKey(event: KeyEvent): Boolean =
    val editor  = state.editor
    val mods    = event.modifiers
    val handled = event.code match
      case KeyCode.Enter if mods.hasAny(KeyModifiers.Shift) || mods.hasAny(KeyModifiers.Alt) =>
        editor.newline()
        true
      case KeyCode.Enter if mods.isEmpty                                                     =>
        state.submit().foreach(onSubmit)
        true
      case KeyCode.Char('r') if mods == KeyModifiers.Ctrl                                    =>
        state.reverseSearch()
        true
      case KeyCode.Tab if mods.isEmpty                                                       => state.acceptGhost()
      case KeyCode.Right if mods.isEmpty && state.ghost.isDefined                            => state.acceptGhost()
      case KeyCode.Up if mods.isEmpty && editor.caret.line == 0                              =>
        state.historyPrevious()
        true
      case KeyCode.Down if mods.isEmpty && editor.caret.line == editor.lineCount - 1         =>
        state.historyNext()
        true
      case KeyCode.Char(c) if isPlainTyping(event)                                           =>
        editor.insert(Character.toString(c))
        true
      case KeyCode.Backspace if mods.isEmpty                                                 =>
        editor.backspace()
        true
      case _                                                                                 =>
        editingChords(editor)(event) || cursorMotion(editor, event)
    if handled then state.refreshCompletion()
    handled

  private def cursorMotion(editor: w.TextAreaState, event: KeyEvent): Boolean =
    if !event.modifiers.isEmpty then false
    else
      event.code match
        case KeyCode.Left   => { editor.moveLeft(); true }
        case KeyCode.Right  => { editor.moveRight(); true }
        case KeyCode.Up     => { editor.moveUp(); true }
        case KeyCode.Down   => { editor.moveDown(); true }
        case KeyCode.Home   => { editor.moveHome(); true }
        case KeyCode.End    => { editor.moveEnd(); true }
        case KeyCode.Delete => { editor.delete(); true }
        case _              => false

object PromptComposerElement:

  /** Reads the state's revision under `scope`, so an async completion result schedules the frame that shows it. */
  private[dsl] def tracked(state: PromptComposerState, onSubmit: String => Unit)(using
      scope: ReactiveScope
  ): PromptComposerElement =
    val _ = state.revision.get
    PromptComposerElement(state, onSubmit)

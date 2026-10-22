package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Position, Rect, StatefulWidget, Style, Versioned, Widget}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Caller-owned state of a [[PromptComposer]]: the multi-line draft, the submission history, the open reverse search or
  * completion popup, and the large pastes folded into placeholders.
  *
  * Create it once outside `view`. Render-thread-only, like every widget state; the completion provider is the one piece
  * that the host executes elsewhere. The DSL adapter debounces it and delivers latest-wins on the owning render thread;
  * the widget itself depends only on core and owns no executor, task scope or backend.
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
  * [[io.worxbend.tui.core.Versioned Versioned]] over its own fields as well as the draft: [[version]] is the editor's
  * version plus a count of this state's own writes (history recall, the reverse search, the completion popup, pastes,
  * submits), so any of them made between frames schedules the frame that shows it.
  */
final class PromptComposerState(
    initialHistory: Seq[String] = Nil,
    val completions: (Char, String) => Seq[String] = (_, _) => Nil,
    val debounce: FiniteDuration = 150.millis,
    val pasteLines: Int = 10,
    val maxRows: Int = 8,
) extends Versioned:
  /** The draft being edited — every [[EditBuffer]] operation is available on it. */
  val editor: TextAreaState = TextAreaState()

  private val entries                      = mutable.ArrayBuffer.from(initialHistory)
  private var recalled: Option[Int]        = None
  private var draft: String                = ""
  private val pastes                       = mutable.LinkedHashMap.empty[String, String]
  private var reverseOpen: Option[Reverse] = None
  private var popupOpen: Option[Popup]     = None
  private var pending: Option[() => Unit]  = None
  private[tui] var scheduleCompletion: (Popup, Seq[String] => Unit) => (() => Unit) = (_, _) => () => ()
  private var writes: Long                                                          = 0L

  def version: Long = writes + editor.version

  private def touch(): Unit = writes += 1

  private[tui] def reverse: Option[Reverse] = reverseOpen

  private[tui] def reverse_=(next: Option[Reverse]): Unit =
    reverseOpen = next
    touch()

  private[tui] def popup: Option[Popup] = popupOpen

  private[tui] def popup_=(next: Option[Popup]): Unit =
    popupOpen = next
    touch()

  /** The draft as typed, placeholders unexpanded. */
  def value: String = editor.value

  /** Submissions so far, oldest first. */
  def history: Seq[String] = entries.toSeq

  /** The draft with every paste placeholder replaced by the text it stands for — what a submission hands on. */
  def expandedValue: String =
    // Replacement payloads are never re-scanned or expanded recursively.
    val text   = editor.value
    val keys   = pastes.keys.toVector.sortBy(key => -key.length)
    val result = new java.lang.StringBuilder
    var offset = 0
    while offset < text.length do
      keys.find(text.startsWith(_, offset)) match
        case Some(key) =>
          result.append(pastes(key))
          offset += key.length
        case None      =>
          val point = text.codePointAt(offset)
          result.appendCodePoint(point)
          offset += Character.charCount(point)
    result.toString

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

  private[tui] def updateReverse(query: String): Unit =
    reverse = Some(Reverse(query, findOlder(query, None)))

  /** Loads the match into the draft and closes the search. */
  private[tui] def acceptReverse(): Unit =
    reverse.flatMap(_.hit).foreach(index => editor.setValue(entries(index)))
    reverse = None

  private def findOlder(query: String, before: Option[Int]): Option[Int] =
    val from = before.fold(entries.size - 1)(_ - 1)
    (from to 0 by -1).find(index => entries(index).contains(query)).orElse(before)

  private[tui] def reverseMatch: Option[String] = reverse.flatMap(_.hit).map(entries)

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
  private[tui] def refreshCompletion(): Unit =
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
        val start = TextPosition(caret.line, caret.column - 1)
        val opens = typed match
          case Some("/") => caret.line == 0 && caret.column == 1
          case Some("@") =>
            caret.column == 1 || line.lift(caret.column - 2).exists(_.codePoints().allMatch(Character.isWhitespace))
          case _         => false
        if opens then request(Popup(typed.fold('@')(_.head), start, "", Vector.empty, 0))

  /** Replaces the trigger and query with the highlighted candidate and a trailing space. */
  private[tui] def acceptCompletion(): Boolean =
    popup.flatMap(open => open.items.lift(open.highlighted).map(open -> _)) match
      case Some((open, item)) =>
        val end = editor.caret
        editor.moveTo(open.start)
        editor.moveTo(end, select = true)
        editor.insert(s"${open.trigger}$item ")
        closePopup()
        true
      case None               => false

  private[tui] def moveHighlight(delta: Int): Unit =
    popup =
      popup.map(open => open.copy(highlighted = math.max(0, math.min(open.items.size - 1, open.highlighted + delta))))

  private[tui] def closePopup(): Unit =
    pending.foreach(_())
    pending = None
    popup = None

  private def request(next: Popup): Unit =
    popup = Some(next)
    pending.foreach(_())
    pending = Some(
      scheduleCompletion(
        next,
        items =>
          if popup.exists(open => open.start == next.start && open.query == next.query) then
            popup = popup.map(_.copy(items = items.toVector, highlighted = 0)),
      )
    )

  // --- large paste -----------------------------------------------------------------------------------------------

  /** Inserts a bracketed paste, folding one of at least [[pasteLines]] lines into a placeholder. */
  def paste(text: String, pastedLines: Int => String = lines => s"[Pasted $lines lines]"): Unit =
    val clean = PasteText.multiline(text)
    val lines = clean.split("\n", -1).length
    if lines >= pasteLines then
      val label       = CharWidth.withoutControls(pastedLines(lines))
      val base        = if label.nonEmpty then label else s"[Pasted $lines lines]"
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
private[tui] final case class Reverse(query: String, hit: Option[Int])

/** An open completion popup: the trigger, where it was typed, the query after it, and the provider's answer. */
private[tui] final case class Popup(
    trigger: Char,
    start: TextPosition,
    query: String,
    items: Vector[String],
    highlighted: Int,
)

/** Backend-independent composer painting over caller-owned state. Render-thread-only. A host can place the completion
  * list in a portal using [[popupAt]] and [[popupWidget]]; the base render never paints outside its area.
  */
final case class PromptComposer(
    showCursor: Boolean = true,
    style: Style = Style.Default,
    promptGlyph: String = "",
    reverseLabel: String = "(reverse-i-search)",
) extends StatefulWidget[PromptComposerState]:
  private def glyphWidth: Int                              = CharWidth.of(promptGlyph)
  private def editor(state: PromptComposerState): TextArea =
    TextArea(showCursor = showCursor && state.reverse.isEmpty, style = style, options = TextAreaOptions(wrap = true))

  private def textArea(area: Rect, state: PromptComposerState): Rect =
    val inset = math.min(glyphWidth, area.width)
    Rect(area.x + inset, area.y, area.width - inset, math.max(0, area.height - state.reverse.size))

  /** Draft height, capped at maxRows, plus the open reverse-search row. */
  def heightAt(width: Int, state: PromptComposerState): Int =
    math.max(1, math.min(state.maxRows, editor(state).heightAt(math.max(1, width - glyphWidth), state.editor))) +
      state.reverse.size

  def render(area: Rect, buffer: Buffer, state: PromptComposerState): Unit =
    if !area.isEmpty then
      val text  = textArea(area, state)
      val inset = text.x - area.x
      if inset > 0 && text.height > 0 then
        buffer.setString(area.x, area.y, CharWidth.substringByWidth(promptGlyph, inset), style)
      editor(state).render(text, buffer, state.editor)
      for cell <- editor(state).caretAt(text, state.editor); rest <- state.ghost do
        val shown = CharWidth.substringByWidth(rest.takeWhile(_ != '\n'), text.right - cell.x)
        buffer.setString(cell.x, cell.y, shown, style.dim)
        if showCursor && shown.nonEmpty then
          val current = buffer.get(cell.x, cell.y)
          buffer.set(cell.x, cell.y, current.copy(style = current.style.reverse))
      state.reverse.foreach { open =>
        val bar = s"$reverseLabel`${open.query}': ${state.reverseMatch.getOrElse("")}"
        buffer.setString(area.x, area.bottom - 1, CharWidth.substringByWidth(bar, area.width), style)
      }

  /** No caret is disclosed while reverse search covers the draft. */
  def caretAt(area: Rect, state: PromptComposerState): Option[Position] =
    if state.reverse.isDefined then None else editor(state).caretAt(textArea(area, state), state.editor)

  /** Candidate placement in the host's coordinate space, constrained by its frame boundary. */
  def popupAt(area: Rect, state: PromptComposerState, frame: Rect): Option[Rect] =
    for
      open  <- state.popup if open.items.nonEmpty
      caret <- caretAt(area, state)
    yield
      val x         = math.max(area.x, caret.x - CharWidth.of(open.query) - 1)
      val width     = math.min(area.width, open.items.iterator.map(CharWidth.of).max + 2)
      val height    = math.min(open.items.size, math.max(0, frame.height - 1))
      val roomBelow = frame.bottom - (caret.y + 1)
      val below     = roomBelow >= height || roomBelow >= caret.y - frame.y
      val top       = if below then caret.y + 1 else caret.y - height
      Rect(x, top, width, height).intersection(frame)

  /** Snapshot of the completion-list painter for an inline host or a deferred portal. */
  def popupWidget(state: PromptComposerState): Widget =
    val open = state.popup
    (area, buffer) =>
      open.foreach { popup =>
        popup.items.take(area.height).zipWithIndex.foreach { (item, index) =>
          val rowStyle = if index == popup.highlighted then style.reverse else style
          val cell     = CharWidth.substringByWidth(" " + item, area.width)
          buffer.setString(area.x, area.y + index, cell + " " * math.max(0, area.width - CharWidth.of(cell)), rowStyle)
        }
      }

object PromptComposerState:
  /** Creates render-confined state; provider execution is supplied by the host, never by this widget module. */
  def apply(
      initialHistory: Seq[String] = Nil,
      completions: (Char, String) => Seq[String] = (_, _) => Nil,
      debounce: FiniteDuration = 150.millis,
      pasteLines: Int = 10,
      maxRows: Int = 8,
  ): PromptComposerState = new PromptComposerState(initialHistory, completions, debounce, pasteLines, maxRows)

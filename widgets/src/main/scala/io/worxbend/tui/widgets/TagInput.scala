package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Position, Rect, StatefulWidget, Style, Versioned, Widget}

/** Caller-owned state of a [[TagInput]]: the committed tags, the text being typed (an ordinary [[TextInputState]], so
  * every editing key a text field knows works on it), and the optional `suggestions` the field completes from.
  *
  * Render-thread-only and `Versioned`: its [[version]] is its own mutation count plus the input's, so typing a letter
  * moves it as surely as committing a tag does.
  */
final class TagInputState(initialTags: Seq[String] = Nil, val suggestions: Seq[String] = Nil) extends Versioned:

  private var tagValues: Vector[String] = initialTags.toVector.distinct
  private var mutations: Long           = 0L

  /** The text being typed, not yet a tag. */
  val input: TextInputState = TextInputState()

  def version: Long = mutations + input.version

  /** The committed tags, oldest first. */
  def tags: Vector[String] = tagValues

  /** Replaces every tag. */
  def tags_=(values: Seq[String]): Unit =
    tagValues = values.toVector.distinct
    mutations += 1

  /** Turns the typed text, trimmed, into a tag and clears the input. `false`, with nothing changed, when the text is
    * blank or already a tag.
    */
  def commit(): Boolean =
    val text = input.value.trim
    if text.isEmpty || tagValues.contains(text) then false
    else
      tagValues :+= text
      input.clear()
      mutations += 1
      true

  /** Drops the newest tag; a no-op when there is none. */
  def removeLast(): Unit =
    if tagValues.nonEmpty then
      tagValues = tagValues.init
      mutations += 1

  /** Drops the tag at `index`; a no-op outside the tags. */
  def remove(index: Int): Unit =
    if tagValues.indices.contains(index) then
      tagValues = tagValues.patch(index, Nil, 1)
      mutations += 1

  /** The first suggestion that extends the typed text (ignoring case) and is not a tag yet — `None` with nothing typed.
    */
  def suggestion: Option[String] =
    val typed = input.value
    if typed.isEmpty then None
    else
      suggestions.find(candidate =>
        candidate.length > typed.length && candidate.regionMatches(true, 0, typed, 0, typed.length) &&
          !tagValues.contains(candidate)
      )

  /** Replaces the typed text with [[suggestion]]; `false` when there is nothing to accept. */
  def acceptSuggestion(): Boolean =
    suggestion.fold(false) { completion =>
      input.setValue(completion)
      true
    }

object TagInputState:

  /** A field holding `tags`, completing from `suggestions`. */
  def apply(tags: Seq[String] = Nil, suggestions: Seq[String] = Nil): TagInputState =
    new TagInputState(tags, suggestions)

/** A tag (chip) field: every committed tag drawn as a ` tag ` chip in `chipStyle`, followed by the text being typed,
  * all laid out by [[Flow]] so the chips wrap onto further rows when one fills. The typed text is a [[TextInput]]; a
  * suggestion that extends it shows after it in `suggestionStyle`. With no tags and nothing typed, `placeholder` shows.
  *
  * [[heightAt]] and [[caretAt]] replay the same layout for a caller sizing the field or parking the terminal cursor.
  * Stateless apart from the caller-owned [[TagInputState]]; render-thread use only.
  */
final case class TagInput(
    placeholder: String = "",
    showCursor: Boolean = true,
    style: Style = Style.Default,
    chipStyle: Style = Style.Default.reverse,
    suggestionStyle: Style = Style.Default.dim,
    cursorStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TagInputState]:

  /** The rows the field fills at `width` with `state`'s tags. */
  def heightAt(width: Int, state: TagInputState): Int = flow(state).heightAt(math.max(1, width)).getOrElse(1)

  /** Where the caret sits when the field is drawn into `area`; `None` when it would fall outside. */
  def caretAt(area: Rect, state: TagInputState): Option[Position] =
    inputArea(area, state).flatMap(rect => TextInput().caretAt(rect, state.input))

  def render(area: Rect, buffer: Buffer, state: TagInputState): Unit =
    if !area.isEmpty then flow(state).render(area, buffer)

  private def flow(state: TagInputState): Flow =
    Flow(state.tags.map(TagInput.Chip(_, chipStyle)) :+ field(state))

  private def field(state: TagInputState): TagInput.Field =
    val shown = if state.tags.isEmpty && state.input.value.isEmpty then placeholder else ""
    TagInput.Field(TextInput(shown, showCursor, style, cursorStyle), state, suggestionStyle)

  private def inputArea(area: Rect, state: TagInputState): Option[Rect] =
    if area.isEmpty then None
    else
      flow(state).placements(area.width).lastOption.map { (_, at) =>
        Rect(area.x + at.x, area.y + at.y, at.width, 1).intersection(area)
      }

object TagInput:

  /** One committed tag: ` label ` in the chip style. */
  private[widgets] final case class Chip(label: String, style: Style) extends Widget with Measured:
    private val text                               = s" $label "
    override def widthAt(height: Int): Option[Int] =
      val _ = height
      Some(CharWidth.of(text))
    override def heightAt(width: Int): Option[Int] =
      val _ = width
      Some(1)
    def render(area: Rect, buffer: Buffer): Unit   =
      if !area.isEmpty then buffer.setString(area.x, area.y, text, style, area.width)

  /** The typed text, wide enough for itself, its suggestion and the caret cell after them. */
  private[widgets] final case class Field(input: TextInput, state: TagInputState, suggestionStyle: Style)
      extends Widget
      with Measured:
    private def ghost: String                      =
      state.suggestion
        .map(completion => CharWidth.dropByWidth(completion, CharWidth.of(state.input.value)))
        .getOrElse("")
    override def widthAt(height: Int): Option[Int] =
      val _ = height
      Some(math.max(CharWidth.of(input.placeholder), CharWidth.of(state.input.value) + CharWidth.of(ghost) + 1))
    override def heightAt(width: Int): Option[Int] =
      val _ = width
      Some(1)
    def render(area: Rect, buffer: Buffer): Unit   =
      if !area.isEmpty then
        input.render(Rect(area.x, area.y, area.width, 1), buffer, state.input)
        if ghost.nonEmpty && state.input.cursor == state.input.clusterSeq.size then
          val x = area.x + CharWidth.of(state.input.value)
          buffer.setString(x, area.y, ghost, suggestionStyle, area.right - x)

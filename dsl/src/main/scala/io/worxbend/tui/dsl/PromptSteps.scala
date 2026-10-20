package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  CharWidth,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  Measured,
  Rect,
  Span,
  Style,
  VersionedState,
  Widget,
}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.widgets.TextInputState

/** What a step's own key handling decided about one key. */
private[dsl] enum StepKey:

  /** The key answers the step: validate what it holds and move on. */
  case Submit

  /** The step used the key; repaint. */
  case Handled

  /** Not the step's key: leave it to the element under focus (the text field's editing chords). */
  case Ignored

/** One question of a prompt: the interactive control, the parser a typed or scripted answer goes through, and the
  * summary line it leaves behind.
  *
  * `A` is the step's answer type. A single prompt reads its [[accepted]] value back typed; a form reads every step's as
  * `Any`, which is what `FormSpec.assemble` takes. Steps are render-thread-only while a terminal prompt runs and
  * single-threaded otherwise.
  */
private[dsl] trait PromptStep[A]:

  def label: String

  /** The answer this step was last given, kept across a Shift+Tab step back so the field shows it again. */
  var accepted: Option[A] = None

  /** Rows the step needs while it is the one being asked, including its error row. */
  def activeRows: Int

  /** The step's control. `onKey` must be attached to the focused element, so it runs before that element's own keys. */
  def view(error: Option[String], onKey: KeyEvent => Boolean)(using ReactiveScope, Theme): Element

  /** The step-specific reading of a key; [[PromptRun]] has already taken Ctrl-C and Shift+Tab. */
  def key(event: KeyEvent): StepKey

  /** What the control holds now, validated. */
  def current: Either[String, A]

  /** A typed (plain shape) or pre-supplied (scripted shape) answer, parsed as the control would have produced it. */
  def fromAnswer(raw: String): Either[String, A]

  /** The answer as the summary line shows it. */
  def show(value: A): String

  /** Whether the answer is a secret the plain shape must not echo back. */
  def masked: Boolean = false

  /** The plain shape's question, without the trailing `": "`. */
  def question: String = label

  /** Validates [[current]] and keeps it on success; the error message otherwise. */
  final def submit(): Option[String] = keep(current)

  /** Parses `raw` with [[fromAnswer]] and keeps it on success; the error message otherwise. */
  final def answer(raw: String): Option[String] = keep(fromAnswer(raw))

  private def keep(result: Either[String, A]): Option[String] =
    result match
      case Right(value)  =>
        accepted = Some(value)
        None
      case Left(message) => Some(message)

  /** `? <label> <answer>` — the line left in scrollback. */
  final def summary: String = PromptStep.summaryText(label, accepted.map(show).getOrElse(""))

  /** [[summary]] styled for the inline frame. */
  final def summaryLine(using theme: Theme): Element =
    PromptStep.summaryElement(label, accepted.map(show).getOrElse(""), theme.accent)

private[dsl] object PromptStep:

  /** What a cancelled step leaves instead of an answer: [[Messages.promptCancelled]] of the table in force. */
  def Cancelled: String = Messages.active.promptCancelled

  def summaryText(label: String, answer: String): String =
    if answer.isEmpty then s"? $label" else s"? $label $answer"

  def summaryElement(label: String, answer: String, answerStyle: Style)(using theme: Theme): Element =
    Element
      .line(
        Span("? ", theme.accent),
        Span(label, Style.Default.bold),
        Span(if answer.isEmpty then "" else " " + answer, answerStyle),
      )
      .length(1)

  /** The `? Label ` prefix of an active step. */
  def prompt(label: String)(using theme: Theme): Element =
    Element.line(Span("? ", theme.accent), Span(label + " ", Style.Default.bold)).length(CharWidth.of(label) + 3)

  def errorRow(error: Option[String])(using theme: Theme): Element =
    Element.line(Span(error.fold("")("  " + _), theme.error)).length(1)

  def isCtrl(event: KeyEvent, letter: Char): Boolean =
    event.code == KeyCode.Char(letter.toInt) && event.modifiers.hasAny(KeyModifiers.Ctrl)

  /** The longest run of grapheme clusters every word starts with — Tab's completion of several candidates. */
  def commonPrefix(words: Seq[String]): String =
    words.map(word => CharWidth.graphemeClusters(word).toVector) match
      case Seq()         => ""
      case first +: rest =>
        val length = rest.foldLeft(first.size)((n, other) => first.zip(other).take(n).takeWhile(_ == _).size)
        first.take(length).mkString

  def isPlain(event: KeyEvent, code: KeyCode): Boolean =
    event.code == code && !event.modifiers.hasAny(KeyModifiers.Ctrl | KeyModifiers.Alt)

/** A one-line text answer: `Prompt.text`, `password`, `number`, `path`, and every free-text form field.
  *
  * An empty field answers `default`. `parse` turns the raw text into the answer (and may reject it); `complete`, when
  * given, is what Tab asks for candidates: one candidate replaces the field, several replace it with their longest
  * common prefix and are listed on the hint row.
  */
private[dsl] final class TextStep[A](
    val label: String,
    default: String,
    parse: String => Either[String, A],
    display: A => String,
    override val masked: Boolean = false,
    complete: Option[String => Seq[String]] = None,
    maskGlyph: Option[String] = None,
) extends PromptStep[A]:

  val state: TextInputState      = TextInputState()
  private var hint: List[String] = Nil

  def activeRows: Int = 2

  def view(error: Option[String], onKey: KeyEvent => Boolean)(using ReactiveScope, Theme): Element =
    val field  = Element.input(state, default).autofocus.onKeyEvent(onKey)
    val shown  =
      if !masked then field
      else maskGlyph.fold(field.hidden.caretStyle(CaretStyle.Painted))(field.masked(_))
    val second = if error.isEmpty && hint.nonEmpty then Element.text("  " + hint.mkString("  ")).dim.length(1)
    else PromptStep.errorRow(error)
    Element.column(Element.row(PromptStep.prompt(label), shown.fill).length(1), second)

  def key(event: KeyEvent): StepKey =
    if PromptStep.isPlain(event, KeyCode.Enter) then StepKey.Submit
    else if PromptStep.isPlain(event, KeyCode.Tab) then complete.fold(StepKey.Ignored)(completeWith)
    else
      hint = Nil
      StepKey.Ignored

  private def completeWith(candidatesOf: String => Seq[String]): StepKey =
    val candidates = candidatesOf(state.value)
    candidates match
      case Seq()     => ()
      case Seq(only) =>
        state.setValue(only)
        hint = Nil
      case many      =>
        state.setValue(PromptStep.commonPrefix(many))
        hint = many.toList
    StepKey.Handled

  def current: Either[String, A] = fromAnswer(state.value)

  def fromAnswer(raw: String): Either[String, A] =
    parse(if raw.isEmpty then default else raw).left.map(message =>
      if masked then Messages.active.promptSecretRejected else message
    )

  def show(value: A): String = if masked then "*" * 8 else display(value)

  override def question: String = if default.isEmpty || masked then label else s"$label [$default]"

/** Yes or no: `y`/`n` answer at once, Enter takes `default`. */
private[dsl] final class ConfirmStep(val label: String, default: Boolean) extends PromptStep[Boolean]:

  def activeRows: Int = 2

  def view(error: Option[String], onKey: KeyEvent => Boolean)(using scope: ReactiveScope, theme: Theme): Element =
    val choices = if default then Messages.active.promptYesNoDefaultYes else Messages.active.promptYesNoDefaultNo
    val control = Element
      .interactiveWidget((), InteractiveHandlers[Unit](onKey = (_, event) => onKey(event))) { (_, _) =>
        PromptWidgets.textRow(choices, Style.Default.dim)
      }
      .autofocus
    Element.column(Element.row(PromptStep.prompt(label), control.fill).length(1), PromptStep.errorRow(error))

  private var pending: Option[Boolean] = None

  def key(event: KeyEvent): StepKey =
    event.code match
      case KeyCode.Char(c) if !event.modifiers.hasAny(KeyModifiers.Ctrl | KeyModifiers.Alt) =>
        Character.toLowerCase(c) match
          case 'y' =>
            pending = Some(true)
            StepKey.Submit
          case 'n' =>
            pending = Some(false)
            StepKey.Submit
          case _   => StepKey.Handled
      case KeyCode.Enter if !event.modifiers.hasAny(KeyModifiers.Ctrl | KeyModifiers.Alt)   =>
        pending = None
        StepKey.Submit
      case _                                                                                => StepKey.Ignored

  def current: Either[String, Boolean] = Right(pending.getOrElse(default))

  def fromAnswer(raw: String): Either[String, Boolean] =
    val labels = Messages.active
    val yes    = labels.promptYes.toLowerCase(java.util.Locale.ROOT)
    val no     = labels.promptNo.toLowerCase(java.util.Locale.ROOT)
    raw.trim.toLowerCase(java.util.Locale.ROOT) match
      case ""                            => Right(default)
      case "y" | "yes" | "true"          => Right(true)
      case "n" | "no" | "false"          => Right(false)
      case localized if localized == yes => Right(true)
      case localized if localized == no  => Right(false)
      case other                         =>
        Left(
          labels.filled(labels.promptAnswerYesNo, "yes" -> labels.promptYes, "no" -> labels.promptNo, "input" -> other)
        )

  def show(value: Boolean): String = if value then Messages.active.promptYes else Messages.active.promptNo

  override def question: String =
    s"$label ${if default then Messages.active.promptYesNoDefaultYes else Messages.active.promptYesNoDefaultNo}"

/** The caller-owned cursor and ticks of a choice list. Render-thread-only; every move raises the version. */
private[dsl] final class ChoiceState(size: Int, initial: Set[Int]) extends VersionedState:

  private var at: Int          = 0
  private var picked: Set[Int] = initial.filter(i => i >= 0 && i < size)

  def cursor: Int      = at
  def chosen: Set[Int] = picked

  def move(by: Int): Unit =
    if size > 0 then
      at = Math.floorMod(at + by, size)
      touch()

  def toggle(): Unit =
    if size > 0 then
      picked = if picked(at) then picked - at else picked + at
      touch()

  def select(indices: Set[Int]): Unit =
    picked = indices.filter(i => i >= 0 && i < size)
    touch()

/** One of a list (`multi = false`) or any subset of it (`multi = true`): Up/Down move, Space ticks, Enter answers.
  *
  * `A` is the whole answer — one option, or the ticked options in list order — and `pick` builds it from the indices.
  */
private[dsl] final class ChoiceStep[O, A](
    val label: String,
    options: Seq[O],
    render: O => String,
    multi: Boolean,
    pick: Seq[O] => Either[String, A],
    display: A => String,
    initial: Set[Int] = Set.empty,
    pageSize: Int = 7,
) extends PromptStep[A]:

  val state: ChoiceState = ChoiceState(options.size, initial)
  private val labels     = options.map(render).toVector
  private val visible    = math.max(1, math.min(pageSize, options.size))

  def activeRows: Int = 2 + visible

  def view(error: Option[String], onKey: KeyEvent => Boolean)(using scope: ReactiveScope, theme: Theme): Element =
    val hint    = if multi then Messages.active.promptMultiSelectHint else Messages.active.promptSelectHint
    val header  = Element.row(PromptStep.prompt(label), Element.text(hint).dim.fill).length(1)
    val handler = InteractiveHandlers[ChoiceState](onKey = (_, event) => onKey(event))
    val list    = Element
      .interactiveWidget(state, handler)((choices, _) =>
        PromptWidgets.choiceList(labels, choices, multi, visible, theme)
      )
      .autofocus
      .length(visible)
    Element.column(header, list, PromptStep.errorRow(error))

  def key(event: KeyEvent): StepKey =
    if PromptStep.isPlain(event, KeyCode.Up) || event.code == KeyCode.Char('k'.toInt) then
      state.move(-1)
      StepKey.Handled
    else if PromptStep.isPlain(event, KeyCode.Down) || event.code == KeyCode.Char('j'.toInt) then
      state.move(1)
      StepKey.Handled
    else if multi && event.code == KeyCode.Char(' '.toInt) then
      state.toggle()
      StepKey.Handled
    else if PromptStep.isPlain(event, KeyCode.Enter) then StepKey.Submit
    else StepKey.Ignored

  def current: Either[String, A] =
    if multi then pick(state.chosen.toSeq.sorted.map(options))
    else options.lift(state.cursor).fold(Left(Messages.active.promptNothingToChoose))(option => pick(Seq(option)))

  def fromAnswer(raw: String): Either[String, A] =
    if raw.trim.isEmpty then current
    else
      val wanted = if multi then raw.split(',').toSeq.map(_.trim).filter(_.nonEmpty) else Seq(raw.trim)
      val found  = wanted.map(word => word -> indexOf(word))
      found.collectFirst { case (word, None) => word } match
        case Some(word) =>
          Left(
            Messages.active
              .filled(Messages.active.promptNotAnOption, "input" -> word, "options" -> labels.mkString(", "))
          )
        case None       => pick(found.flatMap(_._2).distinct.sorted.map(options))

  /** An option by its label (case-insensitively) or by its 1-based number. */
  private def indexOf(word: String): Option[Int] =
    val byLabel = labels.indexWhere(_.equalsIgnoreCase(word))
    if byLabel >= 0 then Some(byLabel)
    else word.toIntOption.map(_ - 1).filter(labels.indices.contains)

  def show(value: A): String = display(value)

  override def question: String =
    val numbered = labels.zipWithIndex.map((text, i) => s"${i + 1}) $text").mkString(", ")
    if multi then s"$label ($numbered; ${Messages.active.promptCommaSeparated})" else s"$label ($numbered)"

/** The small widgets the steps draw with. */
private[dsl] object PromptWidgets:

  def textRow(text: String, style: Style): Widget = (area: Rect, buffer: Buffer) =>
    if !area.isEmpty then
      val _ = buffer.setString(area.x, area.y, text, style, area.width)

  /** The visible window of a choice list, scrolled so the cursor is always on it. */
  def choiceList(labels: Vector[String], state: ChoiceState, multi: Boolean, visible: Int, theme: Theme): Widget =
    new Widget with Measured:
      def render(area: Rect, buffer: Buffer): Unit   =
        if !area.isEmpty then
          val first = math.max(0, math.min(state.cursor - visible + 1, labels.size - visible)).max(0)
          val start = math.min(first, state.cursor)
          labels.indices.slice(start, start + math.min(visible, area.height)).zipWithIndex.foreach { (index, row) =>
            val here   = index == state.cursor
            val glyphs = Messages.active
            val marker = if here then glyphs.promptPointer else " " * CharWidth.of(glyphs.promptPointer)
            val tick   =
              if !multi then "" else if state.chosen(index) then glyphs.promptChecked else glyphs.promptUnchecked
            val style  = if here then theme.accent else Style.Default
            val _      = buffer.setString(area.x, area.y + row, marker + tick + labels(index), style, area.width)
          }
      override def heightAt(width: Int): Option[Int] =
        val _ = width
        Some(visible)

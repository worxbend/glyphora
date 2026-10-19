package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Color, Versioned}
import io.worxbend.tui.macros.{FieldInput, FieldSpec, FieldValidation, FormSpec}
import io.worxbend.tui.runtime.{ReactiveScope, Signal}
import io.worxbend.tui.widgets.TextInputState

/** One rendered form field: its derived spec, the control state holding its raw value, and the parser that validates
  * the raw value on submit.
  */
private[dsl] sealed trait FieldBinding:
  def spec: FieldSpec

  /** The current raw value run through the field's parser — what `submit` validates. */
  private[dsl] def parsed: Either[String, Any]

private[dsl] object FieldBinding:
  final case class TextLike(
      spec: FieldSpec,
      state: TextInputState,
      parse: String => Either[String, Any],
  ) extends FieldBinding:
    private[dsl] def parsed: Either[String, Any] = parse(state.value)

  final case class BoolLike(
      spec: FieldSpec,
      value: Signal[Boolean],
      parse: Boolean => Either[String, Any],
  ) extends FieldBinding:
    private[dsl] def parsed: Either[String, Any] = parse(value.peek)

  /** A choice between a closed set of labels. `selected` is an index into `options` rather than the label itself,
    * because that is what the one-row cycler the form renders takes; the label is looked up on submit and handed to the
    * parser, which turns it back into the field's declared type.
    */
  final case class SelectLike(
      spec: FieldSpec,
      options: Seq[String],
      selected: Signal[Int],
      parse: String => Either[String, Any],
  ) extends FieldBinding:
    private[dsl] def parsed: Either[String, Any] =
      // the only way to reach the `Left` is a picklist with no options at all, and it must not throw here: `submit`
      // runs on the render thread, where an exception takes the whole app down rather than showing a field error
      options
        .lift(selected.peek)
        .map(parse)
        .getOrElse(Left(s"no option to choose for '${spec.name}'"))

/** Live state for a derived form. Submission first parses all controls with the spec's original parsers. If parsing
  * succeeds, typed field checks validate the candidate without changing it; only a valid candidate is published. Parser
  * errors therefore precede validation errors. Writes and callbacks run on the render thread.
  *
  * [[Versioned]]: [[version]] is the sum of every text field's input version plus the number of submits, so text typed
  * into a field from outside the event path, or a submit made between frames, schedules the frame that shows it. The
  * checkbox and choice fields are signals, which schedule their own frames.
  *
  * A field can be disabled by name ([[disable]], [[enable]], [[setDisabled]]): both renderings draw it with the theme's
  * disabled style and take it out of the tab order, and [[Form.accessible]] announces it as disabled. Its value is kept
  * and still submitted — the case class needs every field — so disabling is a statement about editing, not about the
  * result.
  */
final class FormState[A] private (
    private[dsl] val bindings: Seq[FieldBinding],
    spec: FormSpec[A],
    validators: Seq[FieldValidation[A]],
) extends Versioned:
  val errors: Signal[Map[String, String]] = Signal(Map.empty)
  val result: Signal[Option[A]]           = Signal(None)
  private var submits                     = 0L

  def version: Long =
    bindings.foldLeft(submits) {
      case (sum, FieldBinding.TextLike(_, input, _)) => sum + input.version
      case (sum, _)                                  => sum
    }

  /** The names of the fields that are disabled now — read by both renderings, so a change repaints the form. */
  val disabled: Signal[Set[String]] = Signal(Set.empty)

  /** Disables the field called `name`: drawn dimmed, out of the tab order, announced as disabled. */
  def disable(name: String): Unit = setDisabled(name, true)

  /** Enables the field called `name` again. */
  def enable(name: String): Unit = setDisabled(name, false)

  /** Disables or enables the field called `name`. A name no field has is ignored. */
  def setDisabled(name: String, isDisabled: Boolean): Unit =
    if bindings.exists(_.spec.name == name) then
      disabled.set(if isDisabled then disabled.peek + name else disabled.peek - name)

  /** Whether the field called `name` is disabled, without subscribing. */
  def isDisabled(name: String): Boolean = disabled.peek.contains(name)

  /** Parses every control, then validates the typed candidate. Failure always clears the previous result. */
  def submit(): Unit =
    submits += 1
    val parsed = bindings.map(binding => binding.spec.name -> binding.parsed)
    val failed = parsed.collect { case (name, Left(message)) => name -> message }.toMap
    if failed.nonEmpty then
      errors.set(failed)
      result.set(None)
    else
      val candidate = spec.assemble(parsed.collect { case (_, Right(value)) => value })
      val invalid   = spec.validate(candidate, validators)
      errors.set(invalid)
      result.set(Option.when(invalid.isEmpty)(candidate))

object FormState:
  /** Builds state from a spec and checks made through `spec.field(_.name).validate(...)`. Standalone `Field` parsers
    * are deliberately not accepted: a UI control kind is not a Scala value type. Duplicate checks must be composed with
    * `.and`; checks belonging to another spec are rejected at construction.
    */
  def of[A](spec: FormSpec[A], validators: FieldValidation[A]*): FormState[A] =
    spec.checkValidators(validators)
    val bindings = spec.defaults.map { field =>
      val fieldSpec = field.spec
      fieldSpec.input match
        case FieldInput.BoolField            =>
          FieldBinding.BoolLike(fieldSpec, Signal(false), checked => field.parse(checked.toString))
        case FieldInput.SelectField(options) =>
          FieldBinding.SelectLike(fieldSpec, options, Signal(0), raw => field.parse(raw))
        case _                               =>
          FieldBinding.TextLike(fieldSpec, TextInputState(), raw => field.parse(raw))
    }
    new FormState(bindings, spec, validators)

/** Renders a [[FormState]] as labeled controls with inline validation errors, composed from `input`/`checkbox` so it
  * inherits focus traversal for free.
  */
object Form:

  /** The traversal both renderings share: one column of fields, each optionally followed by its validation error.
    *
    * `field` renders one binding (the index is its zero-based position, which only [[accessible]] announces) and
    * `errorText` decorates the failure message for that rendering. Reading `state.errors` here is what subscribes the
    * caller's `ReactiveScope`, so a failed submit repaints the form.
    */
  private def fieldColumn[A](state: FormState[A], errorText: String => String)(
      field: (FieldBinding, Int) => Element
  )(using ReactiveScope): Element =
    val currentErrors = state.errors.get
    val off           = state.disabled.get
    val rows          = state.bindings.zipWithIndex.flatMap { (binding, index) =>
      val error = currentErrors.get(binding.spec.name).map(message => errorRow(errorText(message)))
      field(binding, index).disabled(off.contains(binding.spec.name)) +: error.toSeq
    }
    Element.column(rows*)

  /** The single-row rendering of a validation failure, shared by [[apply]] and [[accessible]] so a change to how an
    * error looks lands in both renderings at once. The two differ only in the text they hand in.
    */
  private def errorRow(message: String): Element =
    Element.text(message).fg(Color.Red).length(1)

  /** The control a text-like field is edited with, chosen from what the derivation said the field holds.
    *
    * A numeric field rendered as a plain text input accepts any keystroke, so typing `abc` into an `age` field looks
    * accepted right up until submit answers `'abc' is not a whole number`. `numberInput` refuses the keystroke instead:
    * it claims the same single row and takes the same [[TextInputState]], so it swaps in without changing anything
    * around it. The parser still runs on submit — this only stops the user reaching it with input that cannot parse.
    */
  private def textControl(spec: FieldSpec, state: TextInputState): Element =
    spec.input match
      case FieldInput.IntField     => Element.numberInput(state)
      case FieldInput.DecimalField => Element.numberInput(state).decimal
      case _                       => Element.input(state)

  def apply[A](state: FormState[A])(using ReactiveScope): Element =
    // display columns, not UTF-16 lengths: a field named with CJK or emoji characters otherwise gets a label column
    // narrower than it renders into, and the error line below it no longer lines up with the input
    val labelWidth = state.bindings.map(binding => CharWidth.of(binding.spec.name)).maxOption.getOrElse(0) + 2
    fieldColumn(state, message => s"${" ".repeat(labelWidth)}! $message") { (binding, _) =>
      binding match
        case FieldBinding.TextLike(spec, inputState, _)          =>
          Element
            .row(
              Element.text(s"${spec.name}:").length(labelWidth),
              textControl(spec, inputState).fill,
            )
            .length(1)
        case FieldBinding.BoolLike(spec, value, _)               =>
          Element.checkbox(spec.name, value)
        case FieldBinding.SelectLike(spec, options, selected, _) =>
          // the one-row cycler rather than the popup `dropdown`: the dropdown's list floats over the rows below it
          // (it no longer pushes them down), but it would cover the next fields while open and needs an extra
          // keystroke to open and commit, where the cycler changes value in place with the arrows on a row that
          // never hides another one
          Element
            .row(
              Element.text(s"${spec.name}:").length(labelWidth),
              Element.select(options, selected).fill,
            )
            .length(1)
    }

  /** A screen-reader-friendly rendering of the same [[FormState]] (Huh's `WithAccessible`): every field on its own
    * labeled line announced as "Field N of M", checkbox state and a disabled field spelled out as text, and validation
    * failures prefixed with "Error:" rather than signalled by color alone. Pair with a plain Tab/Enter key flow for
    * assistive tech.
    */
  def accessible[A](state: FormState[A])(using ReactiveScope): Element =
    val total = state.bindings.size
    val off   = state.disabled.get
    fieldColumn(state, message => s"Error: $message") { (binding, index) =>
      // a disabled field says so in words, as its checkbox state and choice are, rather than by being dimmed alone
      val position                         = s"Field ${index + 1} of $total"
      def status(details: String*): String =
        val all = details ++ Option.when(off.contains(binding.spec.name))("disabled")
        if all.isEmpty then "" else all.mkString(" (", ", ", ")")
      binding match
        case FieldBinding.TextLike(spec, inputState, _)          =>
          Element.column(
            Element.text(s"$position: ${spec.name}${status()}").length(1),
            textControl(spec, inputState).fill.length(1),
          )
        case FieldBinding.BoolLike(spec, value, _)               =>
          val announced = if value.get then "checked" else "unchecked"
          Element.column(
            Element.text(s"$position: ${spec.name}${status(announced)}").length(1),
            Element.checkbox(spec.name, value).length(1),
          )
        case FieldBinding.SelectLike(spec, options, selected, _) =>
          // the chosen label is spelled out rather than left to the highlight, which is a colour and announces nothing
          val announced = options.lift(selected.get).getOrElse("nothing to choose from")
          Element.column(
            Element.text(s"$position: ${spec.name}${status(announced, s"${options.size} options")}").length(1),
            Element.select(options, selected).length(1),
          )
    }

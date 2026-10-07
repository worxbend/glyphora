package io.worxbend.tui.macros

import java.time.{Duration, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID

import scala.annotation.implicitNotFound
import scala.collection.immutable.TreeMap
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror
import scala.util.Try

/** How one *type* becomes one form field: which control the user types into, and how the text they typed turns back
  * into a value of that type.
  *
  * [[deriveForm]] summons one of these per case-class field. That is the whole extension point: a type the library has
  * never heard of joins the derivation the moment a `given FormFieldType[ThatType]` is in its implicit scope — which
  * for a type you declared yourself means "next to it, in its companion object".
  *
  * {{{
  * opaque type Email = String
  *
  * object Email:
  *   def from(raw: String): Either[String, Email] =
  *     if raw.contains("@") then Right(raw) else Left(s"'\$raw' is not an email address")
  *
  *   given FormFieldType[Email] = FormFieldType(FieldInput.TextField)(Email.from)
  *
  * final case class Signup(email: Email, age: Int)
  * deriveForm[Signup] // compiles: Email brought its own instance
  * }}}
  *
  * Pure and thread-agnostic: `parse` reads its argument and allocates a result, touching no shared state, so it is safe
  * to call from any thread including the render thread. An implementation that blocks or mutates shared state breaks
  * that promise — the form calls `parse` on the render thread when the user submits.
  */
@implicitNotFound(
  "deriveForm: no form control is defined for a field of type ${A}. Out of the box String, Int, Long, Double, " +
    "BigDecimal, Boolean, java.util.UUID, java.time.LocalDate/LocalTime/LocalDateTime/Duration and Option of those " +
    "are supported; define a `given FormFieldType[${A}]` next to your own type to teach the derivation about it. " +
    "An enum whose cases all take no parameters can say " +
    "`given FormFieldType[${A}] = FormFieldType.ofEnum[${A}]` in its companion."
)
trait FormFieldType[A]:

  /** The control this type is edited with. */
  def input: FieldInput

  /** Turns the raw text of the control into an `A`, or into the message the form shows next to the field. */
  def parse(raw: String): Either[String, A]

  /** The parser a derived field gets; typed validation checks its value without replacing this parser.
    *
    * It lives here rather than in [[deriveForm]] because a `FormFieldType[?]` cannot be taken apart from the outside:
    * writing `Field(spec, control.parse)` against a wildcard instance loses the connection between the parser's result
    * type and the instance's own, and no longer type-checks.
    */
  final def field(name: String): Field[A] = Field(FieldSpec(name, input), parse)

object FormFieldType:

  /** Builds an instance from the control to render and the parser to run — the shape an application writes when
    * teaching the derivation about one of its own types.
    */
  def apply[A](control: FieldInput)(parser: String => Either[String, A]): FormFieldType[A] =
    new FormFieldType[A]:
      def input: FieldInput                     = control
      def parse(raw: String): Either[String, A] = parser(raw)

  given text: FormFieldType[String] = apply(FieldInput.TextField)(raw => Right(raw))

  given int: FormFieldType[Int] =
    optional(FieldInput.IntField)(_.toIntOption)(raw => s"'$raw' is not a whole number")

  /** A floating-point number, edited through the decimal control it shares with [[bigDecimal]]. Named `double` after
    * its type — the instance was `decimal` before 0.15.0, renamed so the given and its [[Field.double]] factory carry
    * the same name.
    */
  given double: FormFieldType[Double] =
    optional(FieldInput.DecimalField)(_.toDoubleOption)(raw => s"'$raw' is not a number")

  given bool: FormFieldType[Boolean] =
    optional(FieldInput.BoolField)(_.toBooleanOption)(raw => s"'$raw' is not true/false")

  /** Builds an instance around a parser that reports a bad value by *throwing* — which is how every parser in the JDK's
    * `java.time` and `java.util.UUID` reports one.
    *
    * The exception is caught here and turned into the `Left` message the form shows next to the field. That conversion
    * is not a stylistic preference: `parse` is called on the render thread when the user submits, so an escaping
    * `DateTimeParseException` would tear down the render loop and end the application, where a `Left` is a line of red
    * text the user can correct. The raw text is trimmed before parsing, matching the built-in `Int`/`Double` instances,
    * so a value the user typed with a stray leading space is still accepted.
    *
    * @param control
    *   the control the field is edited with
    * @param parser
    *   turns already-trimmed text into a value, or throws
    * @param message
    *   builds the message for the *untrimmed* text the user actually typed
    */
  private def catching[A](control: FieldInput)(parser: String => A)(message: String => String): FormFieldType[A] =
    apply(control)(raw => Try(parser(raw.trim)).toOption.toRight(message(raw)))

  /** The `Option`-returning counterpart to [[catching]], for parsers like `String.toIntOption` that already report a
    * bad value by returning `None`.
    *
    * @param control
    *   the control the field is edited with
    * @param parser
    *   turns already-trimmed text into a value, or `None`
    * @param message
    *   builds the message for the *untrimmed* text the user actually typed
    */
  private def optional[A](control: FieldInput)(parser: String => Option[A])(
      message: String => String
  ): FormFieldType[A] =
    apply(control)(raw => parser(raw.trim).toRight(message(raw)))

  /** A whole number too large for `Int`. It shares the whole-number control with `Int`, because the two are the same
    * *kind* of entry to the person filling the form; only the range differs.
    */
  given long: FormFieldType[Long] =
    optional(FieldInput.IntField)(_.toLongOption)(raw => s"'$raw' is not a whole number")

  /** An exact decimal — money, quantities — where `Double`'s binary rounding would be wrong. Same decimal control as
    * `Double`.
    */
  given bigDecimal: FormFieldType[BigDecimal] =
    catching(FieldInput.DecimalField)(BigDecimal(_))(raw => s"'$raw' is not a number")

  /** A universally unique identifier, typed in its usual hyphenated form, for example
    * `123e4567-e89b-12d3-a456-426614174000`.
    */
  given uuid: FormFieldType[UUID] =
    catching(FieldInput.TextField)(UUID.fromString)(raw => s"'$raw' is not a UUID")

  /** A calendar date in ISO-8601 form, `YYYY-MM-DD`. It renders as a plain text field because this library has no
    * calendar picker yet; when one arrives it becomes a new [[FieldInput]] case and this instance changes with it.
    */
  given localDate: FormFieldType[LocalDate] =
    catching(FieldInput.TextField)(LocalDate.parse)(raw => s"'$raw' is not a date (YYYY-MM-DD)")

  /** A time of day in ISO-8601 form, `HH:MM` or `HH:MM:SS`. Text field, for the same reason as [[localDate]]. */
  given localTime: FormFieldType[LocalTime] =
    catching(FieldInput.TextField)(LocalTime.parse)(raw => s"'$raw' is not a time (HH:MM)")

  /** A date and time in ISO-8601 form, the two joined by a literal `T`: `2026-09-01T14:30`. */
  given localDateTime: FormFieldType[LocalDateTime] =
    catching(FieldInput.TextField)(LocalDateTime.parse)(raw => s"'$raw' is not a date and time (YYYY-MM-DDTHH:MM)")

  /** A length of time in ISO-8601 duration form: `PT5M30S` is five minutes and thirty seconds, `PT2H` is two hours. */
  given duration: FormFieldType[Duration] =
    catching(FieldInput.TextField)(Duration.parse)(raw => s"'$raw' is not a duration (ISO-8601, e.g. PT5M30S)")

  /** A picklist over an enum whose cases all take no parameters: every case's name becomes one option, and the label
    * the user chose is matched back to the case value.
    *
    * Compile-time only. `constValueTuple` reads the case names out of the type and `summonAll` collects the singleton
    * values, both during compilation, so nothing here reads a class at runtime and a native image needs no reflection
    * configuration for it.
    *
    * This is opt-in rather than an automatic `given`, and deliberately so. An unconditional
    * `given [A](using Mirror.SumOf[A])` would sit in the same implicit scope as [[option]] — `Option` has a
    * `Mirror.SumOf` of its own — and would either shadow it or make the search ambiguous. It would also quietly claim
    * every sealed hierarchy whose cases happen to take no parameters, including ones that are not choices a user should
    * be offered. Opting in is one line, in the place the extension point already documents:
    *
    * {{{
    * enum Role:
    *   case Admin, Viewer
    *
    * object Role:
    *   given FormFieldType[Role] = FormFieldType.ofEnum[Role]
    * }}}
    *
    * A case that takes parameters has no `ValueOf`, so this fails to compile naming that case rather than producing a
    * picklist that cannot represent it.
    */
  inline def ofEnum[A](using mirror: Mirror.SumOf[A]): FormFieldType[A] =
    val labels = constValueTuple[mirror.MirroredElemLabels].toList.map(_.toString)
    // every element is a `ValueOf[C]` for one of the enum's own case types, so each `.value` is already an `A`; the
    // cast below is what carries that fact past `ValueOf`'s wildcard element type, which the compiler cannot track
    // through the tuple.
    //
    // `productIterator`, not the `Tuple.toList` that reads more naturally here. `toList` types its elements as the
    // *union* of the tuple's element types, and on this compiler a union of exactly two `ValueOf`s — an enum with
    // exactly two cases — erases the type test below into a call to a cast method that exists only inside the
    // compiler, so the given built fine and threw `NoSuchMethodError` the first time anything forced it. One case and
    // three or more cases were unaffected, which is what kept it hidden. Iterating the tuple as `Any` leaves the test
    // an ordinary class check at every arity.
    val values = summonAll[Tuple.Map[mirror.MirroredElemTypes, ValueOf]].productIterator.toList.map {
      case singleton: ValueOf[?] =>
        // `summonAll` erases the tuple's element types to `ValueOf[?]`, so the fact that each element is a
        // `ValueOf[C]` for one of A's own case types — and its `.value` therefore already an `A` — cannot be stated
        // to the compiler here. Pattern matching cannot recover it either: the type argument is gone at runtime, so
        // there is nothing left to match on.
        singleton.value.asInstanceOf[A] // scalafix:ok DisableSyntax; the erased case type cannot be recovered
    }
    ofLabels(labels.zip(values))

  /** A picklist over labels and values supplied by hand — the non-inline half of [[ofEnum]], and the way to build one
    * whose labels are not the case names ("Administrator" for `Admin`, a translated label).
    *
    * It is a plain method rather than `inline` for the same reason `FormSpec.ofProduct` is: [[ofEnum]] is inlined into
    * every call site, so keeping the matching and the error message here means the program holds one copy of them
    * instead of one per call.
    *
    * Matching ignores surrounding whitespace and case on both declared labels and submitted text. Display labels keep
    * their original spelling. Ambiguous labels fail at construction rather than silently submitting the first value. An
    * empty `options` produces a field that rejects everything.
    *
    * @throws IllegalArgumentException
    *   if two labels compare equal after trimming, ignoring case
    */
  def ofLabels[A](options: Seq[(String, A)]): FormFieldType[A] =
    val empty   = TreeMap.empty[String, A](using Ordering.comparatorToOrdering(using String.CASE_INSENSITIVE_ORDER))
    val choices = options.foldLeft(empty) { case (indexed, (label, value)) =>
      val key = label.trim
      require(!indexed.contains(key), s"duplicate form option label '$label' (ignoring whitespace and case)")
      indexed.updated(key, value)
    }
    apply(FieldInput.SelectField(options.map(_._1))) { raw =>
      choices.get(raw.trim).toRight(s"'$raw' is not one of ${options.map(_._1).mkString(", ")}")
    }

  /** An optional field renders with the same control as the type inside it, and treats blank input as "not given"
    * rather than as a parse failure. Anything else is handed to the inner instance, so `Option[Int]` still rejects
    * `"abc"` with the message `Int` would have given.
    *
    * One combination is optional in name only: an `Option[Boolean]` renders as a checkbox, and a checkbox is always
    * either ticked or not, so the field always submits a `Some`. Declare a plain `Boolean` unless the `Option` means
    * something to the rest of your program.
    */
  given option[A](using inner: FormFieldType[A]): FormFieldType[Option[A]] =
    apply(inner.input)(raw => if raw.trim.isEmpty then Right(None) else inner.parse(raw).map(Some(_)))

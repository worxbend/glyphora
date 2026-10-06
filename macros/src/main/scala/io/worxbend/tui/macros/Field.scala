package io.worxbend.tui.macros

import java.time.{Duration, LocalDate, LocalDateTime, LocalTime}
import java.util.UUID

import scala.deriving.Mirror

/** A form field with parsing and validation, composing cue4s-style: transforms are stored lazily and run when the
  * field's raw input is submitted.
  */
final case class Field[A](spec: FieldSpec, parse: String => Either[String, A]):

  /** Runs `f` on every successfully parsed value, leaving parse failures untouched.
    *
    * This standalone parser can change its result type. It cannot be attached to a derived form: use a `FormSpec.field`
    * handle for validation, or supply a `FormFieldType[B]` for domain parsing.
    */
  def map[B](f: A => B): Field[B] =
    mapValidated(value => Right(f(value)))

  /** Chains a validation step onto the parser: `f` may reject a parsed value by returning `Left(message)`, and that
    * message is what the form shows next to the field.
    *
    * A standalone parser transformation, separate from the type-preserving checks on a derived field handle.
    */
  def mapValidated[B](f: A => Either[String, B]): Field[B] =
    Field(spec, raw => parse(raw).flatMap(f))

/** Standalone parsers. `map` and `mapValidated` may change their result type, but `FormState.of` accepts only
  * spec-owned [[FieldValidation]] rules, never these parsers. Customize derivation through [[FormFieldType]] instead.
  */
object Field:

  def text(name: String): Field[String] = FormFieldType.text.field(name)

  def int(name: String): Field[Int] = FormFieldType.int.field(name)

  def double(name: String): Field[Double] = FormFieldType.double.field(name)

  def bool(name: String): Field[Boolean] = FormFieldType.bool.field(name)

  def long(name: String): Field[Long] = FormFieldType.long.field(name)

  def bigDecimal(name: String): Field[BigDecimal] = FormFieldType.bigDecimal.field(name)

  def uuid(name: String): Field[UUID] = FormFieldType.uuid.field(name)

  def localDate(name: String): Field[LocalDate] = FormFieldType.localDate.field(name)

  def localTime(name: String): Field[LocalTime] = FormFieldType.localTime.field(name)

  def localDateTime(name: String): Field[LocalDateTime] = FormFieldType.localDateTime.field(name)

  def duration(name: String): Field[Duration] = FormFieldType.duration.field(name)

  /** A standalone enum parser with the options supplied by [[FormFieldType.ofEnum]]. For a derived form's checks, use
    * its typed field handle instead.
    */
  inline def enumeration[A](name: String)(using Mirror.SumOf[A]): Field[A] =
    FormFieldType.ofEnum[A].field(name)

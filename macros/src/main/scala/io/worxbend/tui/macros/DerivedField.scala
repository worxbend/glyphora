package io.worxbend.tui.macros

import scala.quoted.*

/** A field selected from one derived spec, with its exact declared Scala value type.
  *
  * Validation can reject a value, never replace it. Parser transformations belong to [[Field]] or [[FormFieldType]],
  * not to this handle. The constructor is internal: only a compile-time checked case-class field selection creates one.
  */
final class DerivedField[A, V] private[macros] (
    owner: FormSpec[A],
    val name: String,
    select: A => V,
):
  /** Runs a pure check on the parsed value. `Right(())` accepts the original value unchanged. */
  def validate[R](check: V => Either[String, R])(using acceptsOnly: R <:< Unit): FieldValidation[A] =
    // Infer R before requiring Unit: a directly expected Unit otherwise lets Scala silently discard a replacement
    // expression, such as Right(value.toString). Nothing is accepted so an always-failing Left remains ergonomic.
    FieldValidation.checked(owner, name, value => check(select(value)).map(acceptsOnly))

  /** Predicate shorthand for [[validate]]. */
  def validate(predicate: V => Boolean, message: String): FieldValidation[A] =
    validate(value => Either.cond(predicate(value), (), message))

/** An immutable, spec-owned validation rule. No public constructor or parser replacement channel is exposed. */
sealed abstract class FieldValidation[A] private[macros] ():
  def name: String
  private[macros] def owner: FormSpec[A]
  private[macros] def check(value: A): Either[String, Unit]

  /** Composes another check on the same candidate; the first failure wins for this field. */
  final def and(next: FieldValidation[A]): FieldValidation[A] =
    if (owner ne next.owner) || name != next.name then
      throw IllegalArgumentException("composed validators must belong to the same spec and field")
    FieldValidation.checked(owner, name, value => check(value).flatMap(_ => next.check(value)))

object FieldValidation:
  private[macros] def checked[A](
      spec: FormSpec[A],
      fieldName: String,
      validate: A => Either[String, Unit],
  ): FieldValidation[A] =
    new FieldValidation[A]:
      val name: String                                          = fieldName
      private[macros] def owner: FormSpec[A]                    = spec
      private[macros] def check(value: A): Either[String, Unit] = validate(value)

/** Checks syntax and exact type identity while the compiler still knows opaque and generic field types. */
private[macros] object DerivedFieldSelection:
  inline def fieldName[A, V](inline selector: A => V): String = ${ name[A, V]('selector) }

  def name[A: Type, V: Type](selector: Expr[A => V])(using Quotes): Expr[String] =
    import quotes.reflect.*

    def unwrap(term: Term): Term = term match
      case Inlined(_, _, body) => unwrap(body)
      case Typed(body, _)      => unwrap(body)
      case other               => other

    val selected = unwrap(selector.asTerm) match
      case Lambda(List(parameter), body) =>
        unwrap(body) match
          case selection @ Select(receiver, _) if unwrap(receiver).symbol == parameter.symbol => selection
          case _ => report.errorAndAbort("select a direct case-class field, for example _.age", selector)
      case _ => report.errorAndAbort("select a direct case-class field, for example _.age", selector)

    val fields   = TypeRepr.of[A].typeSymbol.caseFields
    if !fields.contains(selected.symbol) then
      report.errorAndAbort("select a declared case-class field, not a method or a computed value", selector)
    val declared = TypeRepr.of[A].memberType(selected.symbol)
    if !(declared =:= TypeRepr.of[V]) then
      report.errorAndAbort(
        s"the handle must retain the field's exact declared type ${declared.show}; got ${TypeRepr.of[V].show}",
        selector,
      )
    Expr(selected.name)

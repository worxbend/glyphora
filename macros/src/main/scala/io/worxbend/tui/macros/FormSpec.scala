package io.worxbend.tui.macros

/** What kind of input control a form field needs — chosen by the field type's [[FormFieldType]]. */
enum FieldInput:
  case TextField
  case IntField
  case DecimalField
  case BoolField

  /** A choice between a closed set of labels, rather than free text.
    *
    * The labels travel with the case because they are what the control has to draw and what the parser has to match
    * against, and because [[FormSpec]] carries no other channel between the derivation and the renderer. This describes
    * the control only, never the Scala value type: typed validation uses a [[DerivedField]], not input-kind equality.
    */
  case SelectField(options: Seq[String])

/** One field of a derived form: the case-class field name and its input kind. */
final case class FieldSpec(name: String, input: FieldInput)

/** A compile-time-derived description of a form for `A`: the controls, their original per-type parsers, and internal
  * product assembly. Produced by [[deriveForm]]; owned by `tui-macros` so `tui-dsl` can consume it without a circular
  * dependency.
  *
  * Consumers inspect [[fields]] and obtain typed handles with [[field]]. The existential default parsers and `Seq[Any]`
  * product assembly are internal to the library: only the original derived parsers supply those values, in declaration
  * order. Validators check the resulting `A` and cannot replace any field value. The arity assertion protects this
  * internal seam; no runtime type test or reflection is used to recover erased field types.
  *
  * @param defaults
  *   one [[Field]] per case-class field, in declaration order, each already carrying the [[FieldSpec]] and the parser
  *   its type's [[FormFieldType]] chose. Holding the whole `Field` — rather than only its spec — is what lets a field
  *   type nobody in this module knows about supply its own parser: there is no step that turns a `FieldInput` back into
  *   a parser, so the set of supported types never has to be closed.
  * @param assemble
  *   builds an `A` from one value per field, subject to the contract above
  */
final class FormSpec[A] private[macros] (
    private[tui] val defaults: Seq[Field[?]],
    private[tui] val assemble: Seq[Any] => A,
):

  /** The fields to render, in the case class's declaration order. */
  def fields: Seq[FieldSpec] = defaults.map(_.spec)

  /** Selects a direct case-class field without erasing its declared type (including opaque domain types). A computed
    * expression, method, or explicitly widened value type is a compile error.
    */
  inline def field[V](inline select: A => V): DerivedField[A, V] =
    selectedField(DerivedFieldSelection.fieldName[A, V](select), select)

  private def selectedField[V](name: String, select: A => V): DerivedField[A, V] =
    if !fields.exists(_.name == name) then throw IllegalArgumentException(s"the form does not declare field '$name'")
    new DerivedField(this, name, select)

  /** Checks declarations once, before the render thread starts submitting values. */
  private[tui] def checkValidators(validators: Seq[FieldValidation[A]]): Unit =
    validators.foreach { validator =>
      if validator.owner ne this then
        throw IllegalArgumentException(s"validator for '${validator.name}' belongs to a different form spec")
    }
    val repeated = validators.groupBy(_.name).collect { case (name, rules) if rules.sizeIs > 1 => name }
    if repeated.nonEmpty then
      throw IllegalArgumentException(
        s"more than one validator for field(s) ${repeated.mkString(", ")}; compose with .and"
      )

  /** Validates an already parsed candidate without transforming any of its values. */
  private[tui] def validate(value: A, validators: Seq[FieldValidation[A]]): Map[String, String] =
    validators.flatMap(rule => rule.check(value).left.toOption.map(rule.name -> _)).toMap

object FormSpec:

  /** Builds a [[FormSpec]] from already-derived `fields` and the `Mirror.fromProduct` of the target case class.
    *
    * This is a plain (non-inline) method on purpose. `deriveForm` is `inline`, so everything written in its body is
    * copied into every call site; keeping the arity check and its error message here means one copy of that code exists
    * for the whole program instead of one per `deriveForm` call.
    *
    * @param fields
    *   the derived fields, in the case class's declaration order
    * @param fromProduct
    *   the mirror's constructor: turns a tuple of field values into an `A`
    */
  private[macros] def ofProduct[A](fields: Seq[Field[?]], fromProduct: Product => A): FormSpec[A] =
    new FormSpec(
      fields,
      values =>
        if values.sizeIs != fields.size then
          throw IllegalArgumentException(
            s"assembling a form value needs one value per field, in order — ${fields.size} " +
              s"(${fields.map(_.spec.name).mkString(", ")}) — but got ${values.size}"
          )
        fromProduct(Tuple.fromArray(values.toArray)),
    )

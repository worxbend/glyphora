package io.worxbend.tui.macros

import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

/** Derives a [[FormSpec]] for a case class at compile time via `Mirror.ProductOf` — field names become field specs, and
  * each field's *type* chooses its control and its parser by way of the [[FormFieldType]] summoned for it (`String` →
  * text, `Int`/`Long` → whole-number, `Double`/`BigDecimal` → decimal, `Boolean` → checkbox, `java.util.UUID` and the
  * `java.time` value types → text, `Option[A]` → the same control as `A` with blank meaning `None`). A field type with
  * no instance in scope is a compile error, not a runtime surprise, and a type of your own joins the set by declaring
  * `given FormFieldType[YourType]` in its companion — see [[FormFieldType]].
  *
  * The returned spec exposes typed handles for direct case-class field selections. Its original parsers and ordered
  * product assembly remain internal; validation checks the typed candidate and never replaces field values.
  *
  * No runtime reflection anywhere: `constValueTuple` and `summonAll` are resolved during compilation and `fromProduct`
  * is a direct call, so a native image needs no reflect-config entry for the derived type.
  *
  * One diagnostic is deliberately weaker than it could be: the compile error names the *type* that has no instance, not
  * the *field* that carries it. Pairing each label with its type so the message could say both makes the compiler print
  * the name as `("age" : String)` followed by several lines of implicit-search trace, which reads worse than the short
  * message. In a case class with two fields of the same unsupported type, look for the type rather than the name.
  */
inline def deriveForm[A](using m: Mirror.ProductOf[A]): FormSpec[A] =
  val names    = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
  // `summonAll` is a single stdlib macro over the whole tuple rather than a recursive `inline def` per field, which
  // matters for more than tidiness: an inline method that called itself once per field would spend one frame of the
  // *caller's* `-Xmax-inlines` budget (32 by default) per field, so a wide enough case class stopped compiling with a
  // message about inline depth that named nothing in this library.
  val controls = summonAll[Tuple.Map[m.MirroredElemTypes, FormFieldType]].toList.map { case control: FormFieldType[?] =>
    control
  }
  FormSpec.ofProduct(names.zip(controls).map((name, control) => control.field(name)), m.fromProduct)

package io.worxbend.tui.macros

import java.util.Locale

import scala.annotation.implicitNotFound
import scala.compiletime.{constValue, constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror

/** One described map entry. Immutable and thread-agnostic; owned by the description builder. */
final case class PrettyEntry[R](key: R, value: R)

/** One labelled product field. Immutable and thread-agnostic; owned by the description builder. */
final case class PrettyField[R](name: String, value: R)

/** What a [[Pretty]] describes a value *to*: four ways to build a description of type `R`.
  *
  * The description is built through this interface rather than returned as a data type of this module's own because
  * `tui-macros` sits below every other module and names nothing from them: the DSL's `inspect(value)` hands in a shape
  * that builds `tui-widgets`' `PrettyValue`, a test hands in one that spells a string, and neither module has to know
  * about the other.
  *
  * @tparam R
  *   the description being built
  */
trait PrettyShape[R]:

  /** A leaf already in display form: `42`, `"text"`, `None`. */
  def atom(text: String): R

  /** A collection named `name` (`List`, `Set`, `Some`) holding `items` in order. */
  def sequence(name: String, items: Seq[R]): R

  /** A key-value collection named `name`. */
  def map(name: String, entries: Seq[PrettyEntry[R]]): R

  /** A record — a case class, an enum case, a case object (with no fields) — named `name`, with labelled fields. */
  def product(name: String, fields: Seq[PrettyField[R]]): R

/** How a value of type `A` is described for display — the typeclass behind the DSL's `inspect(value)`.
  *
  * Instances for the primitives, `String` (quoted and escaped), `Option` and the immutable collections are in the
  * companion, and so is [[Pretty.derived]], which hands any case class, enum or sealed hierarchy the instance
  * [[derivePretty]] builds through its `Mirror` — no reflection, so a native image needs no configuration. A type of
  * your own that wants a different form declares a `given Pretty[YourType]` beside it, usually through [[Pretty.atom]];
  * a given in lexical or companion scope wins over the derivation, which is also how a field holding a secret is kept
  * off the screen.
  *
  * Pure and thread-agnostic: `describe` touches only its arguments.
  */
@implicitNotFound(
  "no Pretty is defined for ${A}. Primitives, String, Option, Seq, List, Vector, Set and Map of those, and any case " +
    "class, enum or sealed hierarchy over them, are supported out of the box; declare a `given Pretty[${A}]` through " +
    "Pretty.atom next to your own type to teach the derivation about it."
)
trait Pretty[A]:

  /** `value` described to `shape`. */
  def describe[R](value: A, shape: PrettyShape[R]): R

object Pretty:

  /** An instance whose whole description is one atom of `show(value)`. */
  def atom[A](show: A => String): Pretty[A] =
    new Pretty[A]:
      def describe[R](value: A, shape: PrettyShape[R]): R = shape.atom(show(value))

  given string: Pretty[String]         = atom(quoted(_, '"'))
  given char: Pretty[Char]             = atom(c => quoted(c.toString, '\''))
  given int: Pretty[Int]               = atom(_.toString)
  given long: Pretty[Long]             = atom(_.toString)
  given short: Pretty[Short]           = atom(_.toString)
  given byte: Pretty[Byte]             = atom(_.toString)
  given double: Pretty[Double]         = atom(_.toString)
  given float: Pretty[Float]           = atom(_.toString)
  given boolean: Pretty[Boolean]       = atom(_.toString)
  given bigInt: Pretty[BigInt]         = atom(_.toString)
  given bigDecimal: Pretty[BigDecimal] = atom(_.toString)

  given option[A](using inner: Pretty[A]): Pretty[Option[A]] =
    new Pretty[Option[A]]:
      def describe[R](value: Option[A], shape: PrettyShape[R]): R =
        value match
          case Some(a) => shape.sequence("Some", Seq(inner.describe(a, shape)))
          case None    => shape.atom("None")

  private def collection[A, C <: Iterable[A]](name: String, inner: Pretty[A]): Pretty[C] =
    new Pretty[C]:
      def describe[R](value: C, shape: PrettyShape[R]): R =
        shape.sequence(name, value.iterator.map(inner.describe(_, shape)).toSeq)

  given seq[A](using inner: Pretty[A]): Pretty[Seq[A]]       = collection("Seq", inner)
  given list[A](using inner: Pretty[A]): Pretty[List[A]]     = collection("List", inner)
  given vector[A](using inner: Pretty[A]): Pretty[Vector[A]] = collection("Vector", inner)
  given set[A](using inner: Pretty[A]): Pretty[Set[A]]       = collection("Set", inner)

  given map[K, V](using key: Pretty[K], entry: Pretty[V]): Pretty[Map[K, V]] =
    new Pretty[Map[K, V]]:
      def describe[R](value: Map[K, V], shape: PrettyShape[R]): R =
        shape.map("Map", value.toSeq.map((k, v) => PrettyEntry(key.describe(k, shape), entry.describe(v, shape))))

  /** [[derivePretty]] as a given, so a case class, enum or sealed hierarchy needs no declaration and nested types are
    * reached through the ordinary search. The hand-written instances above are more specific and keep precedence.
    */
  inline given derived[A](using mirror: Mirror.Of[A]): Pretty[A] = derivePretty[A]

  /** The instance of a product named `name` whose fields, labelled `labels`, are described by `fields` in order. Shared
    * by every [[derivePretty]] call site rather than inlined into each.
    */
  def product[A](name: String, labels: List[String], fields: List[Pretty[?]]): Pretty[A] =
    new Pretty[A]:
      def describe[R](value: A, shape: PrettyShape[R]): R =
        // a `Mirror.ProductOf[A]` exists only for case classes and singleton cases, every one of which is a `Product`
        val product   = value.asInstanceOf[Product] // scalafix:ok DisableSyntax; mirrored products are Products
        val described = product.productIterator.zip(fields).map { (field, pretty) =>
          // each instance was summoned for exactly the field type at this position, which `Pretty[?]` has erased
          pretty.asInstanceOf[Pretty[Any]].describe(field, shape) // scalafix:ok DisableSyntax; erased per-field type
        }
        shape.product(name, labels.zip(described.toSeq).map((label, value) => PrettyField(label, value)))

  /** The instance of a sum type: the case's own instance, chosen by the mirror's `ordinal`. */
  def sum[A](cases: List[Pretty[?]], ordinal: A => Int): Pretty[A] =
    new Pretty[A]:
      def describe[R](value: A, shape: PrettyShape[R]): R =
        // the case instance at `ordinal(value)` was built for the case `value` is an instance of
        cases(ordinal(value))
          .asInstanceOf[Pretty[A]]
          .describe(value, shape) // scalafix:ok DisableSyntax; case type erased by the list

  /** `text` between `quote`s with the quote, the backslash and the control characters escaped. */
  private def quoted(text: String, quote: Char): String =
    val out = StringBuilder().append(quote)
    text.foreach {
      case '\n'             => out.append("\\n")
      case '\t'             => out.append("\\t")
      case '\r'             => out.append("\\r")
      case '\\'             => out.append("\\\\")
      case c if c == quote  => out.append('\\').append(c)
      case c if c.isControl => out.append(String.format(Locale.ROOT, "\\u%04x", c.toInt))
      case c                => out.append(c)
    }
    out.append(quote).result()

/** Derives a [[Pretty]] for a case class, an enum or a sealed hierarchy at compile time through its `Mirror` — the same
  * pattern as [[deriveCodec]]: each field and each case summons its own instance, a hand-written given where there is
  * one and [[Pretty.derived]] otherwise, so nested types need no declaration and a missing instance is a compile error
  * naming the type. The recursion spends the call site's `-Xmax-inlines` budget; derive inner types into their own
  * `given`s to reset it.
  */
inline def derivePretty[A](using mirror: Mirror.Of[A]): Pretty[A] =
  inline mirror match
    case sum: Mirror.SumOf[A]         => Pretty.sum[A](PrettyDerivation.instances[sum.MirroredElemTypes], sum.ordinal)
    case product: Mirror.ProductOf[A] =>
      Pretty.product[A](
        constValue[product.MirroredLabel],
        constValueTuple[product.MirroredElemLabels].toList.map(_.toString),
        PrettyDerivation.instances[product.MirroredElemTypes],
      )

private[macros] object PrettyDerivation:

  /** One instance per element type of the tuple, in order, each summoned at the call site so a given there wins. */
  inline def instances[T <: Tuple]: List[Pretty[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[Pretty[t]] :: instances[ts]

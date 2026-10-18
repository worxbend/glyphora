package io.worxbend.tui.macros

import scala.annotation.implicitNotFound
import scala.compiletime.{constValueTuple, erasedValue, summonInline}
import scala.deriving.Mirror

/** How a value becomes a compact line of text and comes back again — the typeclass behind persisted signals.
  *
  * The text form is a stream of length-prefixed *atoms*, `<length>:<text>`, with no quoting or escaping anywhere: a
  * `String` is one atom, a number is one atom holding its decimal spelling, a case class is its fields' encodings one
  * after another (the field count is known from the type, so nothing frames it), an `Option` is a one-character atom
  * followed by the value when present, a collection is its size followed by its elements, and an enum or sealed trait
  * is the case name followed by that case's fields. Any string can therefore be carried verbatim, decoding is one left
  * to right pass with no backtracking, and a file truncated or edited by hand fails with a message rather than an
  * exception or a half-read value. It is a storage format, not a wire or interchange format: there is no version tag,
  * and a type whose fields change decodes its old file as a `Left`, which [[Persisted]] in `tui-runtime` treats as
  * "start from the initial value".
  *
  * Instances for the primitives, `Option` and the immutable collections are in the companion, and so is
  * [[Codec.derived]], which hands any case class, enum or sealed hierarchy the codec [[deriveCodec]] builds — so a
  * `Codec[Settings]` is summoned without a declaration, nested types included. A type of your own that is none of
  * those, or that wants a different text form, joins by declaring a `given Codec[YourType]` next to it, usually through
  * [[Codec.atom]]; a given in lexical scope wins over the derivation.
  *
  * Pure and thread-agnostic: `write` and `read` touch only their arguments, so an instance may be shared and called
  * from any thread — a persisted signal encodes on the render thread and writes the text on a worker.
  */
@implicitNotFound(
  "no Codec is defined for ${A}. String, Int, Long, Double, Boolean, Option, Seq, List, Vector and Set of those, and " +
    "any case class, enum or sealed hierarchy over them, are supported out of the box; declare a `given Codec[${A}]` " +
    "through Codec.atom next to your own type to teach the derivation about it."
)
trait Codec[A]:

  /** Appends the encoding of `value` to `out`. */
  def write(value: A, out: StringBuilder): Unit

  /** Reads one value from the reader's current position, advancing it past what was consumed. */
  def read(in: Codec.Reader): Either[String, A]

  /** The whole text form of `value`. */
  final def encode(value: A): String =
    val out = StringBuilder()
    write(value, out)
    out.result()

  /** Decodes text produced by [[encode]]. Trailing text after the value is an error: the file holds one value. */
  final def decode(text: String): Either[String, A] =
    val in = Codec.Reader(text)
    read(in).flatMap { value =>
      if in.atEnd then Right(value) else Left(s"unexpected text after the value at offset ${in.position}")
    }

object Codec:

  /** A cursor over the text being decoded. One reader belongs to one `decode` call and is used on that thread only. */
  final class Reader(text: String):
    private var offset: Int = 0

    def position: Int  = offset
    def atEnd: Boolean = offset >= text.length

    /** The next `<length>:<text>` atom, or why there is none. */
    def atom(): Either[String, String] =
      val colon = text.indexOf(':', offset)
      if colon < 0 then Left(s"expected a length-prefixed value at offset $offset, found end of input")
      else
        text.slice(offset, colon).toIntOption match
          case Some(length) if length >= 0 && colon + 1 + length <= text.length =>
            val payload = text.slice(colon + 1, colon + 1 + length)
            offset = colon + 1 + length
            Right(payload)
          case Some(_)                                                          =>
            Left(s"value at offset $offset claims more text than remains (truncated input?)")
          case None                                                             =>
            Left(s"expected a length at offset $offset, found '${text.slice(offset, colon)}'")

  private def writeAtom(payload: String, out: StringBuilder): Unit =
    out.append(payload.length).append(':').append(payload)

  /** A codec for a type whose whole encoding is one atom — the way an application teaches the derivation about one of
    * its own types: `given Codec[Email] = Codec.atom[Email](_.value, Email.parse)`.
    */
  def atom[A](show: A => String, parse: String => Either[String, A]): Codec[A] =
    new Codec[A]:
      def write(value: A, out: StringBuilder): Unit = writeAtom(show(value), out)
      def read(in: Codec.Reader): Either[String, A] = in.atom().flatMap(parse)

  private def parsed[A](name: String)(parse: String => Option[A]): Codec[A] =
    atom[A](_.toString, raw => parse(raw).toRight(s"'$raw' is not $name"))

  given string: Codec[String]   = atom[String](identity, Right(_))
  given int: Codec[Int]         = parsed("a whole number")(_.toIntOption)
  given long: Codec[Long]       = parsed("a whole number")(_.toLongOption)
  given double: Codec[Double]   = parsed("a number")(_.toDoubleOption)
  given boolean: Codec[Boolean] = parsed("true/false")(_.toBooleanOption)

  given option[A](using inner: Codec[A]): Codec[Option[A]] =
    new Codec[Option[A]]:
      def write(value: Option[A], out: StringBuilder): Unit =
        value match
          case Some(a) =>
            writeAtom("s", out)
            inner.write(a, out)
          case None    => writeAtom("n", out)
      def read(in: Codec.Reader): Either[String, Option[A]] =
        in.atom().flatMap {
          case "s"   => inner.read(in).map(Some(_))
          case "n"   => Right(None)
          case other => Left(s"expected an Option marker, found '$other'")
        }

  private def collection[A, C <: Iterable[A]](inner: Codec[A], build: Vector[A] => C): Codec[C] =
    new Codec[C]:
      def write(value: C, out: StringBuilder): Unit =
        writeAtom(value.size.toString, out)
        value.foreach(inner.write(_, out))
      def read(in: Codec.Reader): Either[String, C] =
        in.atom().flatMap(_.toIntOption.filter(_ >= 0).toRight("expected a collection size")).flatMap { size =>
          val items = Vector.newBuilder[A]
          var error = Option.empty[String]
          var index = 0
          while error.isEmpty && index < size do
            inner.read(in) match
              case Right(item) => items += item
              case Left(why)   => error = Some(why)
            index += 1
          error.toLeft(build(items.result()))
        }

  given seq[A](using inner: Codec[A]): Codec[Seq[A]]       = collection(inner, identity)
  given list[A](using inner: Codec[A]): Codec[List[A]]     = collection(inner, _.toList)
  given vector[A](using inner: Codec[A]): Codec[Vector[A]] = collection(inner, identity)
  given set[A](using inner: Codec[A]): Codec[Set[A]]       = collection(inner, _.toSet)

  /** [[deriveCodec]] as a given, so a case class, enum or sealed hierarchy needs no declaration to be persisted and the
    * derivation reaches nested types through the ordinary `Codec[Seq[Inner]]` search. The hand-written instances above
    * are more specific than this one, so `Option`, `List` and the other sealed standard types keep theirs; a `given` an
    * application declares in lexical scope is found before the implicit scope and wins too.
    */
  inline given derived[A](using mirror: Mirror.Of[A]): Codec[A] = deriveCodec[A]

  /** The codec of a product type whose fields are encoded one after another by `fields`, in declaration order. Shared
    * by every [[deriveCodec]] call site rather than inlined into each, so the program holds one copy of the loop.
    */
  def product[A](fields: List[Codec[?]], fromProduct: Product => A): Codec[A] =
    new Codec[A]:
      def write(value: A, out: StringBuilder): Unit =
        // a `Mirror.ProductOf[A]` exists only for case classes and singleton cases, every one of which is a `Product`;
        // the mirror does not state that bound to the compiler, so it is restated here
        val product = value.asInstanceOf[Product] // scalafix:ok DisableSyntax; mirrored products are Products
        product.productIterator.zip(fields).foreach { (field, codec) =>
          // each codec was summoned for exactly the field type at this position, which `Codec[?]` has erased
          codec.asInstanceOf[Codec[Any]].write(field, out) // scalafix:ok DisableSyntax; erased per-field type
        }
      def read(in: Codec.Reader): Either[String, A] =
        val values = Array.ofDim[Any](fields.size)
        var error  = Option.empty[String]
        var index  = 0
        fields.foreach { codec =>
          if error.isEmpty then
            codec.read(in) match
              case Right(value) => values(index) = value
              case Left(why)    => error = Some(why)
          index += 1
        }
        error.toLeft(fromProduct(Tuple.fromArray(values)))

  /** The codec of a sum type: the case's name as one atom, then the case's own encoding. `ordinal` is the mirror's. */
  def sum[A](labels: List[String], cases: List[Codec[?]], ordinal: A => Int): Codec[A] =
    new Codec[A]:
      def write(value: A, out: StringBuilder): Unit =
        val index = ordinal(value)
        writeAtom(labels(index), out)
        // the case codec at `index` was built for the case `value` is an instance of
        cases(index).asInstanceOf[Codec[A]].write(value, out) // scalafix:ok DisableSyntax; case type erased by the list
      def read(in: Codec.Reader): Either[String, A] =
        in.atom().flatMap { label =>
          labels.indexOf(label) match
            case -1    => Left(s"'$label' is not a case of this type (expected one of ${labels.mkString(", ")})")
            case index =>
              // every case is a subtype of A, so a value its codec read is an A
              cases(index).asInstanceOf[Codec[A]].read(in) // scalafix:ok DisableSyntax; case type erased by the list
        }

/** Derives a [[Codec]] for a case class, an enum or a sealed hierarchy at compile time through its `Mirror`.
  *
  * Each field, and each case of a sum, summons its own `Codec` — a hand-written given where there is one, and otherwise
  * [[Codec.derived]], which is this derivation again — so a nested case class or an enum field needs no declaration of
  * its own. A field type with neither is a compile error naming the type, never a runtime failure, and nothing here
  * reads a class at runtime: a native image needs no reflection configuration for the derived types.
  *
  * The recursion is one inline step per field and per case, which is spent from the *call site's* `-Xmax-inlines`
  * budget (32 by default). A very wide case class, or a deep nest of them, can run out; the fix is to derive the inner
  * types into their own `given`s, which resets the budget at each one.
  */
inline def deriveCodec[A](using mirror: Mirror.Of[A]): Codec[A] =
  inline mirror match
    case sum: Mirror.SumOf[A]         =>
      Codec.sum[A](
        constValueTuple[sum.MirroredElemLabels].toList.map(_.toString),
        CodecDerivation.codecs[sum.MirroredElemTypes],
        sum.ordinal,
      )
    case product: Mirror.ProductOf[A] =>
      Codec.product[A](CodecDerivation.codecs[product.MirroredElemTypes], product.fromProduct)

private[macros] object CodecDerivation:

  /** One codec per element type of the tuple, in order, each summoned at the call site so a given declared there wins.
    */
  inline def codecs[T <: Tuple]: List[Codec[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[Codec[t]] :: codecs[ts]

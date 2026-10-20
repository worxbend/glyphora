package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

/** The shapes a persisted-signal value takes in practice: an enum, a nested case class, optional and repeated fields.
  * Top level, because that is where an application declares them and where the compiler synthesises their mirrors.
  */
enum Pane:
  case Left, Right, Both

final case class Window(x: Int, y: Int, title: Option[String])

final case class Layout(pane: Pane, windows: Seq[Window], recent: List[String], ratio: Double, pinned: Boolean)

/** A sum whose cases carry fields, so the derivation has to recurse into a case rather than name a singleton. */
enum Filter:
  case None
  case ByName(needle: String)
  case ByRange(low: Long, high: Long)

/** `Codec` round-trips values through a compact text form with no reflection: everything is derived from `Mirror`s at
  * compile time. The assertions are on round-tripping plus the handful of failure shapes a file on disk can present.
  */
final class CodecSpec extends AnyFunSuite:

  private val layout = deriveCodec[Layout]

  test("RTP-7 oversized atoms cannot rewind the reader or amplify collection input"):
    val reader = Codec.Reader("2147483647:x")
    assert(reader.atom().isLeft)
    assert(reader.position == 0)
    assert(summon[Codec[Vector[String]]].decode("10:20000000002147483647:x").isLeft)
    final case class Empty()
    val empty  = summon[Codec[Vector[Empty]]]
    assert(empty.decode("10:2000000000").isLeft)
    assert(empty.decode(empty.encode(Vector.fill(8)(Empty()))) == Right(Vector.fill(8)(Empty())))

  test("a nested case class with Option, Seq, List, enum, Double and Boolean fields round-trips"):
    val value =
      Layout(
        Pane.Both,
        Seq(Window(1, 2, Some("a:b,c")), Window(-3, 0, None)),
        List("x", "", "y z"),
        0.75,
        pinned = true,
      )
    assert(layout.decode(layout.encode(value)) == Right(value))

  test("empty collections and None survive the trip"):
    val value = Layout(Pane.Left, Seq.empty, Nil, 0.0, pinned = false)
    assert(layout.decode(layout.encode(value)) == Right(value))

  test("an enum with parameterised cases round-trips every case"):
    val codec = deriveCodec[Filter]
    Seq(Filter.None, Filter.ByName("hé 設定"), Filter.ByRange(Long.MinValue, Long.MaxValue)).foreach { value =>
      assert(codec.decode(codec.encode(value)) == Right(value))
    }

  test("strings holding the framing characters are not misread"):
    val codec = summon[Codec[String]]
    Seq("", ":", "3:abc", "a;b", "\n", "日本語").foreach(s => assert(codec.decode(codec.encode(s)) == Right(s)))

  test("decoding rejects truncated input and an unknown enum case with a message, never a throw"):
    val encoded = layout.encode(Layout(Pane.Right, Seq.empty, Nil, 1.0, pinned = false))
    assert(layout.decode(encoded.dropRight(3)).isLeft)
    assert(layout.decode("").isLeft)
    val pane    = deriveCodec[Pane]
    assert(pane.decode(pane.encode(Pane.Right)) == Right(Pane.Right))
    assert(pane.decode(summon[Codec[String]].encode("Middle")).left.exists(_.contains("Middle")))

  test("a primitive that fails to parse reports the offending text"):
    assert(summon[Codec[Int]].decode(summon[Codec[String]].encode("twelve")).left.exists(_.contains("twelve")))

  test("a hand-written codec for a field type takes precedence over derivation"):
    final case class Holder(id: Id)
    given Codec[Id] = Codec.atom[Id](_.raw, raw => Right(Id(raw.toUpperCase)))
    val codec       = deriveCodec[Holder]
    assert(codec.decode(codec.encode(Holder(Id("abc")))) == Right(Holder(Id("ABC"))))

final case class Id(raw: String)

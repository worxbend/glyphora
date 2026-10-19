package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

/** The shape a persisted settings type had before it grew a field. */
final case class PrefsV1(theme: String, width: Int)

/** The shape it has now. */
final case class Prefs(theme: String, width: Int, sidebar: Boolean)

/** The version tag on `Codec`'s text form, and the migration hook that reads an older version into today's type. */
final class CodecVersionSpec extends AnyFunSuite:

  private val plain = deriveCodec[PrefsV1]

  test("the text form starts with a version tag, 1 for a codec that declares none"):
    assert(plain.version == 1)
    assert(plain.encode(PrefsV1("dark", 80)) == "@1;4:dark2:80")
    assert(plain.decode("@1;4:dark2:80") == Right(PrefsV1("dark", 80)))

  test("an untagged file — the format before the tag existed — still decodes, as version 1"):
    // the literal bytes a 0.20.0 pre-release wrote for PrefsV1("dark", 80)
    assert(plain.decode("4:dark2:80") == Right(PrefsV1("dark", 80)))
    assert(deriveCodec[Pane].decode("5:Right") == Right(Pane.Right))

  private val current: Codec[Prefs] =
    deriveCodec[Prefs].versioned(2) { case 1 => Codec.readAs[PrefsV1](old => Prefs(old.theme, old.width, true)) }

  test("a versioned codec tags its text with its own version and round-trips"):
    assert(current.version == 2)
    assert(current.encode(Prefs("light", 100, sidebar = false)) == "@2;5:light3:1005:false")
    assert(current.decode(current.encode(Prefs("light", 100, sidebar = false))) == Right(Prefs("light", 100, false)))

  test("the migration hook reads an older version — tagged or untagged — into today's type"):
    assert(current.decode("@1;4:dark2:80") == Right(Prefs("dark", 80, sidebar = true)))
    assert(current.decode("4:dark2:80") == Right(Prefs("dark", 80, sidebar = true)))

  test("a version with no migration, a newer version and a malformed tag are failures with a message"):
    val strict = deriveCodec[Prefs].versioned(3) { case 2 => Codec.readAs[Prefs](identity) }
    assert(strict.decode("@1;4:dark2:80").left.exists(_.contains("version 1")))
    assert(current.decode("@9;4:dark2:801:x").left.exists(_.contains("version 9")))
    assert(current.decode("@x;4:dark").isLeft)
    assert(current.decode("@2").isLeft)

  test("trailing text after a migrated value is still an error"):
    assert(current.decode("@1;4:dark2:801:x").isLeft)

  test("a nested field carries no tag of its own: only the top level is tagged"):
    final case class Outer(inner: PrefsV1)
    assert(deriveCodec[Outer].encode(Outer(PrefsV1("d", 1))) == "@1;1:d1:1")

  test("the documented spelling, a given built from Codec.derived, migrates too"):
    given Codec[Prefs] =
      Codec.derived[Prefs].versioned(2) { case 1 => Codec.readAs[PrefsV1](old => Prefs(old.theme, old.width, false)) }
    assert(summon[Codec[Prefs]].decode("4:dark2:80") == Right(Prefs("dark", 80, sidebar = false)))

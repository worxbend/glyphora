package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, GlyphSupport, Style}

import org.scalatest.funsuite.AnyFunSuite

/** `Theme.parse` / `Theme.render`: the `key = value` theme file format. */
final class ThemeFileSpec extends AnyFunSuite:

  test("Nord written out and read back is Nord"):
    assert(Theme.parse(Theme.render(Theme.Nord)) == Right(Theme.Nord))

  test("every preset round-trips, components and glyph ceiling included"):
    (Theme.presets :+ Theme.Dark.withGlyphs(GlyphSupport.Ascii)).foreach { theme =>
      assert(Theme.parse(Theme.render(theme)) == Right(theme), theme.name)
    }

  test("a theme with no components round-trips to one with none"):
    val bare = Theme.Dark.withoutComponents.withName("bare")
    assert(Theme.parse(Theme.render(bare)) == Right(bare))

  test("a bad colour is reported with the line it is on"):
    val text = "# a theme\nname = broken\n\naccent = bold #zzzzzz\n"
    Theme.parse(text) match
      case Left(error)  =>
        assert(error.line == 4)
        assert(error.message.contains("accent"))
        assert(error.message.contains("#zzzzzz"))
      case Right(theme) => fail(s"parsed $theme")

  test("unknown keys, missing '=' and unknown presets are errors on their own line"):
    assert(Theme.parse("name = x\nshiny = bold").left.map(_.line) == Left(2))
    assert(Theme.parse("name x").left.map(_.line) == Left(1))
    assert(Theme.parse("name = x\nextends = neon").left.map(_.line) == Left(2))
    assert(Theme.parse("component.button:wobbly = bold").left.map(_.line) == Left(1))

  test("a partial file extends a preset and overrides only what it names"):
    val text   =
      """|name    = midnight
         |extends = nord
         |accent  = bold #ffffff
         |component.button.primary:focused = italic
         |""".stripMargin
    val parsed = Theme.parse(text).fold(error => fail(error.toString), identity)
    assert(parsed.name == "midnight")
    assert(parsed.accent == Style.Default.bold.withFg(Color.Rgb(255, 255, 255)))
    assert(parsed.border == Theme.Nord.border)
    assert(parsed.component(ButtonRole.Primary).focused == Style.Default.italic)
    assert(parsed.component(ButtonRole.Primary).base == Theme.Nord.component(ButtonRole.Primary).base)

  test("a component and its states are written one per line"):
    val theme = Theme.Dark.withoutComponents.withComponent(
      ListRole.Selected,
      StateStyles(base = Style.Default.withBg(Color.Blue), hovered = Style.Default.underline),
    )
    val text  = Theme.render(theme)
    assert(text.linesIterator.contains("component.list.selected = on Blue"))
    assert(text.linesIterator.contains("component.list.selected:hovered = underline"))

  test("Ansi16 names nothing outside the terminal's own sixteen colours"):
    // a palette index is written as bare digits and a true colour as `#rrggbb`; a sixteen-colour name is neither
    val values = Theme.render(Theme.Ansi16).linesIterator.filterNot(_.startsWith("#")).map(_.dropWhile(_ != '='))
    val words  = values.flatMap(_.drop(1).trim.split(' ')).toSeq
    assert(words.forall(word => !word.startsWith("#") && !word.forall(_.isDigit)), words.mkString(" "))

package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** `Style.parse` / `Style.render` — the one-line style spelling theme files are written in. */
final class StyleSyntaxSpec extends AnyFunSuite:

  test("modifiers, a foreground and an `on` background read in any order the words allow"):
    assert(
      Style.parse("bold #ff0000 on black") == Right(Style.Default.bold.withFg(Color.Rgb(255, 0, 0)).withBg(Color.Black))
    )
    assert(Style.parse("italic dim cyan") == Right(Style.Default.italic.dim.withFg(Color.Cyan)))
    assert(Style.parse("on 236") == Right(Style.Default.withBg(Color.Indexed(236))))

  test("two-word colour names, cleared modifiers, underline variants, underline colour and a link"):
    assert(Style.parse("bright red") == Right(Style.Default.withFg(Color.BrightRed)))
    assert(Style.parse("not-bold reset on reset") == Right(Style.Default.notBold.withoutFg.withoutBg))
    assert(
      Style.parse("curly-underline underline-color red link https://example.com") ==
        Right(Style.Default.curlyUnderline.withUnderlineColor(Color.Red).withLink("https://example.com"))
    )

  test("an empty or `default` style is Style.Default"):
    assert(Style.parse("default") == Right(Style.Default))
    assert(Style.parse("   ") == Right(Style.Default))

  test("a bad word, a second foreground or a dangling `on` is a Left naming the problem"):
    assert(Style.parse("bold chartreuse").left.exists(_.contains("chartreuse")))
    assert(Style.parse("red blue").left.exists(_.contains("blue")))
    assert(Style.parse("red on").left.exists(_.contains("on")))

  test("render writes what parse reads back, for every kind of field"):
    val styles = Seq(
      Style.Default,
      Style.Default.bold.italic.withFg(Color.Indexed(42)).withBg(Color.Rgb(1, 2, 3)),
      Style.Default.notBold.notReverse.withoutFg.withoutBg,
      Style.Default.reverse.rapidBlink.crossedOut.hidden.blink.underline.dim,
      Style.Default.doubleUnderline.withUnderlineColor(Color.BrightMagenta).withLink("https://x.dev/a?b=c"),
      Style.Default.withFg(Color.BrightBlack).withBg(Color.White),
    )
    styles.foreach(style => assert(Style.parse(Style.render(style)) == Right(style), Style.render(style)))

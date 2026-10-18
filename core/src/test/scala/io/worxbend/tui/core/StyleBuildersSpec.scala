package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** Covers the shape [[Style]] took when it stopped being a `case class`: a `final class` with a private constructor, a
  * companion `apply` that keeps every named-argument construction compiling, one `with*` builder per field, and
  * hand-written structural equality over every field — `Cell` is a case class holding a `Style`, so `Buffer` equality,
  * frame diffing and style memoisation all derive from it.
  */
final class StyleBuildersSpec extends AnyFunSuite:

  /** Every field set to something other than its default, with disjoint modifier sets. */
  private val populated = Style(
    fg = Some(Color.Red),
    bg = Some(Color.Blue),
    modifiers = Modifiers.Bold,
    link = Some("https://example.test"),
    underlineColor = Some(Color.Green),
    underlineStyle = UnderlineStyle.Curly,
    clearedModifiers = Modifiers.Italic,
  )

  test("every with* builder round-trips its field from the default style"):
    assert(Style.Default.withFg(Color.Red) == Style(fg = Some(Color.Red)))
    assert(Style.Default.withBg(Color.Blue) == Style(bg = Some(Color.Blue)))
    assert(
      Style.Default.withModifiers(Modifiers.Bold | Modifiers.Dim) == Style(modifiers = Modifiers.Bold | Modifiers.Dim)
    )
    assert(Style.Default.withLink("u") == Style(link = Some("u")))
    assert(Style.Default.withUnderlineColor(Color.Green) == Style(underlineColor = Some(Color.Green)))
    assert(Style.Default.withUnderlineStyle(UnderlineStyle.Dotted) == Style(underlineStyle = UnderlineStyle.Dotted))
    assert(Style.Default.withClearedModifiers(Modifiers.Bold) == Style(clearedModifiers = Modifiers.Bold))

  test("each builder leaves every other field as it was"):
    assert(populated.withFg(Color.Cyan) == populated.withFg(Color.Cyan)) // sanity: equality is value-based
    assert(populated.withFg(Color.Cyan).bg == populated.bg)
    assert(populated.withFg(Color.Cyan).link == populated.link)
    assert(populated.withBg(Color.Cyan).fg == populated.fg)
    assert(populated.withLink("other").underlineStyle == populated.underlineStyle)
    assert(populated.withUnderlineColor(Color.Cyan).modifiers == populated.modifiers)
    assert(populated.withUnderlineStyle(UnderlineStyle.Dashed).clearedModifiers == populated.clearedModifiers)
    // the two bitsets are kept disjoint: `Dim` neither sets nor clears anything the other set holds
    assert(populated.withModifiers(Modifiers.Dim).clearedModifiers == populated.clearedModifiers)
    assert(populated.withClearedModifiers(Modifiers.Dim).modifiers == populated.modifiers)

  test("withModifiers and withClearedModifiers keep the two bitsets disjoint, last call winning"):
    // the class invariant every other builder honours: a flag is set or cleared, never both
    val set     = populated.withModifiers(Modifiers.Italic)
    assert(set.modifiers == Modifiers.Italic)
    assert(set.clearedModifiers.isEmpty)
    val cleared = populated.withClearedModifiers(Modifiers.Bold)
    assert(cleared.clearedModifiers == Modifiers.Bold)
    assert(cleared.modifiers.isEmpty)

  test("the Option-valued fields have a without* pair"):
    assert(populated.withoutLink.link.isEmpty)
    assert(populated.withoutLink.withLink("https://example.test") == populated)
    assert(populated.withoutFg.fg.contains(Color.Reset)) // documented: "no colour, and I mean it", not silence
    assert(populated.withoutBg.bg.contains(Color.Reset))

  test("equals and hashCode agree on all fields"):
    val twin           = Style.Default
      .withFg(Color.Red)
      .withBg(Color.Blue)
      .bold
      .withLink("https://example.test")
      .withUnderlineColor(Color.Green)
      .curlyUnderline
      .notItalic
    assert(twin == populated)
    assert(twin.hashCode == populated.hashCode)
    val variants       = Seq(
      populated.withFg(Color.Cyan),
      populated.withBg(Color.Cyan),
      populated.withModifiers(Modifiers.Bold | Modifiers.Dim),
      populated.withLink("elsewhere"),
      populated.withUnderlineColor(Color.Cyan),
      populated.withUnderlineStyle(UnderlineStyle.Dashed),
      populated.withClearedModifiers(Modifiers.Italic | Modifiers.Dim),
    )
    for variant <- variants do
      assert(variant != populated, variant.toString)
      // not a contract of hashCode in general, but these are seven one-field changes to a seven-field hash: a collision
      // among them would mean a field the hash forgot, which is exactly the regression this guards against
      assert(variant.hashCode != populated.hashCode, variant.toString)
    val notAStyle: Any = "not a style"
    assert(populated != notAStyle)
    assert(Set(populated, twin, Style.Default).size == 2)

  test("the companion apply still accepts the documented named-argument forms"):
    assert(Style() == Style.Default)
    assert(Style(fg = Some(Color.Red)) == Style.fg(Color.Red))
    assert(Style(bg = Some(Color.Red)) == Style.bg(Color.Red))
    assert(Style(fg = Some(Color.Red), bg = Some(Color.Blue)) == Style.of(Color.Red, Color.Blue))
    assert(Style(modifiers = Modifiers.All) == Style.mods(Modifiers.All))
    assert(
      Style(
        Some(Color.Red),
        Some(Color.Blue),
        Modifiers.Bold,
        Some("https://example.test"),
        Some(Color.Green),
        UnderlineStyle.Curly,
        Modifiers.Italic,
      )
        == populated
    )

  test("toString is stable"):
    assert(Style.Default.toString == "Style.Default")
    assert(Style(fg = Some(Color.Cyan), modifiers = Modifiers.Bold).toString == "Style(fg=Cyan, modifiers=Bold)")

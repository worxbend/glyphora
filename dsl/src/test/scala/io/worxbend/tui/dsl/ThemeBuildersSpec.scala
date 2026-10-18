package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, GlyphSupport, Style}
import io.worxbend.tui.widgets.{MarkdownTheme, SyntaxTheme}

import org.scalatest.funsuite.AnyFunSuite

/** Covers the shape [[Theme]] took when it stopped being a `case class`: a `final class` with a private constructor, a
  * companion `apply` that keeps every named-argument construction compiling, one `with*` builder per field, structural
  * equality over every field and a `toString` that is the theme's name. The builders are the customisation path the
  * docs used to spell as `Theme.Dark.copy(...)` — the one member a new semantic role would have broken.
  */
final class ThemeBuildersSpec extends AnyFunSuite:

  private val magenta: Style          = Style.Default.withFg(Color.Magenta)
  private val loading: LoadingTheme   = LoadingTheme.from(magenta, Style.Default.dim, Style.Default)
  private val markdown: MarkdownTheme = MarkdownTheme(heading1 = magenta)
  // `default`, not `keyword`: the widget-level `SyntaxTheme()` already paints keywords magenta, and `Dark` uses it
  // unchanged, so a keyword-only variant would equal `Theme.Dark` by construction
  private val syntax: SyntaxTheme     = SyntaxTheme(default = magenta)

  /** `base` spelled out through the companion `apply`, with the named overrides — the documented construction form. */
  private def rebuild(base: Theme)(
      name: String = base.name,
      primary: Style = base.primary,
      accent: Style = base.accent,
      muted: Style = base.muted,
      error: Style = base.error,
      warning: Style = base.warning,
      success: Style = base.success,
      surface: Style = base.surface,
      border: Style = base.border,
      focus: Style = base.focus,
      loading: LoadingTheme = base.loading,
      markdown: MarkdownTheme = base.markdown,
      syntax: SyntaxTheme = base.syntax,
      glyphs: GlyphSupport = base.glyphs,
  ): Theme =
    Theme(
      name = name,
      primary = primary,
      accent = accent,
      muted = muted,
      error = error,
      warning = warning,
      success = success,
      surface = surface,
      border = border,
      focus = focus,
      loading = loading,
      markdown = markdown,
      syntax = syntax,
      glyphs = glyphs,
    )

  test("every with* builder round-trips its field"):
    val dark = Theme.Dark
    assert(dark.withName("mine") == rebuild(dark)(name = "mine"))
    assert(dark.withPrimary(magenta) == rebuild(dark)(primary = magenta))
    assert(dark.withAccent(magenta) == rebuild(dark)(accent = magenta))
    assert(dark.withMuted(magenta) == rebuild(dark)(muted = magenta))
    assert(dark.withError(magenta) == rebuild(dark)(error = magenta))
    assert(dark.withWarning(magenta) == rebuild(dark)(warning = magenta))
    assert(dark.withSuccess(magenta) == rebuild(dark)(success = magenta))
    assert(dark.withSurface(magenta) == rebuild(dark)(surface = magenta))
    assert(dark.withBorder(magenta) == rebuild(dark)(border = magenta))
    assert(dark.withFocus(magenta) == rebuild(dark)(focus = magenta))
    assert(dark.withLoading(loading) == rebuild(dark)(loading = loading))
    assert(dark.withMarkdown(markdown) == rebuild(dark)(markdown = markdown))
    assert(dark.withSyntax(syntax) == rebuild(dark)(syntax = syntax))
    assert(dark.withGlyphs(GlyphSupport.Ascii) == rebuild(dark)(glyphs = GlyphSupport.Ascii))

  test("each builder leaves every other field as it was"):
    val themed = Theme.Light.withBorder(magenta)
    assert(themed.border == magenta)
    assert(themed.name == Theme.Light.name)
    assert(themed.primary == Theme.Light.primary)
    assert(themed.loading == Theme.Light.loading)
    assert(themed.markdown == Theme.Light.markdown)
    assert(themed.glyphs == Theme.Light.glyphs)
    assert(Theme.Light.withGlyphs(GlyphSupport.Ascii).withGlyphs(GlyphSupport.Full) == Theme.Light)

  test("equals and hashCode agree on all fields"):
    val light = Theme.Light
    val twin  = Theme.Dark
      .withName(light.name)
      .withPrimary(light.primary)
      .withAccent(light.accent)
      .withMuted(light.muted)
      .withError(light.error)
      .withWarning(light.warning)
      .withSuccess(light.success)
      .withSurface(light.surface)
      .withBorder(light.border)
      .withFocus(light.focus)
      .withLoading(light.loading)
      .withMarkdown(light.markdown)
      .withSyntax(light.syntax)
      .withGlyphs(light.glyphs)
    assert(twin == light)
    assert(twin.hashCode == light.hashCode)
    assert(twin ne light, "a chain of builders builds a new value; equality here must be structural, not reference")

    val dark           = Theme.Dark
    val variants       = Seq(
      dark.withName("other"),
      dark.withPrimary(magenta),
      dark.withAccent(magenta),
      dark.withMuted(magenta),
      dark.withError(magenta),
      dark.withWarning(magenta),
      dark.withSuccess(magenta),
      dark.withSurface(magenta),
      dark.withBorder(magenta),
      dark.withFocus(magenta),
      dark.withLoading(loading),
      dark.withMarkdown(markdown),
      dark.withSyntax(syntax),
      dark.withGlyphs(GlyphSupport.Ascii),
    )
    for variant <- variants do
      assert(variant != dark, variant.toString)
      // not a contract of hashCode in general, but these are fourteen one-field changes to a fourteen-field hash: a
      // collision among them would mean a field the hash forgot, which is exactly the regression this guards against
      assert(variant.hashCode != dark.hashCode, variant.toString)
    val notATheme: Any = "not a theme"
    assert(dark != notATheme)
    assert(Set(light, twin, dark).size == 2)

  test("the companion apply still accepts the documented named-argument forms"):
    // the glyph ceiling is the one field with a default, so the thirteen-argument form the presets use still compiles
    val named      = Theme(
      name = "named",
      primary = magenta,
      accent = magenta,
      muted = magenta,
      error = magenta,
      warning = magenta,
      success = magenta,
      surface = magenta,
      border = magenta,
      focus = magenta,
      loading = loading,
      markdown = markdown,
      syntax = syntax,
    )
    assert(named.glyphs == GlyphSupport.Full)
    val positional = Theme(
      "named",
      magenta,
      magenta,
      magenta,
      magenta,
      magenta,
      magenta,
      magenta,
      magenta,
      magenta,
      loading,
      markdown,
      syntax,
      GlyphSupport.Full,
    )
    assert(positional == named)

  test("toString is the theme's name"):
    assert(Theme.Dark.toString == "Theme(dark)")
    assert(Theme.Light.toString == "Theme(light)")
    assert(Theme.HighContrast.toString == "Theme(high-contrast)")
    assert(Theme.Dark.withName("mine").toString == "Theme(mine)")

  test("the presets and the ambient given are unchanged"):
    assert(summon[Theme] eq Theme.Dark)
    assert(Seq(Theme.Dark, Theme.Light, Theme.HighContrast).map(_.name) == Seq("dark", "light", "high-contrast"))
    assert(Seq(Theme.Dark, Theme.Light, Theme.HighContrast).forall(_.glyphs == GlyphSupport.Full))

  test("Theme.detected is withGlyphs over the base theme"):
    val detected = Theme.detected(Theme.Light, Map("TERM" -> "xterm", "LANG" -> "C"))
    assert(detected == Theme.Light.withGlyphs(GlyphSupport.Ascii))
    assert(detected.withGlyphs(GlyphSupport.Full) == Theme.Light)

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{ColorScheme, GlyphSupport, Style}
import io.worxbend.tui.core.Color
import io.worxbend.tui.terminal.TerminalGlyphs
import io.worxbend.tui.widgets.{ColorRamp, MarkdownTheme, SyntaxTheme}

/** The styles the loading and progress animations draw from.
  *
  * Split out of [[Theme]] rather than flattened into it because these five travel together: retheming a progress bar
  * means changing `track` and `fill` consistently, and a theme that got one of them wrong would be hard to spot. It
  * also keeps `Theme`'s own vocabulary semantic (`accent`, `muted`) rather than widget-specific.
  *
  * `fillRamp` is optional because a color ramp is a deliberate choice, not a default: a bar that walks red-to-green
  * reads as "danger receding", which is right for disk usage and wrong for a download. Themes leave it unset and a call
  * site opts in with `.ramp(...)`.
  */
final case class LoadingTheme(
    spinner: Style,
    label: Style,
    track: Style,
    fill: Style,
    band: Style,
    fillRamp: Option[ColorRamp] = None,
)

object LoadingTheme:

  /** Derives a loading palette from a theme's own semantic styles, so a custom [[Theme]] gets a coherent one for free:
    * the moving parts take the accent color, the track and label recede into the muted style.
    */
  def from(accent: Style, muted: Style, surface: Style): LoadingTheme =
    LoadingTheme(spinner = accent, label = muted, track = muted, fill = accent, band = surface)

/** Semantic styles the chrome presets, the themed element factories, and applications draw from.
  *
  * A `TuiApp` hands its own [[TuiApp.theme]] to `view` as a `using` parameter, so everything a view calls sees it
  * without any `given` ceremony. Outside a view — a widget-level test, an element built in a helper object — the
  * ambient [[Theme.default]] applies at construction, and an element built against it is re-resolved against the app's
  * own theme when it is painted (see [[LateBinding]]), so a helper's panel follows `Theme.Light` like the rest.
  *
  * The three sub-palettes are grouped rather than flattened because they retheme as units: `loading` for the spinner
  * and progress family, `markdown` for a rendered document, and `markdown.syntax` (mirrored as [[syntax]] for the
  * standalone highlighter) for code.
  *
  * A `final class` with a private constructor rather than a `case class`, as `Style`, `Layout` and `RunnerConfig` are,
  * because this is the type a new semantic role lands in. A case class rewrites `apply`, `copy` and `unapply` on every
  * new field, and a consumer compiled against the old shape fails with `NoSuchMethodError`; here the compatibility
  * surface is the three presets plus the `with*` builders — `Theme.Dark.withBorder(...)` where the docs used to say
  * `Theme.Dark.copy(border = ...)` — all of which a new field leaves untouched. There is no `copy` and no pattern
  * extractor; equality and `hashCode` are structural over every field, and `toString` is `Theme(<name>)`, which is what
  * a theme switcher and a failed assertion both want to see.
  *
  * @param border
  *   the frame style `panel` and `rule` draw with when the caller sets no style of their own.
  * @param focus
  *   the cue the focus pass paints on the focused element, and the selection highlight of the scrollable widgets.
  * @param glyphs
  *   the ceiling every themed factory keeps its glyphs under. The three built-in themes stay at
  *   [[io.worxbend.tui.core.GlyphSupport.Full]], which is what a modern terminal emulator can draw. An application that
  *   has to run somewhere less certain writes `theme = Theme.detected()` and every panel border, spinner and progress
  *   bar in the tree follows, with no other call site changing.
  */
final class Theme private (
    val name: String,
    val primary: Style,
    val accent: Style,
    val muted: Style,
    val error: Style,
    val warning: Style,
    val success: Style,
    val surface: Style,
    val border: Style,
    val focus: Style,
    val loading: LoadingTheme,
    val markdown: MarkdownTheme,
    val syntax: SyntaxTheme,
    val glyphs: GlyphSupport,
    val components: Map[StyleName, StateStyles],
):

  /** This theme with the named fields replaced — the one place the builders below spell out the constructor. */
  private def updated(
      name: String = name,
      primary: Style = primary,
      accent: Style = accent,
      muted: Style = muted,
      error: Style = error,
      warning: Style = warning,
      success: Style = success,
      surface: Style = surface,
      border: Style = border,
      focus: Style = focus,
      loading: LoadingTheme = loading,
      markdown: MarkdownTheme = markdown,
      syntax: SyntaxTheme = syntax,
      glyphs: GlyphSupport = glyphs,
      components: Map[StyleName, StateStyles] = components,
  ): Theme =
    new Theme(
      name,
      primary,
      accent,
      muted,
      error,
      warning,
      success,
      surface,
      border,
      focus,
      loading,
      markdown,
      syntax,
      glyphs,
      components,
    )

  /** The name [[toString]] prints and a theme switcher shows; the presets are `"dark"`, `"light"`, `"high-contrast"`.
    */
  def withName(name: String): Theme = updated(name = name)

  /** The style the focus pass stamps on a `.disabled` control: [[muted]] with `dim` added, so a disabled button reads
    * as greyed out in every preset without a role of its own. Derived rather than stored — a theme that changes `muted`
    * changes this too — because the three presets express "de-emphasised" through `muted` already and a second knob for
    * the same idea would drift from the first. A later theme shape may promote it to a role; the read stays `disabled`.
    */
  def disabled: Style = muted.dim

  def withPrimary(style: Style): Theme = updated(primary = style)
  def withAccent(style: Style): Theme  = updated(accent = style)
  def withMuted(style: Style): Theme   = updated(muted = style)
  def withError(style: Style): Theme   = updated(error = style)
  def withWarning(style: Style): Theme = updated(warning = style)
  def withSuccess(style: Style): Theme = updated(success = style)
  def withSurface(style: Style): Theme = updated(surface = style)

  /** The frame style `panel` and `rule` draw with when the caller sets none of their own. */
  def withBorder(style: Style): Theme = updated(border = style)

  /** The focus cue, and the selection highlight of every "pick a row" element. */
  def withFocus(style: Style): Theme = updated(focus = style)

  /** The loading palette as a unit — [[LoadingTheme.from]] derives a coherent one from `accent`, `muted` and `surface`
    * rather than making a custom theme spell out all five.
    */
  def withLoading(palette: LoadingTheme): Theme = updated(loading = palette)

  /** The document palette `markdown(…)` renders through. Its `syntax` is independent of [[withSyntax]]: the presets
    * bind both to one `SyntaxTheme` so fenced code and the standalone highlighter cannot drift apart, and a custom
    * theme that changes one should usually change the other.
    */
  def withMarkdown(theme: MarkdownTheme): Theme = updated(markdown = theme)

  /** Token colours for the standalone `SyntaxHighlighter` — see [[withMarkdown]] for the pairing. */
  def withSyntax(theme: SyntaxTheme): Theme = updated(syntax = theme)

  /** The glyph ceiling every themed factory keeps under — what [[Theme.detected]] lowers. Only the ceiling moves; every
    * colour is kept.
    */
  def withGlyphs(ceiling: GlyphSupport): Theme = updated(glyphs = ceiling)

  /** The styles every element of `role` draws in, per interaction state — see [[StateStyles]]. Replaces whatever this
    * theme held under exactly that name; a parent or child name is untouched, and still merges with this one when it is
    * looked up.
    */
  def withComponent(role: ComponentRole, styles: StateStyles): Theme = withComponent(role.styleName, styles)

  /** This theme with no component styles at all, so every element draws as it would with none — the start for a custom
    * component set that should not inherit a preset's.
    */
  def withoutComponents: Theme = updated(components = Map.empty)

  /** [[withComponent]] by name, for a role the application spells as text. */
  def withComponent(name: StyleName, styles: StateStyles): Theme =
    updated(components = components.updated(name, styles))

  /** The [[StateStyles]] for `role`, merged down its name's lineage: `"button.primary"` is `"button"` with
    * `"button.primary"` layered over it, field by field, so a child only says what makes it different. A name nothing
    * in the lineage mentions resolves to [[StateStyles.Empty]], which draws exactly as an unthemed element does.
    */
  def component(role: ComponentRole): StateStyles = component(role.styleName)

  /** [[component]] by name. */
  def component(name: StyleName): StateStyles =
    if components.isEmpty then StateStyles.Empty
    else name.lineage.foldLeft(StateStyles.Empty)((merged, step) => components.get(step).fold(merged)(merged.patch))

  /** The resting style `name` resolves to — the `base` of [[component]], for a lookup that has no states. */
  def style(name: StyleName): Style = component(name).base

  /** Structural equality over every field, reference equality tried first; the two cheap fields are compared before the
    * twelve styles and palettes.
    */
  override def equals(other: Any): Boolean =
    other match
      case that: Theme =>
        (this eq that) ||
        (name == that.name && glyphs == that.glyphs && primary == that.primary && accent == that.accent &&
          muted == that.muted && error == that.error && warning == that.warning && success == that.success &&
          surface == that.surface && border == that.border && focus == that.focus && loading == that.loading &&
          markdown == that.markdown && syntax == that.syntax && components == that.components)
      case _           => false

  /** Hashes every field [[equals]] compares, so a `Set[Theme]` or a memo keyed by theme cannot conflate two that differ
    * in one role.
    */
  override def hashCode(): Int =
    var hash = name.hashCode
    hash = 31 * hash + primary.hashCode
    hash = 31 * hash + accent.hashCode
    hash = 31 * hash + muted.hashCode
    hash = 31 * hash + error.hashCode
    hash = 31 * hash + warning.hashCode
    hash = 31 * hash + success.hashCode
    hash = 31 * hash + surface.hashCode
    hash = 31 * hash + border.hashCode
    hash = 31 * hash + focus.hashCode
    hash = 31 * hash + loading.hashCode
    hash = 31 * hash + markdown.hashCode
    hash = 31 * hash + syntax.hashCode
    hash = 31 * hash + glyphs.hashCode
    31 * hash + components.hashCode

  /** `Theme(dark)` — the name alone. Fourteen styles and palettes printed in full were unreadable in exactly the place
    * a theme is printed most, a failed assertion, and the name is what distinguishes one theme from another to a
    * reader.
    */
  override def toString: String = s"Theme($name)"

object Theme:

  /** A theme from its fields — the form the three presets below are written in. Only the glyph ceiling has a default:
    * every colour is a decision the theme's author makes.
    *
    * This is a construction convenience, not the compatibility surface. Adding a field changes its descriptor and
    * renumbers its default-argument getters, so a consumer compiled against `Theme(name = …, …)` still fails with
    * `NoSuchMethodError` on the next field until it recompiles; at that growth the previous arity is kept as a plain
    * default-free overload so compiled callers keep working. Code that must survive a new field without recompiling
    * starts from [[Dark]], [[Light]] or [[HighContrast]] and builds with the `with*` methods.
    */
  def apply(
      name: String,
      primary: Style,
      accent: Style,
      muted: Style,
      error: Style,
      warning: Style,
      success: Style,
      surface: Style,
      border: Style,
      focus: Style,
      loading: LoadingTheme,
      markdown: MarkdownTheme,
      syntax: SyntaxTheme,
      glyphs: GlyphSupport = GlyphSupport.Full,
      components: Map[StyleName, StateStyles] = Map.empty,
  ): Theme =
    new Theme(
      name,
      primary,
      accent,
      muted,
      error,
      warning,
      success,
      surface,
      border,
      focus,
      loading,
      markdown,
      syntax,
      glyphs,
      components,
    )

  // The three shared code palettes are declared before the themes that name them: `object` members initialise top to
  // bottom, so a `val Light` that read a `LightSyntax` declared below it would capture `null`.

  /** Shared by `Dark`'s Markdown fences and its standalone highlighter, so the two cannot drift apart.
    *
    * The widget-level defaults are already tuned for a dark terminal, so `Dark` is the one theme that can take them
    * unchanged — but it still has to bind them through a single name. Written as two independent `SyntaxTheme()` calls,
    * retuning the dark highlighter would move the standalone element and silently leave fenced code inside a `markdown`
    * element on the widget default.
    */
  private val DarkSyntax: SyntaxTheme = SyntaxTheme()

  /** Shared by `Light`'s Markdown fences and its standalone highlighter, so the two cannot drift apart. */
  private val LightSyntax: SyntaxTheme = SyntaxTheme(
    keyword = Style.Default.withFg(Color.Indexed(90)),
    string = Style.Default.withFg(Color.Indexed(28)),
    number = Style.Default.withFg(Color.Indexed(130)),
    comment = Style.Default.italic.withFg(Color.Indexed(245)),
    function = Style.Default.withFg(Color.Indexed(25)),
    variable = Style.Default.withFg(Color.Indexed(30)),
    default = Style.Default.withFg(Color.Black),
    inserted = Style.Default.withFg(Color.Indexed(28)),
    deleted = Style.Default.withFg(Color.Indexed(124)),
  )

  /** Shared by `HighContrast`'s Markdown fences and its standalone highlighter. */
  private val HighContrastSyntax: SyntaxTheme = SyntaxTheme(
    keyword = Style.Default.withFg(Color.Yellow).bold,
    string = Style.Default.withFg(Color.White).bold,
    number = Style.Default.withFg(Color.Yellow).bold,
    comment = Style.Default.withFg(Color.White).italic,
    function = Style.Default.withFg(Color.White).bold,
    variable = Style.Default.withFg(Color.White),
    default = Style.Default.withFg(Color.White),
    inserted = Style.Default.withFg(Color.Green).bold,
    deleted = Style.Default.withFg(Color.Red).bold,
  )

  val Dark: Theme = Theme(
    name = "dark",
    primary = Style.Default,
    accent = Style.Default.withFg(Color.Cyan),
    muted = Style.Default.dim,
    error = Style.Default.withFg(Color.Red),
    warning = Style.Default.withFg(Color.Yellow),
    success = Style.Default.withFg(Color.Green),
    surface = Style.Default.withBg(Color.Indexed(236)).withFg(Color.White),
    border = Style.Default.withFg(Color.Indexed(244)),
    focus = Style.Default.reverse,
    loading = LoadingTheme(
      spinner = Style.Default.withFg(Color.Cyan),
      label = Style.Default.dim,
      track = Style.Default.withFg(Color.Indexed(238)),
      fill = Style.Default.withFg(Color.Cyan),
      band = Style.Default.withFg(Color.Indexed(245)),
    ),
    markdown = MarkdownTheme(syntax = DarkSyntax),
    syntax = DarkSyntax,
    components = ThemePresets.standardComponents(Color.Cyan, Color.Black, Color.Red),
  )

  val Light: Theme = Theme(
    name = "light",
    primary = Style.Default.withFg(Color.Black),
    accent = Style.Default.withFg(Color.Blue),
    muted = Style.Default.withFg(Color.Indexed(245)),
    error = Style.Default.withFg(Color.Red),
    warning = Style.Default.withFg(Color.Indexed(130)),
    success = Style.Default.withFg(Color.Green),
    surface = Style.Default.withBg(Color.Indexed(253)).withFg(Color.Black),
    border = Style.Default.withFg(Color.Indexed(248)),
    focus = Style.Default.reverse,
    loading = LoadingTheme(
      spinner = Style.Default.withFg(Color.Blue),
      label = Style.Default.withFg(Color.Indexed(245)),
      track = Style.Default.withFg(Color.Indexed(252)),
      fill = Style.Default.withFg(Color.Blue),
      band = Style.Default.withFg(Color.Indexed(248)),
    ),
    // Cyan and Yellow — the widget defaults — are close to invisible on a white background, so the light document
    // palette moves to blue headings and a dark ochre for inline code
    markdown = MarkdownTheme(
      heading1 = Style.Default.bold.withFg(Color.Blue),
      heading2 = Style.Default.bold.withFg(Color.Black),
      heading3 = Style.Default.underline.withFg(Color.Black),
      strong = Style.Default.bold.withFg(Color.Black),
      emphasis = Style.Default.italic.withFg(Color.Black),
      code = Style.Default.withFg(Color.Indexed(130)),
      quote = Style.Default.italic.withFg(Color.Indexed(245)),
      bullet = Style.Default.withFg(Color.Blue),
      link = Style.Default.withFg(Color.Blue).underline,
      syntax = LightSyntax,
      tableHeader = Style.Default.bold.withFg(Color.Black),
      tableBorder = Style.Default.withFg(Color.Indexed(248)),
      taskDone = Style.Default.withFg(Color.Indexed(28)),
    ),
    syntax = LightSyntax,
    components = ThemePresets.standardComponents(Color.Blue, Color.White, Color.Red),
  )

  val HighContrast: Theme = Theme(
    name = "high-contrast",
    primary = Style.Default.withFg(Color.White).bold,
    accent = Style.Default.withFg(Color.Yellow).bold,
    muted = Style.Default.withFg(Color.White),
    error = Style.Default.withFg(Color.Red).bold,
    warning = Style.Default.withFg(Color.Yellow).bold,
    success = Style.Default.withFg(Color.Green).bold,
    surface = Style.Default.withBg(Color.Black).withFg(Color.White).bold,
    border = Style.Default.withFg(Color.White),
    focus = Style.Default.reverse.bold,
    loading = LoadingTheme(
      spinner = Style.Default.withFg(Color.Yellow).bold,
      label = Style.Default.withFg(Color.White),
      track = Style.Default.withFg(Color.White),
      fill = Style.Default.withFg(Color.Yellow).bold,
      band = Style.Default.withFg(Color.White).bold,
    ),
    // no hue carries meaning here: weight and underline do the work, so the palette stays legible on a monochrome or
    // heavily colour-filtered terminal
    markdown = MarkdownTheme(
      heading1 = Style.Default.withFg(Color.Yellow).bold,
      heading2 = Style.Default.withFg(Color.White).bold,
      heading3 = Style.Default.withFg(Color.White).underline,
      strong = Style.Default.withFg(Color.White).bold,
      emphasis = Style.Default.withFg(Color.White).italic,
      code = Style.Default.withFg(Color.Yellow).bold,
      quote = Style.Default.withFg(Color.White).italic,
      bullet = Style.Default.withFg(Color.Yellow).bold,
      link = Style.Default.withFg(Color.White).underline.bold,
      syntax = HighContrastSyntax,
      tableHeader = Style.Default.withFg(Color.Yellow).bold,
      tableBorder = Style.Default.withFg(Color.White),
      taskDone = Style.Default.withFg(Color.Green).bold,
    ),
    syntax = HighContrastSyntax,
    components = ThemePresets.standardComponents(Color.Yellow, Color.Black, Color.Red),
  )

  /** [Dracula](https://draculatheme.com): purple accent, pink keywords. See [[ThemePresets]] for what the palette
    * presets share — foregrounds only, best on a terminal set to the scheme's own background.
    */
  val Dracula: Theme = ThemePresets.Dracula

  /** [Catppuccin](https://catppuccin.com) in its darkest flavour, Mocha: mauve accent over soft pastels. */
  val Catppuccin: Theme = ThemePresets.Catppuccin

  /** [Nord](https://www.nordtheme.com): frost-blue accent over the polar-night greys. */
  val Nord: Theme = ThemePresets.Nord

  /** [Gruvbox](https://github.com/morhetz/gruvbox) dark, medium contrast: warm yellow accent. */
  val Gruvbox: Theme = ThemePresets.Gruvbox

  /** [Solarized](https://ethanschoonover.com/solarized) dark: blue accent over the base03 family. */
  val Solarized: Theme = ThemePresets.Solarized

  /** Only the terminal's own sixteen colours, so the app follows whatever palette the user gave their terminal — the
    * one preset that reads right on any background, light or dark.
    */
  val Ansi16: Theme = ThemePresets.Ansi16

  /** Every built-in theme, in the order a theme switcher would offer them; `Theme.parse`'s `extends` names these. */
  val presets: Seq[Theme] = Seq(Dark, Light, HighContrast, Dracula, Catppuccin, Nord, Gruvbox, Solarized, Ansi16)

  /** "Whichever of [[Light]] and [[Dark]] matches the terminal" — the default `TuiApp.theme` since 0.20.0.
    *
    * A placeholder rather than a palette of its own: `TuiApp` recognises this exact value (by identity) and resolves it
    * on every frame through [[forScheme]] against the colour scheme the terminal reported — OSC 11 at start-up, mode
    * 2031 afterwards — so an app that never set a theme gets `Light` on a light terminal and follows a live switch. A
    * terminal that could not say resolves to `Dark`, which is what every app got before the terminal could be asked.
    *
    * Outside a `TuiApp` it *is* `Dark` under another name, so a widget-level test or a helper handed it renders exactly
    * as it would under `Dark`. Building on it with a `with*` method produces an ordinary theme that is no longer
    * resolved: start from [[Light]] or [[Dark]] for a custom palette, and read `TuiApp.colorScheme` or `lightDark` to
    * pick between two of your own.
    */
  val auto: Theme = Dark.withName("auto")

  /** The preset for `scheme`: [[Light]] for a light terminal, [[Dark]] for a dark one and for one that could not say.
    */
  def forScheme(scheme: ColorScheme): Theme =
    scheme match
      case ColorScheme.Light => Light
      case _                 => Dark

  /** The ambient default, for element construction that happens outside a `view`: a widget-level test, a helper object
    * that builds a panel once, a REPL session.
    *
    * It is deliberately *not* how a running application gets its theme. `TuiApp` passes [[TuiApp.theme]] into `view` as
    * a `using` parameter (see [[View]]), so inside a view the app's own theme always wins over this one — which is the
    * bug this arrangement fixes: a `given` installed by the framework *around* the call to `view` is not in scope
    * inside the view's body, so an app that overrode `theme` and then called `statusBar(bindings)` used to fall back
    * here without saying so.
    *
    * It is `Dark` by every measure but identity: a distinct instance, so a themed factory can tell "no theme in scope"
    * from an explicit `using Theme.Dark` and late-bind only the former to the app's theme when it is painted.
    */
  given default: Theme = Dark.withName(Dark.name)

  /** `base` with its glyph ceiling lowered to whatever the environment justifies — see
    * [[io.worxbend.tui.terminal.TerminalGlyphs.detect]] for the rules and for the `GLYPHORA_ASCII` override.
    *
    * The one place the pure value in `tui-core` meets the environment reading in `tui-terminal`, so that an application
    * writing `theme = Theme.detected()` needs no import beyond `io.worxbend.tui.dsl.*`.
    *
    * Only the ceiling moves; every colour in `base` is kept — it is [[withGlyphs]] over `base`. Call it once, at
    * start-up, and hold the result: it reads the process environment, which does not change under a running
    * application.
    */
  def detected(base: Theme = Dark, env: Map[String, String] = sys.env): Theme =
    base.withGlyphs(TerminalGlyphs.detect(env))

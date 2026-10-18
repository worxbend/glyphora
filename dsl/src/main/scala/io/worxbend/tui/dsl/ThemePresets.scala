package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Style}
import io.worxbend.tui.widgets.{MarkdownTheme, SyntaxTheme}

/** The palette presets — `Theme.Dracula`, `Theme.Catppuccin`, `Theme.Nord`, `Theme.Gruvbox`, `Theme.Solarized` and
  * `Theme.Ansi16` — and the component set every preset shares.
  *
  * The five colour presets are each written as one [[Palette]] of the scheme's published colours and expanded by
  * [[fromPalette]], so every one of them fills every role, palette and component the same way and a new field in
  * [[Theme]] is a change in one function rather than in five themes. They paint foregrounds only and leave the
  * terminal's background alone, so they read best on a terminal set to the scheme's own background.
  *
  * `Ansi16` is the exception on purpose: it names only the sixteen colours of the terminal's own palette, so it follows
  * whatever theme the user gave their terminal — the one preset that is right on any background.
  */
private[dsl] object ThemePresets:

  /** One scheme's colours, by the job they do in a theme rather than by the scheme's own names. */
  private final case class Palette(
      name: String,
      foreground: Color,
      background: Color,
      surface: Color,
      border: Color,
      muted: Color,
      accent: Color,
      secondary: Color,
      error: Color,
      warning: Color,
      success: Color,
      keyword: Color,
      string: Color,
      number: Color,
      function: Color,
      variable: Color,
  )

  /** The components every preset carries: a filled `button.primary` in the accent and a `button.danger` in the error
    * colour, each underlined and bold while focused and dimmed while disabled. A theme with no components at all is
    * still valid — `.variant(...)` then simply draws the plain button.
    */
  def standardComponents(accent: Color, onAccent: Color, danger: Color): Map[StyleName, StateStyles] =
    def filled(fill: Color): StateStyles =
      StateStyles(
        base = Style.of(onAccent, fill),
        focused = Style.Default.bold.underline,
        disabled = Style.Default.dim,
      )
    Map(ButtonRole.Primary.styleName -> filled(accent), ButtonRole.Danger.styleName -> filled(danger))

  private def fg(color: Color): Style = Style.fg(color)

  private def fromPalette(p: Palette): Theme =
    val syntax = SyntaxTheme(
      keyword = fg(p.keyword),
      string = fg(p.string),
      number = fg(p.number),
      comment = fg(p.muted).italic,
      function = fg(p.function),
      variable = fg(p.variable),
      default = fg(p.foreground),
      inserted = fg(p.success),
      deleted = fg(p.error),
    )
    Theme(
      name = p.name,
      primary = fg(p.foreground),
      accent = fg(p.accent),
      muted = fg(p.muted),
      error = fg(p.error),
      warning = fg(p.warning),
      success = fg(p.success),
      surface = Style.of(p.foreground, p.surface),
      border = fg(p.border),
      focus = Style.of(p.background, p.accent),
      loading = LoadingTheme(
        spinner = fg(p.accent),
        label = fg(p.muted),
        track = fg(p.surface),
        fill = fg(p.accent),
        band = fg(p.muted),
      ),
      markdown = MarkdownTheme(
        heading1 = fg(p.accent).bold,
        heading2 = fg(p.secondary).bold,
        heading3 = fg(p.foreground).underline,
        strong = fg(p.foreground).bold,
        emphasis = fg(p.foreground).italic,
        code = fg(p.number),
        quote = fg(p.muted).italic,
        bullet = fg(p.accent),
        link = fg(p.secondary).underline,
        syntax = syntax,
        tableHeader = fg(p.accent).bold,
        tableBorder = fg(p.border),
        taskDone = fg(p.success),
      ),
      syntax = syntax,
      components = standardComponents(p.accent, p.background, p.error),
    )

  private def hex(value: String): Color = Color.hex(value).getOrElse(Color.Reset)

  /** Dracula — draculatheme.com. */
  val Dracula: Theme = fromPalette(
    Palette(
      name = "dracula",
      foreground = hex("#f8f8f2"),
      background = hex("#282a36"),
      surface = hex("#44475a"),
      border = hex("#6272a4"),
      muted = hex("#6272a4"),
      accent = hex("#bd93f9"),
      secondary = hex("#8be9fd"),
      error = hex("#ff5555"),
      warning = hex("#ffb86c"),
      success = hex("#50fa7b"),
      keyword = hex("#ff79c6"),
      string = hex("#f1fa8c"),
      number = hex("#bd93f9"),
      function = hex("#50fa7b"),
      variable = hex("#8be9fd"),
    )
  )

  /** Catppuccin, its darkest flavour, Mocha — catppuccin.com. */
  val Catppuccin: Theme = fromPalette(
    Palette(
      name = "catppuccin",
      foreground = hex("#cdd6f4"),
      background = hex("#1e1e2e"),
      surface = hex("#313244"),
      border = hex("#6c7086"),
      muted = hex("#7f849c"),
      accent = hex("#cba6f7"),
      secondary = hex("#89b4fa"),
      error = hex("#f38ba8"),
      warning = hex("#fab387"),
      success = hex("#a6e3a1"),
      keyword = hex("#cba6f7"),
      string = hex("#a6e3a1"),
      number = hex("#fab387"),
      function = hex("#89b4fa"),
      variable = hex("#f5e0dc"),
    )
  )

  /** Nord — nordtheme.com. */
  val Nord: Theme = fromPalette(
    Palette(
      name = "nord",
      foreground = hex("#d8dee9"),
      background = hex("#2e3440"),
      surface = hex("#3b4252"),
      border = hex("#4c566a"),
      muted = hex("#616e88"),
      accent = hex("#88c0d0"),
      secondary = hex("#81a1c1"),
      error = hex("#bf616a"),
      warning = hex("#ebcb8b"),
      success = hex("#a3be8c"),
      keyword = hex("#81a1c1"),
      string = hex("#a3be8c"),
      number = hex("#b48ead"),
      function = hex("#88c0d0"),
      variable = hex("#d8dee9"),
    )
  )

  /** Gruvbox, the dark variant at medium contrast — github.com/morhetz/gruvbox. */
  val Gruvbox: Theme = fromPalette(
    Palette(
      name = "gruvbox",
      foreground = hex("#ebdbb2"),
      background = hex("#282828"),
      surface = hex("#3c3836"),
      border = hex("#665c54"),
      muted = hex("#928374"),
      accent = hex("#fabd2f"),
      secondary = hex("#83a598"),
      error = hex("#fb4934"),
      warning = hex("#fe8019"),
      success = hex("#b8bb26"),
      keyword = hex("#fb4934"),
      string = hex("#b8bb26"),
      number = hex("#d3869b"),
      function = hex("#8ec07c"),
      variable = hex("#83a598"),
    )
  )

  /** Solarized Dark — ethanschoonover.com/solarized. */
  val Solarized: Theme = fromPalette(
    Palette(
      name = "solarized",
      foreground = hex("#839496"),
      background = hex("#002b36"),
      surface = hex("#073642"),
      border = hex("#586e75"),
      muted = hex("#586e75"),
      accent = hex("#268bd2"),
      secondary = hex("#2aa198"),
      error = hex("#dc322f"),
      warning = hex("#b58900"),
      success = hex("#859900"),
      keyword = hex("#859900"),
      string = hex("#2aa198"),
      number = hex("#d33682"),
      function = hex("#268bd2"),
      variable = hex("#b58900"),
    )
  )

  /** The terminal's own sixteen colours and nothing else, so the theme follows the user's terminal palette. */
  val Ansi16: Theme =
    val syntax = SyntaxTheme(
      keyword = fg(Color.Magenta),
      string = fg(Color.Green),
      number = fg(Color.Yellow),
      comment = fg(Color.BrightBlack).italic,
      function = fg(Color.Cyan),
      variable = fg(Color.Blue),
      default = Style.Default,
      inserted = fg(Color.Green),
      deleted = fg(Color.Red),
    )
    Theme(
      name = "ansi16",
      primary = Style.Default,
      accent = fg(Color.Cyan),
      muted = fg(Color.BrightBlack),
      error = fg(Color.Red),
      warning = fg(Color.Yellow),
      success = fg(Color.Green),
      surface = Style.of(Color.White, Color.BrightBlack),
      border = fg(Color.BrightBlack),
      focus = Style.Default.reverse,
      loading = LoadingTheme(
        spinner = fg(Color.Cyan),
        label = fg(Color.BrightBlack),
        track = fg(Color.BrightBlack),
        fill = fg(Color.Cyan),
        band = fg(Color.White),
      ),
      markdown = MarkdownTheme(
        heading1 = fg(Color.Cyan).bold,
        heading2 = Style.Default.bold,
        heading3 = Style.Default.underline,
        strong = Style.Default.bold,
        emphasis = Style.Default.italic,
        code = fg(Color.Yellow),
        quote = fg(Color.BrightBlack).italic,
        bullet = fg(Color.Cyan),
        link = fg(Color.Blue).underline,
        syntax = syntax,
        tableHeader = Style.Default.bold,
        tableBorder = fg(Color.BrightBlack),
        taskDone = fg(Color.Green),
      ),
      syntax = syntax,
      components = standardComponents(Color.Cyan, Color.Black, Color.Red),
    )

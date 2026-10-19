package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, GlyphSupport, Style}
import io.worxbend.tui.widgets.{ColorRamp, MarkdownTheme, SyntaxTheme}

import java.util.Locale

/** The `key = value` text format [[Theme.parse]] reads and [[Theme.render]] writes, so an end user can restyle an app
  * without recompiling it.
  *
  * {{{
  * # a theme file
  * name    = midnight
  * extends = nord                 # optional: start from a preset instead of from scratch
  * accent  = bold #88c0d0
  * border  = #4c566a
  * loading.fill        = #a3be8c
  * markdown.heading1   = bold #88c0d0
  * syntax.keyword      = #81a1c1
  * component.button.primary         = #2e3440 on #88c0d0
  * component.button.primary:focused = bold #2e3440 on #8fbcbb
  * }}}
  *
  * One setting per line; blank lines and lines whose first visible character is `#` are ignored (a `#` later on a line
  * is part of the value, because colours are written `#rrggbb`). Every value except `name`, `extends` and `glyphs` is a
  * style in [[io.worxbend.tui.core.Style.parse]]'s spelling.
  *
  *   - The semantic roles: `primary`, `accent`, `muted`, `error`, `warning`, `success`, `surface`, `border`, `focus`,
  *     and `disabled` — written back only when the theme set it, since left out it follows `muted`.
  *   - The palettes, by dotted path: `loading.<spinner|label|track|fill|band>`, `markdown.<field>` and
  *     `markdown.syntax.<field>`, `syntax.<field>` — the field names of `LoadingTheme`, `MarkdownTheme` and
  *     `SyntaxTheme`, with camel case written in dashes (`markdown.table-header`).
  *   - Components: `component.<style name>` sets a component's `base`, and `component.<style name>:<state>` one of its
  *     `focused`, `hovered`, `pressed` or `disabled` styles — see [[StateStyles]] and [[StyleName]].
  *   - `glyphs` is `full`, `box-drawing` or `ascii`.
  *   - `loading.fill-ramp` is the loading `fillRamp`: its colour stops separated by commas, in
  *     [[io.worxbend.tui.core.Color.parse]]'s spelling (`green, yellow, red`, `#a3be8c, #bf616a`), or `none`.
  *
  * A setting the file leaves out keeps the base theme's value. Without `extends` the base is [[Theme.Dark]] *with no
  * components*, so a file [[Theme.render]] wrote — which spells every setting out — reads back as exactly the theme it
  * was written from; with `extends = <preset>` the base is that preset, components included.
  *
  * Parsed by hand, line by line; no reflection.
  */
private[dsl] object ThemeFile:

  /** A style-valued setting: its key, how to read it off a theme and how to put it back. */
  private final case class Setting(key: String, read: Theme => Style, write: (Theme, Style) => Theme)

  private def role(key: String, read: Theme => Style, write: (Theme, Style) => Theme): Setting =
    Setting(key, read, write)

  private val Roles: Seq[Setting] = Seq(
    role("primary", _.primary, _.withPrimary(_)),
    role("accent", _.accent, _.withAccent(_)),
    role("muted", _.muted, _.withMuted(_)),
    role("error", _.error, _.withError(_)),
    role("warning", _.warning, _.withWarning(_)),
    role("success", _.success, _.withSuccess(_)),
    role("surface", _.surface, _.withSurface(_)),
    role("border", _.border, _.withBorder(_)),
    role("focus", _.focus, _.withFocus(_)),
  )

  private def loading(key: String, read: LoadingTheme => Style, write: (LoadingTheme, Style) => LoadingTheme) =
    Setting(s"loading.$key", theme => read(theme.loading), (theme, s) => theme.withLoading(write(theme.loading, s)))

  private val Loading: Seq[Setting] = Seq(
    loading("spinner", _.spinner, (l, s) => l.copy(spinner = s)),
    loading("label", _.label, (l, s) => l.copy(label = s)),
    loading("track", _.track, (l, s) => l.copy(track = s)),
    loading("fill", _.fill, (l, s) => l.copy(fill = s)),
    loading("band", _.band, (l, s) => l.copy(band = s)),
  )

  /** The `SyntaxTheme` fields, as (key, read, write) over the palette itself, shared by both places it appears. */
  private val SyntaxFields: Seq[(String, SyntaxTheme => Style, (SyntaxTheme, Style) => SyntaxTheme)] = Seq(
    ("keyword", _.keyword, (t, s) => t.copy(keyword = s)),
    ("string", _.string, (t, s) => t.copy(string = s)),
    ("number", _.number, (t, s) => t.copy(number = s)),
    ("comment", _.comment, (t, s) => t.copy(comment = s)),
    ("function", _.function, (t, s) => t.copy(function = s)),
    ("variable", _.variable, (t, s) => t.copy(variable = s)),
    ("default", _.default, (t, s) => t.copy(default = s)),
    ("inserted", _.inserted, (t, s) => t.copy(inserted = s)),
    ("deleted", _.deleted, (t, s) => t.copy(deleted = s)),
  )

  private val MarkdownFields: Seq[(String, MarkdownTheme => Style, (MarkdownTheme, Style) => MarkdownTheme)] = Seq(
    ("heading1", _.heading1, (t, s) => t.copy(heading1 = s)),
    ("heading2", _.heading2, (t, s) => t.copy(heading2 = s)),
    ("heading3", _.heading3, (t, s) => t.copy(heading3 = s)),
    ("strong", _.strong, (t, s) => t.copy(strong = s)),
    ("emphasis", _.emphasis, (t, s) => t.copy(emphasis = s)),
    ("code", _.code, (t, s) => t.copy(code = s)),
    ("quote", _.quote, (t, s) => t.copy(quote = s)),
    ("bullet", _.bullet, (t, s) => t.copy(bullet = s)),
    ("link", _.link, (t, s) => t.copy(link = s)),
    ("table-header", _.tableHeader, (t, s) => t.copy(tableHeader = s)),
    ("table-border", _.tableBorder, (t, s) => t.copy(tableBorder = s)),
    ("task-done", _.taskDone, (t, s) => t.copy(taskDone = s)),
  )

  private val Markdown: Seq[Setting] =
    MarkdownFields.map { case (key, read, write) =>
      Setting(
        s"markdown.$key",
        theme => read(theme.markdown),
        (theme, s) => theme.withMarkdown(write(theme.markdown, s)),
      )
    } ++ SyntaxFields.map { case (key, read, write) =>
      Setting(
        s"markdown.syntax.$key",
        theme => read(theme.markdown.syntax),
        (theme, s) => theme.withMarkdown(theme.markdown.copy(syntax = write(theme.markdown.syntax, s))),
      )
    }

  private val Syntax: Seq[Setting] =
    SyntaxFields.map { case (key, read, write) =>
      Setting(s"syntax.$key", theme => read(theme.syntax), (theme, s) => theme.withSyntax(write(theme.syntax, s)))
    }

  private val Settings: Seq[Setting]              = Roles ++ Loading ++ Markdown ++ Syntax
  private val SettingsByKey: Map[String, Setting] = Settings.map(setting => setting.key -> setting).toMap

  private val States: Seq[(String, StateStyles => Style, (StateStyles, Style) => StateStyles)] = Seq(
    ("focused", _.focused, (s, style) => s.copy(focused = style)),
    ("hovered", _.hovered, (s, style) => s.copy(hovered = style)),
    ("pressed", _.pressed, (s, style) => s.copy(pressed = style)),
    ("disabled", _.disabled, (s, style) => s.copy(disabled = style)),
  )

  private val GlyphNames: Seq[(String, GlyphSupport)] =
    Seq("full" -> GlyphSupport.Full, "box-drawing" -> GlyphSupport.BoxDrawing, "ascii" -> GlyphSupport.Ascii)

  /** The presets `extends` may name, by their own `name`. */
  private def preset(name: String): Option[Theme] =
    Theme.presets.find(_.name == name.toLowerCase(Locale.ROOT))

  private val ComponentPrefix = "component."

  private val FillRampKey = "loading.fill-ramp"

  /** The disabled role: written only when the theme set one, so a file that leaves it out keeps it derived from
    * `muted`.
    */
  private val DisabledKey = "disabled"

  /** A `loading.fill-ramp` value: `none`, or one or more comma-separated colours. */
  private def ramp(number: Int, value: String): Either[LineError, Option[ColorRamp]] =
    if value.trim.toLowerCase(Locale.ROOT) == "none" then Right(None)
    else
      value
        .split(',')
        .toVector
        .foldLeft[Either[LineError, Vector[Color]]](Right(Vector.empty)) { (acc, stop) =>
          acc.flatMap(stops =>
            Color.parse(stop).map(stops :+ _).left.map(message => LineError(number, s"$FillRampKey: $message"))
          )
        }
        .map(stops => Some(ColorRamp(stops)))

  private def renderRamp(fill: Option[ColorRamp]): String =
    fill.fold("none")(_.stops.map(_.render).mkString(", "))

  def parse(text: String): Either[LineError, Theme] =
    KeyValueLines.read(text, "key = value").flatMap { lines =>
      val settings = lines.map(line => (line.number, line.key, line.value))
      base(settings).flatMap(start => settings.foldLeft[Either[LineError, Theme]](Right(start))(apply))
    }

  /** The theme a file starts from: the `extends` preset, or `Dark` without its components. */
  private def base(settings: Seq[(Int, String, String)]): Either[LineError, Theme] =
    settings.find(_._2 == "extends") match
      case None                     => Right(Theme.Dark.withoutComponents)
      case Some((number, _, value)) =>
        preset(value).toRight(
          LineError(number, s"'$value' is not a preset; one of ${Theme.presets.map(_.name).mkString(", ")}")
        )

  private def apply(result: Either[LineError, Theme], entry: (Int, String, String)): Either[LineError, Theme] =
    result.flatMap { theme =>
      val (number, key, value)            = entry
      def style: Either[LineError, Style] =
        Style.parse(value).left.map(message => LineError(number, s"$key: $message"))
      key match
        case "extends"   => Right(theme)
        case "name"      =>
          if value.isEmpty then Left(LineError(number, "name: a theme needs a name")) else Right(theme.withName(value))
        case DisabledKey =>
          style.map(theme.withDisabled)
        case FillRampKey =>
          ramp(number, value).map(fill => theme.withLoading(theme.loading.copy(fillRamp = fill)))
        case "glyphs"    =>
          GlyphNames
            .find(_._1 == value.toLowerCase(Locale.ROOT))
            .map(found => theme.withGlyphs(found._2))
            .toRight(LineError(number, s"glyphs: '$value' is not one of ${GlyphNames.map(_._1).mkString(", ")}"))
        case _           =>
          SettingsByKey.get(key) match
            case Some(setting)                           => style.map(setting.write(theme, _))
            case None if key.startsWith(ComponentPrefix) =>
              component(theme, number, key.drop(ComponentPrefix.length), style)
            case None                                    => Left(LineError(number, s"'$key' is not a theme setting"))
    }

  private def component(
      theme: Theme,
      number: Int,
      path: String,
      style: => Either[LineError, Style],
  ): Either[LineError, Theme] =
    val colon          = path.indexOf(':')
    val (named, state) = if colon < 0 then (path, None) else (path.take(colon), Some(path.drop(colon + 1)))
    for
      name   <- StyleName.parse(named).left.map(message => LineError(number, message))
      update <- state match
        case None        => Right((styles: StateStyles, s: Style) => styles.copy(base = s))
        case Some(which) =>
          States
            .find(_._1 == which)
            .map(_._3)
            .toRight(LineError(number, s"'$which' is not a state; one of ${States.map(_._1).mkString(", ")}"))
      value  <- style
    yield
      val current = theme.components.getOrElse(name, StateStyles.Empty)
      theme.withComponent(name, update(current, value))

  def render(theme: Theme): String =
    val header   = Seq(s"name = ${theme.name}", s"glyphs = ${GlyphNames.find(_._2 == theme.glyphs).fold("full")(_._1)}")
    val disabled = Option.when(theme.disabledIsSet)(s"$DisabledKey = ${Style.render(theme.disabled)}").toSeq
    val settings = Settings.map(setting => s"${setting.key} = ${Style.render(setting.read(theme))}") ++ disabled :+
      s"$FillRampKey = ${renderRamp(theme.loading.fillRamp)}"
    val components = theme.components.toSeq.sortBy(_._1.value).flatMap { (name, styles) =>
      s"component.${name.value} = ${Style.render(styles.base)}" +:
        States.collect {
          case (state, read, _) if read(styles) != Style.Default =>
            s"component.${name.value}:$state = ${Style.render(read(styles))}"
        }
    }
    (Seq("# glyphora theme") ++ header ++ settings ++ components).mkString("", "\n", "\n")

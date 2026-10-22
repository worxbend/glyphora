package io.worxbend.tui.core

/** The line drawn under a cell's glyph, independent of the plain underline modifier.
  *
  * `Straight` mirrors the classic `Modifiers.Underline`; the richer variants use the colon-parameterised SGR 4
  * extension (`4:2`…`4:5`) that modern terminals (kitty, VTE, WezTerm, iTerm2) understand and older ones ignore.
  */
enum UnderlineStyle:
  case None, Straight, Double, Curly, Dotted, Dashed

/** How a cell is drawn: optional foreground/background colors plus text-attribute modifiers.
  *
  * `None` for a color means "leave the terminal default in effect". Builders return a new immutable `Style`; they are
  * `with`-prefixed for the color fields because a field and a `def` cannot share a name.
  *
  * A `final class` with a private constructor rather than a `case class`, because this is the type most likely to gain
  * a field as terminals grow attributes. A case class rewrites `apply`, `copy` and `unapply` on every new field, and a
  * consumer compiled against the old shape fails with `NoSuchMethodError`; here the compatibility surface is
  * [[Style.Default]], [[Style.Reset]], the [[Style.fg]]/[[Style.bg]]/[[Style.of]]/[[Style.mods]] factories and the
  * `with*` builders, all of which a new field leaves untouched. There is no `copy` and no pattern extractor; equality
  * and `hashCode` are structural over every field, which [[Cell]] — a case class holding a `Style` — and so [[Buffer]]
  * equality, frame diffing and style memoisation all rely on.
  *
  * Text attributes are carried in *two* bitsets, not one (ratatui's `add_modifier`/`sub_modifier` pair). [[modifiers]]
  * is what the cell renders with; [[clearedModifiers]] records the flags the style was asked to turn off. Without the
  * second set a style has no way to say "not bold" — only "bold" or "silent about bold" — and the moment it is layered
  * onto a bolder one with [[io.worxbend.tui.core.Style#patch patch]], the flag it deliberately dropped comes back.
  *
  * @param clearedModifiers
  *   flags this style turns off when it is layered onto another with [[io.worxbend.tui.core.Style#patch patch]]. Always
  *   disjoint from [[modifiers]]: every builder that sets a flag clears it here and vice versa, so the last call in a
  *   chain wins.
  */
final class Style private (
    val fg: Option[Color],
    val bg: Option[Color],
    val modifiers: Modifiers,
    val link: Option[String],
    val underlineColor: Option[Color],
    val underlineStyle: UnderlineStyle,
    val clearedModifiers: Modifiers,
):

  /** This style with the named fields replaced — the one place the builders below spell out the constructor. */
  private def updated(
      fg: Option[Color] = fg,
      bg: Option[Color] = bg,
      modifiers: Modifiers = modifiers,
      link: Option[String] = link,
      underlineColor: Option[Color] = underlineColor,
      underlineStyle: UnderlineStyle = underlineStyle,
      clearedModifiers: Modifiers = clearedModifiers,
  ): Style = new Style(fg, bg, modifiers, link, underlineColor, underlineStyle, clearedModifiers)

  def withFg(color: Color): Style = updated(fg = Some(color))

  /** Attaches an OSC 8 hyperlink target — terminals that support it make the cells clickable. */
  def withLink(url: String): Style = updated(link = Some(url))

  /** Drops the hyperlink target. `link = None` is the silent case in [[io.worxbend.tui.core.Style#patch patch]], so a
    * style layered onto a linked one afterwards leaves the link in place; there is no spelling for "no link, and I mean
    * it".
    */
  def withoutLink: Style          = updated(link = None)
  def withBg(color: Color): Style = updated(bg = Some(color))

  /** Replaces the set text attributes wholesale with `flags`, and withdraws any earlier request to turn those flags
    * off, so the two bitsets stay disjoint exactly as they do after [[bold]] and the other single-flag builders. Those
    * add one flag; this is for a caller that holds a whole [[Modifiers]] value, the shape [[Style.mods]] takes.
    */
  def withModifiers(flags: Modifiers): Style =
    updated(modifiers = flags, clearedModifiers = clearedModifiers.without(flags))

  /** Replaces the cleared text attributes wholesale with `flags`, dropping them from [[modifiers]] so the two bitsets
    * stay disjoint — the whole-set counterpart of [[without]].
    */
  def withClearedModifiers(flags: Modifiers): Style =
    updated(modifiers = modifiers.without(flags), clearedModifiers = flags)

  def bold: Style      = setting(Modifiers.Bold)
  def dim: Style       = setting(Modifiers.Dim)
  def italic: Style    = setting(Modifiers.Italic)
  def underline: Style = setting(Modifiers.Underline)
  def blink: Style     = setting(Modifiers.Blink)

  /** Turns on rapid blink (SGR 6) rather than the ordinary [[blink]] (SGR 5). See [[Modifiers.RapidBlink]] for how
    * thinly terminals support the distinction.
    */
  def rapidBlink: Style = setting(Modifiers.RapidBlink)

  def reverse: Style    = setting(Modifiers.Reverse)
  def hidden: Style     = setting(Modifiers.Hidden)
  def crossedOut: Style = setting(Modifiers.CrossedOut)

  /** Turns `flags` on, and withdraws any earlier request to turn them off, so `style.notBold.bold` is bold. */
  private def setting(flags: Modifiers): Style =
    updated(modifiers = modifiers | flags, clearedModifiers = clearedModifiers.without(flags))

  /** Clears specific text-attribute flags (ratatui's `sub_modifier`) — e.g. `style.without(Modifiers.Bold)` un-bolds a
    * style inherited from a theme or parent.
    *
    * The flags are both removed from [[modifiers]] and recorded in [[clearedModifiers]], which is what makes the clear
    * survive [[io.worxbend.tui.core.Style#patch patch]]: a style that merely lacks a flag says nothing about it, while
    * one that recorded the clear turns it off in whatever it is layered onto.
    */
  def without(flags: Modifiers): Style =
    updated(modifiers = modifiers.without(flags), clearedModifiers = clearedModifiers | flags)

  def notBold: Style       = without(Modifiers.Bold)
  def notDim: Style        = without(Modifiers.Dim)
  def notItalic: Style     = without(Modifiers.Italic)
  def notUnderline: Style  = without(Modifiers.Underline)
  def notBlink: Style      = without(Modifiers.Blink)
  def notReverse: Style    = without(Modifiers.Reverse)
  def notHidden: Style     = without(Modifiers.Hidden)
  def notCrossedOut: Style = without(Modifiers.CrossedOut)
  def notRapidBlink: Style = without(Modifiers.RapidBlink)

  /** Sets the foreground to [[Color.Reset]] — the terminal's default — and, exactly like [[without]] does for text
    * attributes, makes that choice survive [[io.worxbend.tui.core.Style#patch patch]].
    *
    * Leaving `fg` at `None` would not do: a style that merely *lacks* a foreground says nothing about it, so the color
    * of whatever it is layered onto comes straight back. Recording the clear as an explicit `Some(Color.Reset)` is what
    * turns "silent about the foreground" into "no foreground, and I mean it".
    */
  def withoutFg: Style = updated(fg = Some(Color.Reset))

  /** Sets the background to [[Color.Reset]] — the terminal's default — and, exactly like [[without]] does for text
    * attributes, makes that choice survive [[io.worxbend.tui.core.Style#patch patch]]. See [[withoutFg]] for why `None`
    * is not the same thing.
    */
  def withoutBg: Style = updated(bg = Some(Color.Reset))

  /** This style with everything [[Style.Reset]] clears layered on top — the same value as `patch(Style.Reset)`.
    *
    * Named `reset` to match the terminal vocabulary and ratatui's `Stylize::reset`. See [[Style.Reset]] for the two
    * fields it deliberately leaves alone.
    */
  def reset: Style = patch(Style.Reset)

  /** Colors the underline separately from the glyph (SGR 58) — terminals without support draw a default-colored line.
    */
  def withUnderlineColor(color: Color): Style = updated(underlineColor = Some(color))

  /** Picks a styled underline.
    *
    * `Double`/`Curly`/`Dotted`/`Dashed` emit the SGR `4:n` selector, which draws the line on its own. `Straight` (like
    * `None`) emits nothing extra and defers to the plain [[Modifiers.Underline]] flag — combine it with [[underline]]
    * if you want a line.
    */
  def withUnderlineStyle(style: UnderlineStyle): Style = updated(underlineStyle = style)
  def doubleUnderline: Style                           = withUnderlineStyle(UnderlineStyle.Double)
  def curlyUnderline: Style                            = withUnderlineStyle(UnderlineStyle.Curly)
  def dottedUnderline: Style                           = withUnderlineStyle(UnderlineStyle.Dotted)
  def dashedUnderline: Style                           = withUnderlineStyle(UnderlineStyle.Dashed)

  /** This style with `other`'s explicit choices layered on top: `other`'s colors win where set, `other`'s modifiers are
    * added, and the modifiers `other` [[without]]-cleared are removed.
    *
    * The result carries both sets forward, so layering is associative: `a.patch(b).patch(c)` and `a.patch(b.patch(c))`
    * agree. A flag `other` clears is dropped from the union *and* stays recorded as cleared, unless this style sets it
    * again — which is how a theme → element → span chain lets any level have the final say on a flag.
    *
    * Colors need no second field to say the same thing, because they already have a spelling for "the terminal
    * default": [[Color.Reset]]. `Some(Color.Reset)` is an explicit choice and wins here like any other color, while
    * `None` is the silent case that defers. [[withoutFg]] and [[withoutBg]] are the builders that pick the former.
    */
  def patch(other: Style): Style =
    Style(
      fg = other.fg.orElse(fg),
      bg = other.bg.orElse(bg),
      modifiers = (modifiers | other.modifiers).without(other.clearedModifiers),
      link = other.link.orElse(link),
      underlineColor = other.underlineColor.orElse(underlineColor),
      underlineStyle = if other.underlineStyle == UnderlineStyle.None then underlineStyle else other.underlineStyle,
      clearedModifiers = (clearedModifiers | other.clearedModifiers).without(other.modifiers),
    )

  /** Whether this style still paints its column when the glyph in it is a blank space.
    *
    * A background colour is drawn across the whole cell rather than behind the glyph's ink, and so are reverse video,
    * underline, blink and crossed-out (all four draw something — a filled block, a rule, a strike — that a space does
    * not hide). A foreground colour or bold, by contrast, is invisible on a space. This is the test for "the terminal
    * would still be showing something here", which is what makes a vacated continuation column worth repainting — the
    * frame-diff engine ([[FrameDiff]]) is its one caller.
    */
  private[core] def visibleOnBlank: Boolean =
    bg.exists(_ != Color.Reset) ||
      modifiers.hasAny(Modifiers.Reverse | Modifiers.Underline | Modifiers.Blink | Modifiers.CrossedOut)

  /** Structural equality over every field, reference equality tried first.
    *
    * This runs once per cell in [[Buffer]]'s equality and frame diff and in [[MemoizedStyleTransform]], and a run of
    * cells written by one call usually shares the very same `Style` object, so the `eq` short-cut is the common exit.
    * The two bitsets are compared before the `Option`s because they are plain `Int`s.
    */
  override def equals(other: Any): Boolean =
    other match
      case that: Style =>
        (this eq that) ||
        (modifiers == that.modifiers && clearedModifiers == that.clearedModifiers && fg == that.fg && bg == that.bg &&
          underlineStyle == that.underlineStyle && underlineColor == that.underlineColor && link == that.link)
      case _           => false

  /** Hashes every field [[equals]] compares. A hash that forgot one would make two unequal styles collide in a
    * `Set[Style]`, in `.distinct` and in the memoised transforms — silently, since `equals` would still tell them
    * apart.
    */
  override def hashCode(): Int =
    var hash = fg.hashCode()
    hash = 31 * hash + bg.hashCode()
    hash = 31 * hash + modifiers.hashCode()
    hash = 31 * hash + link.hashCode()
    hash = 31 * hash + underlineColor.hashCode()
    hash = 31 * hash + underlineStyle.hashCode()
    31 * hash + clearedModifiers.hashCode()

  /** The default `toString` of a class is its name and an object hash, which is unreadable in exactly the place it is
    * read most: a failed assertion on a cell's style. Fields left at their default are elided so the common style
    * prints as a short line.
    */
  override def toString: String =
    val parts = Seq(
      fg.map(color => s"fg=$color"),
      bg.map(color => s"bg=$color"),
      Option.when(!modifiers.isEmpty)(s"modifiers=${modifiers.show}"),
      Option.when(!clearedModifiers.isEmpty)(s"cleared=${clearedModifiers.show}"),
      link.map(url => s"link=$url"),
      underlineColor.map(color => s"underlineColor=$color"),
      Option.when(underlineStyle != UnderlineStyle.None)(s"underlineStyle=$underlineStyle"),
    ).flatten
    if parts.isEmpty then "Style.Default" else parts.mkString("Style(", ", ", ")")

  /** This style spelled as the Scala expression that rebuilds it: `Style.Default.withFg(Color.Cyan).bold.notItalic`.
    *
    * [[toString]] is written to be *read* — it is what a failure message shows. This one is written to be *pasted*:
    * into the expected value of the assertion that just failed, or into a REPL. A style with nothing set prints as
    * `Style.Default`, which is exactly the expression that builds it.
    *
    * The builder order is fixed — colors, then the modifiers that are set, then the modifiers that were cleared, then
    * the underline, then the link — and each flag appears once, so two equal styles always print the same text and the
    * text always evaluates back to an equal style. Two details of that order are deliberate rather than incidental:
    *
    *   - a foreground or background of [[Color.Reset]] prints as `withoutFg`/`withoutBg`. Both spellings build the same
    *     value, but those are the builders documented for "no color, and I mean it", so they are what a reader pasting
    *     the text should see.
    *   - cleared modifiers come after set ones, because [[setting]] withdraws a clear. Printing `notBold` before `bold`
    *     would produce text that no longer records the clear when it is evaluated.
    */
  def asSource: String =
    val calls =
      Seq(
        fg.map(color => if color == Color.Reset then "withoutFg" else s"withFg(${color.asSource})"),
        bg.map(color => if color == Color.Reset then "withoutBg" else s"withBg(${color.asSource})"),
      ).flatten
        ++ modifiers.builderNames
        ++ clearedModifiers.builderNames.map(name => s"not${name.updated(0, name.charAt(0).toUpper)}")
        ++ Option.when(underlineStyle != UnderlineStyle.None)(s"withUnderlineStyle(UnderlineStyle.$underlineStyle)")
        ++ underlineColor.map(color => s"withUnderlineColor(${color.asSource})")
        ++ link.map(url => s"withLink(${Style.quote(url)})")
    calls.foldLeft("Style.Default")((expression, call) => s"$expression.$call")

object Style:

  /** A style from its fields, every one defaulting to silent — `Style(fg = Some(Color.Red))` is [[Style.fg]].
    *
    * This is a construction convenience, not the compatibility surface. Adding a field changes its descriptor and
    * renumbers its default-argument getters, so a consumer compiled against `Style(fg = …)` still fails with
    * `NoSuchMethodError` on the next field until it recompiles; at that growth the previous arity is kept as a plain
    * default-free overload so compiled callers keep working. Code that must survive a new field without recompiling
    * starts from [[Default]], [[Reset]] or one of the factories below and builds with the `with*` methods.
    */
  def apply(
      fg: Option[Color] = None,
      bg: Option[Color] = None,
      modifiers: Modifiers = Modifiers.None,
      link: Option[String] = None,
      underlineColor: Option[Color] = None,
      underlineStyle: UnderlineStyle = UnderlineStyle.None,
      clearedModifiers: Modifiers = Modifiers.None,
  ): Style = new Style(fg, bg, modifiers, link, underlineColor, underlineStyle, clearedModifiers)

  /** The style that states nothing: every field silent, so patching anything with it changes nothing. */
  val Default: Style = Style()

  /** The style that says "everything back to the terminal's virgin state" — the value to [[Style.patch]] on top of an
    * inherited style when nothing about that style should survive.
    *
    * [[Default]] is the opposite value. It is silent about every field, so layering it changes nothing. `Reset` is loud
    * about every field it can be loud about: foreground, background and underline color become [[Color.Reset]] (the
    * terminal default, an explicit choice that wins in [[io.worxbend.tui.core.Style#patch patch]] the way any other
    * color does), and every text attribute is recorded in [[Style.clearedModifiers]] so the clear survives the layering
    * too.
    *
    * It deliberately does *not* reset [[Style.underlineStyle]] or [[Style.link]]. `patch` treats `UnderlineStyle.None`
    * and `link = None` as the silent cases, so neither field has a spelling for "explicitly off" that could be put
    * here. Call [[Style.withUnderlineStyle]] yourself if a curly underline has to go.
    */
  val Reset: Style = Style(
    fg = Some(Color.Reset),
    bg = Some(Color.Reset),
    underlineColor = Some(Color.Reset),
    clearedModifiers = Modifiers.All,
  )

  /** A style that sets only the foreground: `Style.fg(Color.Red)` is `Style.Default.withFg(Color.Red)`.
    *
    * ratatui reaches this shape through `impl Into<Style> for Color`, so any API taking a style accepts a bare color.
    * Here it is a named factory rather than an implicit conversion, for the reason written on the `styled` extension in
    * `dsl.scala`: a conversion needs a `scala.language.implicitConversions` import at every call site and hides which
    * type the call site actually produced, and neither is worth the handful of characters it saves.
    */
  def fg(color: Color): Style = Style(fg = Some(color))

  /** A style that sets only the background. See [[fg]] for why this is a plain method rather than a conversion. */
  def bg(color: Color): Style = Style(bg = Some(color))

  /** A style that sets both colors — the `(foreground, background)` pair widget constructors take most often. */
  def of(fg: Color, bg: Color): Style = Style(fg = Some(fg), bg = Some(bg))

  /** A style that sets only text attributes: `Style.mods(Modifiers.Bold | Modifiers.Italic)`.
    *
    * Named `mods` rather than being a third overload of [[of]], because [[Modifiers]] is an opaque `Int` and an
    * overload taking one would be hard to tell at a glance from one taking a color.
    */
  def mods(modifiers: Modifiers): Style = Style(modifiers = modifiers)

  /** Reads a style written the way a theme file writes one: `bold #ff0000 on black`.
    *
    * The words, separated by spaces, in any order:
    *
    *   - a text attribute — `bold`, `dim`, `italic`, `underline`, `blink`, `rapid-blink`, `reverse`, `hidden`,
    *     `crossed-out` — sets it, and the same word with `not-` in front (`not-bold`) clears it the way
    *     [[Style.notBold]] does;
    *   - an underline variant — `straight-underline`, `double-underline`, `curly-underline`, `dotted-underline`,
    *     `dashed-underline`;
    *   - a colour on its own is the foreground, and `on <colour>` the background; `underline-color <colour>` colours
    *     the underline. A colour is anything [[Color.parse]] reads — a name, `#rrggbb`, a palette index — and a
    *     two-word name (`bright red`, `light blue`, `dark grey`) may be written with its space;
    *   - `link <url>` attaches a hyperlink target, which therefore cannot contain a space.
    *
    * `default`, or nothing at all, is [[Default]]. A word that is none of these, a second foreground, or an `on` with
    * no colour after it is a `Left` naming the word, so a theme file can report the line it came from. Every style
    * [[render]] writes parses back to an equal style.
    */
  def parse(text: String): Either[String, Style] =
    StyleSyntax.parse(text)

  /** Writes `style` in the spelling [[parse]] reads back: attributes, then cleared attributes, then the underline
    * variant, the foreground, `on` the background, the underline colour and the link. [[Default]] is `default`.
    */
  def render(style: Style): String =
    StyleSyntax.render(style)

  /** `value` as a Scala string literal, with the two characters that would break out of one escaped, so a link
    * containing a quote or a backslash still pastes back as the same string.
    */
  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '\\'  => "\\\\"
      case '"'   => "\\\""
      case other => other.toString
    "\"" + escaped + "\""

package io.worxbend.tui.core

/** What an export of a [[Buffer]] looks like: the typeface and cell geometry of the picture, the palette named colours
  * resolve through, the colours a cell with no colour of its own is painted in, and a title.
  *
  * `cellWidth`/`cellHeight` are pixels per terminal cell in the SVG; the HTML and ANSI forms carry no geometry and
  * ignore them. `foreground`/`background` stand in for `Color.Reset` and for a cell that names no colour — the two
  * things a terminal would paint from its own theme. `title` becomes the SVG `<title>` and the HTML document title, and
  * is omitted from both when empty. Every field keeps the value the bare `ExportOptions()` would have, so a caller sets
  * only what differs.
  */
final case class ExportOptions(
    font: String = ExportOptions.DefaultFont,
    cellWidth: Int = 9,
    cellHeight: Int = 18,
    palette: ExportPalette = ExportPalette.Default,
    foreground: Color = Color.Rgb(0xd4, 0xd4, 0xd4),
    background: Color = Color.Rgb(0x1e, 0x1e, 0x1e),
    title: String = "",
)

object ExportOptions:

  /** A monospace stack that falls back gracefully: the browser picks the first installed face. */
  val DefaultFont: String = "'JetBrains Mono', 'Fira Code', Menlo, Consolas, 'DejaVu Sans Mono', monospace"

/** Pure renderings of a [[Buffer]] into files a person can open without a terminal: an SVG picture, an HTML page and an
  * ANSI text dump.
  *
  * All three keep colours, text attributes, wide glyphs and OSC 8 links. They are the substrate for documentation
  * screenshots that cannot drift from the code (render the real widget, write the SVG), for the visual diff a styled
  * golden fixture reports, and for "export what is on screen" commands. Everything here is a function of its arguments
  * and allocates only the string it returns, so it may be called from any thread — including, for a frame snapshot, off
  * the render thread.
  *
  * The ANSI form writes each colour in its own natural SGR spelling (named colours as `30`–`37`/`90`–`97`, indexed as
  * `38;5;n`, RGB as `38;2;r;g;b`) rather than resolving it through the palette, so the colours survive a round trip
  * through a parser; the palette decides only how the SVG and HTML paint them. The SGR grammar deliberately mirrors
  * `terminal.Sgr`, which `tui-core` cannot call — the module tiers only point downward — and `terminal`'s
  * `BufferExportSgrDriftSpec` keeps the two from drifting.
  */
object BufferExport:

  /** An SVG picture of `buffer`: one `<text>` per run of same-styled cells, a wide grapheme in a run of its own
    * spanning two columns, a background `<rect>` under every run with a background colour, and an `<a href>` around
    * every linked run. The picture is `area.width * cellWidth` by `area.height * cellHeight` pixels.
    */
  def svg(buffer: Buffer, options: ExportOptions = ExportOptions()): String =
    val width      = buffer.area.width * options.cellWidth
    val height     = buffer.area.height * options.cellHeight
    val fontSize   = math.max(1, math.round(options.cellHeight * 0.72).toInt)
    val baseline   = math.round(options.cellHeight * 0.75).toInt
    val out        = StringBuilder()
    out ++= s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height""""
    out ++= s""" font-family="${escapeXml(options.font)}" font-size="$fontSize">"""
    out += '\n'
    if options.title.nonEmpty then out ++= s"  <title>${escapeXml(options.title)}</title>\n"
    out ++= s"""  <rect width="100%" height="100%" fill="${hex(resolve(options, options.background))}"/>"""
    out += '\n'
    val runs       = segments(buffer)
    val background = runs.flatMap { run =>
      val painted = Painted(run.style, options)
      painted.background.map { color =>
        val x = (run.x - buffer.area.x) * options.cellWidth
        val y = (run.y - buffer.area.y) * options.cellHeight
        s"""  <rect x="$x" y="$y" width="${run.columns * options.cellWidth}" height="${options.cellHeight}" fill="${hex(
            color
          )}"/>"""
      }
    }
    if background.nonEmpty then
      out ++= "  <g>\n"
      background.foreach(rect => out ++= rect ++= "\n")
      out ++= "  </g>\n"
    out ++= "  <g>\n"
    runs.foreach { run =>
      val painted = Painted(run.style, options)
      if run.text.nonEmpty && !painted.hidden then
        val x          = (run.x - buffer.area.x) * options.cellWidth
        val y          = (run.y - buffer.area.y) * options.cellHeight + baseline
        val attributes = StringBuilder()
        attributes ++= s""" x="$x" y="$y" textLength="${run.columns * options.cellWidth}" lengthAdjust="spacingAndGlyphs""""
        attributes ++= s""" fill="${hex(painted.foreground)}" xml:space="preserve""""
        if painted.bold then attributes ++= """ font-weight="bold""""
        if painted.italic then attributes ++= """ font-style="italic""""
        if painted.dim then attributes ++= """ opacity="0.6""""
        painted.decoration.foreach(decoration => attributes ++= s""" text-decoration="$decoration"""")
        val text       = s"<text${attributes.result()}>${escapeXml(run.text)}</text>"
        out ++= "    "
        out ++= run.style.link.fold(text)(url => s"""<a href="${escapeXml(url)}">$text</a>""")
        out += '\n'
    }
    out ++= "  </g>\n</svg>\n"
    out.result()

  /** An HTML document showing `buffer` in a `<pre>`: one `<span>` per run of same-styled cells carrying inline CSS, an
    * `<a href>` around every linked run, rows separated by newlines. Wide graphemes need no special treatment here —
    * the browser's monospace font gives them two columns on its own.
    */
  def html(buffer: Buffer, options: ExportOptions = ExportOptions()): String =
    val out = StringBuilder()
    out ++= "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
    if options.title.nonEmpty then out ++= s"<title>${escapeXml(options.title)}</title>\n"
    out ++= "</head>\n<body>\n"
    out ++= s"""<pre style="background:${hex(resolve(options, options.background))};"""
    out ++= s"""color:${hex(resolve(options, options.foreground))};font-family:${escapeXml(options.font)};"""
    out ++= s"""line-height:1.2;padding:1em;margin:0">"""
    var y   = buffer.area.y
    segments(buffer).foreach { run =>
      if run.y != y then
        out += '\n'
        y = run.y
      val painted = Painted(run.style, options)
      // blanks at the end of a run are padding the browser lays out on its own, unless a background has to cover them
      val text    = if painted.background.isDefined then run.text.padTo(run.columns, ' ') else run.text
      if text.nonEmpty then
        val css  = StringBuilder()
        css ++= s"color:${hex(painted.foreground)}"
        painted.background.foreach(color => css ++= s";background:${hex(color)}")
        if painted.bold then css ++= ";font-weight:bold"
        if painted.italic then css ++= ";font-style:italic"
        if painted.dim then css ++= ";opacity:0.6"
        if painted.hidden then css ++= ";visibility:hidden"
        painted.decoration.foreach(decoration => css ++= s";text-decoration:$decoration")
        val span = s"""<span style="${css.result()}">${escapeXml(text)}</span>"""
        out ++= run.style.link.fold(span)(url => s"""<a href="${escapeXml(url)}" style="color:inherit">$span</a>""")
    }
    out ++= "</pre>\n</body>\n</html>\n"
    out.result()

  /** `buffer` as styled ANSI text, one row per line with every column written — trailing blanks included, so the
    * frame's width survives a round trip through a parser. Each run of same-styled cells is preceded by one absolute
    * SGR sequence (a reset followed by the whole style), every row ends with a bare reset, and a linked run is wrapped
    * in OSC 8. There is no cursor movement in it at all: this is text to print or to save, not a frame to flush.
    */
  def ansi(buffer: Buffer, options: ExportOptions = ExportOptions()): String =
    val _   = options // the palette decides how a picture paints a colour; ANSI keeps the colour's own spelling
    val out = StringBuilder()
    var y   = buffer.area.y
    segments(buffer).foreach { run =>
      if run.y != y then
        out ++= AnsiReset
        out += '\n'
        y = run.y
      out ++= sgr(run.style)
      run.style.link.foreach(url => out ++= s"$Esc]8;;${stripControls(url)}$Esc\\")
      out ++= run.text.padTo(run.columns, ' ')
      if run.style.link.isDefined then out ++= s"$Esc]8;;$Esc\\"
    }
    out ++= AnsiReset
    out.result()

  private val Esc       = "\u001b"
  private val AnsiReset = s"$Esc[0m"

  /** The absolute SGR for `style`: a reset, then every colour and attribute it sets. Mirrors `terminal.Sgr.sgr` at
    * true-colour depth; see the object notes for why it is written a second time here.
    */
  private def sgr(style: Style): String =
    val codes = StringBuilder(s"$Esc[0")
    style.fg.foreach(color => codes ++= ";" ++= colorCode(color, 30))
    style.bg.foreach(color => codes ++= ";" ++= colorCode(color, 40))
    ModifierCodes.foreach((flag, code) => if style.modifiers.hasAny(flag) then codes ++= ";" ++= code)
    underlineStyleCode(style.underlineStyle).foreach(code => codes ++= ";" ++= code)
    style.underlineColor.foreach(color => codes ++= ";" ++= underlineColorCode(color))
    codes.append('m').result()

  private val ModifierCodes: List[(Modifiers, String)] =
    List(
      Modifiers.Bold       -> "1",
      Modifiers.Dim        -> "2",
      Modifiers.Italic     -> "3",
      Modifiers.Underline  -> "4",
      Modifiers.Blink      -> "5",
      Modifiers.RapidBlink -> "6",
      Modifiers.Reverse    -> "7",
      Modifiers.Hidden     -> "8",
      Modifiers.CrossedOut -> "9",
    )

  private def underlineStyleCode(style: UnderlineStyle): Option[String] =
    style match
      case UnderlineStyle.None | UnderlineStyle.Straight => None
      case UnderlineStyle.Double                         => Some("4:2")
      case UnderlineStyle.Curly                          => Some("4:3")
      case UnderlineStyle.Dotted                         => Some("4:4")
      case UnderlineStyle.Dashed                         => Some("4:5")

  private def underlineColorCode(color: Color): String =
    color match
      case Color.Indexed(index) => s"58:5:${Color.clampIndex(index)}"
      case other                =>
        val (r, g, b) = Color.approximateRgb(other)
        s"58:2::$r:$g:$b"

  /** One SGR colour code offset from `base` (30 foreground, 40 background) — the ECMA-48 layout `terminal.Sgr` uses. */
  private def colorCode(color: Color, base: Int): String =
    color match
      case Color.Reset          => s"${base + 9}"
      case Color.Black          => s"${base + 0}"
      case Color.Red            => s"${base + 1}"
      case Color.Green          => s"${base + 2}"
      case Color.Yellow         => s"${base + 3}"
      case Color.Blue           => s"${base + 4}"
      case Color.Magenta        => s"${base + 5}"
      case Color.Cyan           => s"${base + 6}"
      case Color.White          => s"${base + 7}"
      case Color.BrightBlack    => s"${base + 60}"
      case Color.BrightRed      => s"${base + 61}"
      case Color.BrightGreen    => s"${base + 62}"
      case Color.BrightYellow   => s"${base + 63}"
      case Color.BrightBlue     => s"${base + 64}"
      case Color.BrightMagenta  => s"${base + 65}"
      case Color.BrightCyan     => s"${base + 66}"
      case Color.BrightWhite    => s"${base + 67}"
      case Color.Indexed(index) => s"${base + 8};5;${Color.clampIndex(index)}"
      case rgb: Color.Rgb       =>
        val (r, g, b) = Color.approximateRgb(rgb)
        s"${base + 8};2;$r;$g;$b"

  /** An OSC string ends at BEL or ST, so a control character inside a URL would end the link early and print the rest
    * of it as text; dropping them is what `terminal.AnsiSequences.linkOpen` does too.
    */
  private def stripControls(url: String): String = url.filterNot(c => c < 0x20 || c == 0x7f || (c >= 0x80 && c < 0xa0))

  /** One maximal horizontal run of cells sharing a [[Style]], with the continuation column of a wide grapheme folded
    * into the run's `columns` and a wide grapheme always in a run of its own. `text` has trailing blanks stripped;
    * `columns` still counts them, so a background run keeps its full extent.
    */
  private final case class Segment(x: Int, y: Int, columns: Int, text: String, style: Style)

  private def segments(buffer: Buffer): Seq[Segment] =
    val runs = Seq.newBuilder[Segment]
    var y    = buffer.area.y
    while y < buffer.area.bottom do
      var x = buffer.area.x
      while x < buffer.area.right do
        val start = x
        val style = buffer.get(x, y).style
        val text  = StringBuilder()
        var open  = true
        while open && x < buffer.area.right do
          val cell   = buffer.get(x, y)
          val isWide = buffer.isContinuation(x + 1, y)
          if x == start then
            // a wide grapheme is a run of its own: its one <text> spans both columns and nothing joins it
            text ++= cell.symbol
            x += (if isWide then 2 else 1)
            open = !isWide
          else if cell.style != style || isWide then open = false
          else
            text ++= cell.symbol
            x += 1
        runs += Segment(start, y, x - start, trimTrailingBlanks(text.result()), style)
      y += 1
    runs.result()

  private def trimTrailingBlanks(text: String): String =
    var end = text.length
    while end > 0 && text.charAt(end - 1) == ' ' do end -= 1
    text.take(end)

  /** A style resolved against the export options: concrete colours with reverse video applied, and the attribute flags
    * the SVG and HTML forms spell out.
    */
  private final case class Painted(
      foreground: (Int, Int, Int),
      background: Option[(Int, Int, Int)],
      bold: Boolean,
      italic: Boolean,
      dim: Boolean,
      hidden: Boolean,
      decoration: Option[String],
  )

  private object Painted:
    def apply(style: Style, options: ExportOptions): Painted =
      val fg         =
        style.fg.filter(_ != Color.Reset).map(options.palette.rgb).getOrElse(resolve(options, options.foreground))
      val bg         = style.bg.filter(_ != Color.Reset).map(options.palette.rgb)
      val reversed   = style.modifiers.hasAny(Modifiers.Reverse)
      val foreground = if reversed then bg.getOrElse(resolve(options, options.background)) else fg
      val background = if reversed then Some(fg) else bg
      val lines      = Seq(
        Option.when(style.modifiers.hasAny(Modifiers.Underline) || style.underlineStyle != UnderlineStyle.None)(
          "underline"
        ),
        Option.when(style.modifiers.hasAny(Modifiers.CrossedOut))("line-through"),
      ).flatten
      Painted(
        foreground,
        background,
        bold = style.modifiers.hasAny(Modifiers.Bold),
        italic = style.modifiers.hasAny(Modifiers.Italic),
        dim = style.modifiers.hasAny(Modifiers.Dim),
        hidden = style.modifiers.hasAny(Modifiers.Hidden),
        decoration = Option.when(lines.nonEmpty)(lines.mkString(" ")),
      )

  /** `Color.Reset` stands for the export's own default, which the palette never sees. */
  private def resolve(options: ExportOptions, color: Color): (Int, Int, Int) =
    if color == Color.Reset then options.palette.rgb(options.foreground) else options.palette.rgb(color)

  private def hex(rgb: (Int, Int, Int)): String =
    val (r, g, b) = rgb
    f"#$r%02x$g%02x$b%02x"

  private def escapeXml(text: String): String =
    val out = StringBuilder()
    text.foreach {
      case '&'   => out ++= "&amp;"
      case '<'   => out ++= "&lt;"
      case '>'   => out ++= "&gt;"
      case '"'   => out ++= "&quot;"
      case '\''  => out ++= "&#39;"
      case other => out += other
    }
    out.result()

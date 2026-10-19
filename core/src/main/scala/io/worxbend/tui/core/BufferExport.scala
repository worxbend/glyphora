package io.worxbend.tui.core

/** What an export of a [[Buffer]] looks like: the typeface and cell geometry of the picture, the palette named colours
  * resolve through, the colours a cell with no colour of its own is painted in, and a title.
  *
  * `cellWidth`/`cellHeight` are pixels per terminal cell in the SVG; the HTML and ANSI forms carry no geometry and
  * ignore them. `foreground`/`background` stand in for `Color.Reset` and for a cell that names no colour — the two
  * things a terminal would paint from its own theme. `title` becomes the SVG `<title>` and the HTML document title, and
  * is omitted from both when empty. `ansiColors` is the colour policy of the two ANSI forms only (the default keeps
  * every colour in its own spelling; `terminal.ColorDepth.ansiColors` gives the policy of a terminal depth). Every
  * field keeps the value the bare `ExportOptions()` would have, so a caller sets only what differs.
  */
final case class ExportOptions(
    font: String = ExportOptions.DefaultFont,
    cellWidth: Int = 9,
    cellHeight: Int = 18,
    palette: ExportPalette = ExportPalette.Default,
    foreground: Color = Color.Rgb(0xd4, 0xd4, 0xd4),
    background: Color = Color.Rgb(0x1e, 0x1e, 0x1e),
    title: String = "",
    ansiColors: AnsiColors = AnsiColors.TrueColor,
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
  * The ANSI forms write each colour in its own natural SGR spelling (named colours as `30`–`37`/`90`–`97`, indexed as
  * `38;5;n`, RGB as `38;2;r;g;b`) rather than resolving it through the palette, so the colours survive a round trip
  * through a parser; the palette decides only how the SVG and HTML paint them. Both go through `SgrCodec` under an
  * [[AnsiColors]] policy — the very codec and policy the terminal's frame encoder writes with, so an export at a depth
  * holds the bytes the terminal would have been sent.
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
      val text    = if painted.background.isDefined then padded(run) else run.text
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
    val out = StringBuilder()
    var y   = buffer.area.y
    segments(buffer).foreach { run =>
      if run.y != y then
        out ++= AnsiReset
        out += '\n'
        y = run.y
      out ++= SgrCodec.absolute(run.style, options.ansiColors)
      run.style.link.foreach(url => out ++= s"$Esc]8;;${stripControls(url)}$Esc\\")
      out ++= padded(run)
      if run.style.link.isDefined then out ++= s"$Esc]8;;$Esc\\"
    }
    out ++= AnsiReset
    out.result()

  /** `buffer` as styled text to be *printed*, rows separated by newlines — what print mode and a no-TTY run write, and
    * what `terminal.FrameEncoder.encodeLines` answers.
    *
    * Unlike [[ansi]] it is shaped for a scrollback rather than for a round trip. Style is written as a delta from the
    * terminal's default rather than as the absolute, reset-first form — so a style that renders identically to the
    * default under `colors` writes *nothing*, and with `emitsColor` off a frame whose styles are colour-only comes out
    * as plain text with no escape sequence in it (what `NO_COLOR` asks for). Rows end with a style reset exactly when
    * they emitted a style. Trailing cells that would not be visible — blank, no background under `colors`, no
    * underline, reverse or strikethrough — are dropped, so a scrollback line's background does not run to the window's
    * edge. Continuation columns of a wide grapheme are skipped, and a symbol carrying a control code is written as a
    * blank. Pure; callable from any thread.
    */
  def ansiLines(buffer: Buffer, colors: AnsiColors = AnsiColors.TrueColor): String =
    val rows = Vector.newBuilder[String]
    var y    = buffer.area.y
    while y < buffer.area.bottom do
      rows += ansiLine(buffer, y, colors)
      y += 1
    rows.result().mkString("\n")

  /** One row of [[ansiLines]]: delta-styled from the default, reset at the end only when a style was emitted. */
  private def ansiLine(buffer: Buffer, y: Int, colors: AnsiColors): String =
    val end = lastVisibleColumn(buffer, y, colors)
    if end < buffer.area.x then ""
    else
      val body                        = StringBuilder()
      var currentStyle: Style         = Style.Default
      var currentLink: Option[String] = None
      var styled                      = false
      var x                           = buffer.area.x
      while x <= end do
        if !buffer.isContinuation(x, y) then
          val cell = buffer.get(x, y)
          if !((cell.style eq currentStyle) || cell.style == currentStyle) then
            val delta = SgrCodec.delta(currentStyle, cell.style, colors)
            if delta.nonEmpty then
              body ++= delta
              styled = true
            currentStyle = cell.style
          currentLink = carryLink(body, currentLink, cell.style.link)
          body ++= printable(cell.symbol)
        x += 1
      carryLink(body, currentLink, None)
      if styled then body ++= AnsiReset
      body.result()

  /** The rightmost column of row `y` a reader could see under `colors`, or one less than the row's first column when
    * nothing in the row is visible.
    */
  private def lastVisibleColumn(buffer: Buffer, y: Int, colors: AnsiColors): Int =
    var last = buffer.area.x - 1
    var x    = buffer.area.x
    while x < buffer.area.right do
      val cell = buffer.get(x, y)
      if !buffer.isContinuation(x, y) && (!cell.isBlank || blankIsVisible(cell.style, colors)) then last = x
      x += 1
    last

  private def blankIsVisible(style: Style, colors: AnsiColors): Boolean =
    (colors.emitsColor && style.bg.exists(_ != Color.Reset)) ||
      style.modifiers.hasAny(Modifiers.Reverse | Modifiers.Underline | Modifiers.CrossedOut) ||
      style.underlineStyle != UnderlineStyle.None

  /** Emits the OSC-8 transitions that carry the hyperlink state from `open` to `next`, and answers `next`. */
  private def carryLink(body: StringBuilder, open: Option[String], next: Option[String]): Option[String] =
    if next != open then
      if open.nonEmpty then body ++= s"$Esc]8;;$Esc\\"
      next.foreach(url => body ++= s"$Esc]8;;${stripLinkControls(url)}$Esc\\")
    next

  /** `symbol`, or a single blank if it is empty or carries a C0/C1 control code: a control byte written verbatim would
    * reach the terminal as if the application had emitted it.
    */
  private def printable(symbol: String): String =
    if symbol.isEmpty then " "
    else if CharWidth.isPrintableAscii(symbol) then symbol
    else if symbol.exists(isControl) then " "
    else symbol

  private def isControl(c: Char): Boolean = c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)

  /** C0, DEL and C1 removed, tab kept — the rule `terminal.AnsiSequences.linkOpen` applies to a printed link. */
  private def stripLinkControls(url: String): String = url.filter(c => c == '\t' || !isControl(c))

  private val Esc       = "\u001b"
  private val AnsiReset = s"$Esc[0m"

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

  /** The run's text with its trailing blanks restored — by display width, not character count, so a two-column grapheme
    * in a one-cell run gets no blank after it.
    */
  private def padded(run: Segment): String =
    run.text + " " * math.max(0, run.columns - CharWidth.of(run.text))

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

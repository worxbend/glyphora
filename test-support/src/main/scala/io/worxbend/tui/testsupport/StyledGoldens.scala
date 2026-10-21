package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{
  Buffer,
  BufferExport,
  CharWidth,
  Color,
  ExportOptions,
  Modifiers,
  Rect,
  Style,
  UnderlineStyle,
}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** The machinery behind `GoldenFrames.assertMatchesStyled`: reads a styled fixture back into a
  * [[io.worxbend.tui.core.Buffer Buffer]] and writes the visual report a mismatch produces.
  *
  * A styled fixture is the frame's `BufferExport.ansi` text — every row, every column, one absolute SGR per run of
  * same-styled cells, OSC 8 around a link. [[parseAnsi]] accepts exactly that grammar and nothing more: it is the
  * inverse of one exporter, not a terminal emulator, so a fixture edited by hand into some other spelling of the same
  * colours is a fixture that no longer round-trips and should be re-recorded instead. One field is lossy by the grammar
  * itself: SGR 58 has no code for a *named* underline colour, so `Color.Green` there is written and read back as its
  * RGB value. The fixture comparison is on the export text and is unaffected; only the report's parsed picture sees it.
  *
  * Stateless; every method is a pure function of its arguments or writes only the files it names, so it is safe to call
  * from several tests at once.
  */
object StyledGoldens:

  private val Esc = '\u001b'

  /** The frame `text` — a `BufferExport.ansi` export, with or without a trailing newline — as a buffer whose area is
    * `(0, 0)` to the widest row by the number of rows. Styles come back as the [[io.worxbend.tui.core.Style Style]]
    * values that produced them, so a buffer exported and parsed again is equal to the original, continuation columns
    * included.
    */
  def parseAnsi(text: String): Buffer =
    val rows   = TrailingNewlines.replaceFirstIn(text, "").split("\n", -1).toSeq.map(parseRow)
    val width  = rows.map(_.map(chunk => CharWidth.of(chunk.text)).sum).maxOption.getOrElse(0)
    val buffer = Buffer(Rect(0, 0, width, rows.length))
    rows.zipWithIndex.foreach { (chunks, y) =>
      var x = 0
      chunks.foreach { chunk =>
        buffer.setString(x, y, chunk.text, chunk.style)
        x += CharWidth.of(chunk.text)
      }
    }
    buffer

  private val TrailingNewlines = "\\R+$".r

  /** A run of text drawn in one style. */
  private final case class Chunk(text: String, style: Style)

  /** One row of the export: SGR sequences update the current style, OSC 8 opens and closes the link, and everything
    * else is text in the style in force. Chunks are closed on every state change, so a glyph is never attributed to the
    * style of its neighbour.
    */
  private def parseRow(row: String): Seq[Chunk] =
    val chunks               = Seq.newBuilder[Chunk]
    val text                 = StringBuilder()
    var style                = Style.Default
    var link: Option[String] = None
    def flush(): Unit        =
      if text.nonEmpty then
        chunks += Chunk(text.result(), link.fold(style)(style.withLink))
        text.clear()
    var index                = 0
    while index < row.length do
      val c = row.charAt(index)
      if c == Esc && index + 1 < row.length && row.charAt(index + 1) == '[' then
        val end = row.indexOf('m', index)
        if end < 0 then index = row.length
        else
          flush()
          style = applySgr(style, row.slice(index + 2, end))
          index = end + 1
      else if c == Esc && index + 1 < row.length && row.charAt(index + 1) == ']' then
        val end = row.indexOf(s"$Esc\\", index)
        if end < 0 then index = row.length
        else
          flush()
          val body = row.slice(index + 2, end)
          link = Option(body.stripPrefix("8;;")).filter(_.nonEmpty)
          index = end + 2
      else
        text += c
        index += 1
    flush()
    chunks.result()

  /** `style` after the SGR parameters `params` (the text between `ESC[` and `m`) are applied in order. Only the codes
    * `BufferExport.ansi` writes are understood; anything else is ignored rather than guessed at.
    */
  private def applySgr(style: Style, params: String): Style =
    val parts                                                = params.split(";", -1).toList
    @annotation.tailrec
    def loop(remaining: List[String], current: Style): Style =
      remaining match
        case Nil                                         => current
        case "0" :: rest                                 => loop(rest, Style.Default)
        case ("38" | "48") :: "5" :: index :: rest       =>
          loop(rest, withColor(current, remaining.head, Color.Indexed(index.toInt)))
        case ("38" | "48") :: "2" :: r :: g :: b :: rest =>
          loop(rest, withColor(current, remaining.head, Color.Rgb(r.toInt, g.toInt, b.toInt)))
        case code :: rest                                => loop(rest, applySimple(current, code))
    loop(parts.filter(_.nonEmpty), style)

  private def withColor(style: Style, selector: String, color: Color): Style =
    if selector == "38" then style.withFg(color) else style.withBg(color)

  /** One parameter that stands alone: a text attribute, a named colour, a reset, or a colon-parameterised underline. */
  private def applySimple(style: Style, code: String): Style =
    code match
      case "39"                                     => style.withoutFg
      case "49"                                     => style.withoutBg
      case "4:2"                                    => style.withUnderlineStyle(UnderlineStyle.Double)
      case "4:3"                                    => style.withUnderlineStyle(UnderlineStyle.Curly)
      case "4:4"                                    => style.withUnderlineStyle(UnderlineStyle.Dotted)
      case "4:5"                                    => style.withUnderlineStyle(UnderlineStyle.Dashed)
      case underline if underline.startsWith("58:") => style.withUnderlineColor(underlineColor(underline))
      case _                                        =>
        code.toIntOption match
          case Some(n) if n >= 1 && n <= 9     => style.withModifiers(style.modifiers | ModifierFlags(n - 1))
          case Some(n) if n >= 30 && n <= 37   => style.withFg(Named(n - 30))
          case Some(n) if n >= 40 && n <= 47   => style.withBg(Named(n - 40))
          case Some(n) if n >= 90 && n <= 97   => style.withFg(Named(8 + n - 90))
          case Some(n) if n >= 100 && n <= 107 => style.withBg(Named(8 + n - 100))
          case _                               => style

  /** `58:5:n` and `58:2::r:g:b`, the two underline-colour forms the exporter writes. */
  private def underlineColor(code: String): Color =
    code.split(":", -1).toList match
      case "58" :: "5" :: index :: Nil            => Color.Indexed(index.toInt)
      case "58" :: "2" :: _ :: r :: g :: b :: Nil => Color.Rgb(r.toInt, g.toInt, b.toInt)
      case _                                      => Color.Reset

  /** SGR 1–9 in order, so code `n` is `ModifierFlags(n - 1)`. */
  private val ModifierFlags: Vector[Modifiers] = Vector(
    Modifiers.Bold,
    Modifiers.Dim,
    Modifiers.Italic,
    Modifiers.Underline,
    Modifiers.Blink,
    Modifiers.RapidBlink,
    Modifiers.Reverse,
    Modifiers.Hidden,
    Modifiers.CrossedOut,
  )

  /** The sixteen named colours in SGR order: `30`–`37` are the first eight, `90`–`97` the bright eight. */
  private val Named: Vector[Color] = Vector(
    Color.Black,
    Color.Red,
    Color.Green,
    Color.Yellow,
    Color.Blue,
    Color.Magenta,
    Color.Cyan,
    Color.White,
    Color.BrightBlack,
    Color.BrightRed,
    Color.BrightGreen,
    Color.BrightYellow,
    Color.BrightBlue,
    Color.BrightMagenta,
    Color.BrightCyan,
    Color.BrightWhite,
  )

  /** The stroke every differing cell is outlined with in the report. */
  private[testsupport] val OutlineColor: String = "#ff3b30"

  /** The geometry both pictures and the outlines share. */
  private val Options: ExportOptions = ExportOptions()

  /** Writes `<name>.expected.svg`, `<name>.actual.svg` and `<name>.diff.html` into `directory` and returns the three
    * paths in that order. The HTML page shows the two pictures side by side with every differing cell outlined; when
    * the two frames cover different areas there is no cell-by-cell answer, and the page says so instead.
    */
  def writeReport(directory: Path, name: String, expected: Buffer, actual: Buffer): Seq[Path] =
    Files.createDirectories(directory)
    val expectedSvg = BufferExport.svg(expected, Options.copy(title = s"$name expected"))
    val actualSvg   = BufferExport.svg(actual, Options.copy(title = s"$name actual"))
    val expectedOut = directory.resolve(s"$name.expected.svg")
    val actualOut   = directory.resolve(s"$name.actual.svg")
    val reportOut   = directory.resolve(s"$name.diff.html")
    Files.write(expectedOut, expectedSvg.getBytes(StandardCharsets.UTF_8))
    Files.write(actualOut, actualSvg.getBytes(StandardCharsets.UTF_8))
    Files.write(reportOut, report(name, expected, actual, expectedSvg, actualSvg).getBytes(StandardCharsets.UTF_8))
    Seq(expectedOut, actualOut, reportOut)

  private def report(name: String, expected: Buffer, actual: Buffer, expectedSvg: String, actualSvg: String): String =
    val sameArea    = expected.area == actual.area
    val differences = if sameArea then BufferAssertions.cellDifferences(actual, expected) else Seq.empty
    val summary     =
      if !sameArea then s"the frames cover different areas: expected ${expected.area}, actual ${actual.area}"
      else s"${differences.size} of ${expected.area.area} cells differ"
    val outlines    = differences.map { (position, _, _) =>
      val x = (position.x - expected.area.x) * Options.cellWidth
      val y = (position.y - expected.area.y) * Options.cellHeight
      s"""<rect x="$x" y="$y" width="${Options.cellWidth}" height="${Options.cellHeight}" fill="none" stroke="$OutlineColor" stroke-width="2"/>"""
    }
    def panel(label: String, picture: String, buffer: Buffer): String =
      val width  = buffer.area.width * Options.cellWidth
      val height = buffer.area.height * Options.cellHeight
      s"""<figure style="margin:0">
         |<figcaption style="font-family:sans-serif;margin-bottom:.5em">$label</figcaption>
         |<div style="position:relative;display:inline-block">
         |$picture<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" style="position:absolute;left:0;top:0;pointer-events:none">
         |${outlines.mkString("\n")}
         |</svg>
         |</div>
         |</figure>""".stripMargin
    s"""<!DOCTYPE html>
       |<html>
       |<head>
       |<meta charset="utf-8">
       |<title>$name: styled golden diff</title>
       |</head>
       |<body style="background:#111;color:#eee;padding:1em">
       |<h1 style="font-family:sans-serif;font-size:1.2em">golden/$name.ansi — $summary</h1>
       |<div style="display:flex;gap:2em;flex-wrap:wrap">
       |${panel("expected (fixture)", expectedSvg, expected)}
       |${panel("actual (this run)", actualSvg, actual)}
       |</div>
       |</body>
       |</html>
       |""".stripMargin

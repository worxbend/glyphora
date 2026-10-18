package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Document, Element}

/** Pins the structure of [[BufferExport]]'s three renderings of one small frame: a wide glyph, bold red text and an OSC
  * 8 link. Assertions are on nodes and substrings, never on the whole file — the exact markup is free to change, the
  * cells it carries are not.
  */
final class BufferExportSpec extends AnyFunSuite:

  /** 4x2: row 0 is a wide ideograph followed by bold red "ab"; row 1 is a linked "go" and two blanks. */
  private def frame(): Buffer =
    val buffer = Buffer(Rect(0, 0, 4, 2))
    buffer.setString(0, 0, "日", Style.Default)
    buffer.setString(2, 0, "ab", Style.fg(Color.Red).bold)
    buffer.setString(0, 1, "go", Style.Default.withLink("https://example.com"))
    buffer

  private def parse(xml: String): Document =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))

  private def elements(document: Document, tag: String): Seq[Element] =
    val nodes = document.getElementsByTagNameNS("*", tag)
    (0 until nodes.getLength).map(nodes.item(_)).collect { case element: Element => element }

  test("svg parses as XML and places one <text> per run, the wide glyph spanning two columns"):
    val options  = ExportOptions(cellWidth = 10, cellHeight = 20)
    val document = parse(BufferExport.svg(frame(), options))
    val texts    = elements(document, "text")
    val wide     = texts.find(_.getTextContent == "日").getOrElse(fail("no <text> for the wide glyph"))
    assert(wide.getAttribute("textLength") == "20", "a two-column grapheme spans two cell widths")
    val bold     = texts.find(_.getTextContent == "ab").getOrElse(fail("no <text> for the bold run"))
    assert(bold.getAttribute("x") == "20", "the bold run starts in column 2")
    assert(bold.getAttribute("font-weight") == "bold")
    assert(bold.getAttribute("fill").nonEmpty && bold.getAttribute("fill") != wide.getAttribute("fill"))
    val linked   = texts.find(_.getTextContent == "go").getOrElse(fail("no <text> for the linked run"))
    assert(linked.getAttribute("y") != wide.getAttribute("y"), "row 1 sits below row 0")
    val anchors  = elements(document, "a")
    assert(anchors.exists(_.getAttribute("href") == "https://example.com"), "the link becomes an <a href>")
    assert(document.getDocumentElement.getAttribute("width") == "40")
    assert(document.getDocumentElement.getAttribute("height") == "40")

  test("svg carries the title and uses the palette for indexed colours"):
    val buffer    = Buffer(Rect(0, 0, 1, 1))
    buffer.setString(0, 0, "x", Style.fg(Color.Indexed(196)))
    val document  = parse(BufferExport.svg(buffer, ExportOptions(title = "demo")))
    assert(elements(document, "title").map(_.getTextContent) == Seq("demo"))
    val (r, g, b) = Color.approximateRgb(Color.Indexed(196))
    assert(elements(document, "text").head.getAttribute("fill") == f"#$r%02x$g%02x$b%02x")

  private val Esc = "\u001b"

  test("ansi writes each colour in its own SGR spelling, the link as OSC 8, and resets at the end of every row"):
    val text = BufferExport.ansi(frame())
    val rows = text.split("\n", -1).toSeq
    assert(rows.length == 2)
    assert(rows.head.startsWith(s"$Esc[0m日"), "a default-styled cell opens the frame with a bare reset")
    assert(rows.head.contains(s"$Esc[0;31;1mab"), "bold red is one absolute SGR in front of the run")
    assert(rows.forall(_.endsWith(s"$Esc[0m")), "every row ends reset, so a style never leaks across a line")
    assert(rows(1).contains(s"$Esc]8;;https://example.com$Esc\\go$Esc]8;;$Esc\\"), "the link is opened and closed")
    assert(rows(1).endsWith(s"$Esc[0m  $Esc[0m"), "trailing blanks are kept so the frame's width survives a round trip")

  test("ansi keeps indexed and RGB colours as themselves rather than through the palette"):
    val buffer = Buffer(Rect(0, 0, 2, 1))
    buffer.setString(0, 0, "a", Style.fg(Color.Indexed(196)))
    buffer.setString(1, 0, "b", Style.bg(Color.Rgb(1, 2, 3)).italic)
    val text   = BufferExport.ansi(buffer)
    assert(text.contains(s"$Esc[0;38;5;196ma"))
    assert(text.contains(s"$Esc[0;48;2;1;2;3;3mb"))

  test("html wraps the frame in a <pre>, styles runs with spans and links them with <a>"):
    val html      = BufferExport.html(frame(), ExportOptions(title = "page"))
    assert(html.contains("<title>page</title>"))
    assert(html.contains("<pre"))
    assert(html.contains("font-weight:bold"))
    assert(html.contains("""<a href="https://example.com""""))
    assert(html.contains("日"))
    val (r, g, b) = Color.approximateRgb(Color.Red)
    assert(html.contains(f"color:#$r%02x$g%02x$b%02x"))
    // the <pre> block alone is well-formed XML: every span and anchor is closed, every glyph escaped
    val pre       = html.slice(html.indexOf("<pre"), html.indexOf("</pre>") + "</pre>".length)
    assert(parse(pre).getDocumentElement.getTagName == "pre")

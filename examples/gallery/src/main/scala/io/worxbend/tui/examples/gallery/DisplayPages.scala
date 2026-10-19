package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** A configuration value for the `inspect` page — any case class gets a tree view with no code of its own. */
final case class ServerConfig(host: String, port: Int, tls: Boolean, tags: List[String])

/** Text, rich content, notices and the animated text effects. */
object DisplayPages:

  private val Family = "Display"

  private def page(factory: String, summary: String, props: Prop*)(view: View): GalleryPage =
    GalleryPage(factory, Family, summary, view, props)

  private val SampleMarkdown =
    """# Release notes
      |
      |- **bold**, *italic* and `code`
      |- [x] a finished task
      |
      |> quoted text""".stripMargin

  /** A small RGBA gradient built in memory: `ImageLoader.readPng` reads a real file the same way, but the gallery's
    * pictures must not depend on a file that may be missing.
    */
  private def gradient(width: Int, height: Int): RgbaImage =
    val bytes = Array.tabulate[Byte](width * height * 4) { i =>
      val pixel = i / 4
      val x     = pixel % width
      val y     = pixel / width
      (i % 4 match
        case 0 => x * 255 / math.max(1, width - 1)
        case 1 => y * 255 / math.max(1, height - 1)
        case 2 => 160
        case _ => 255
      ).toByte
    }
    RgbaImage
      .fromRgba(width, height, IArray.unsafeFromArray(bytes))
      .fold(error => throw IllegalStateException(error), identity)

  def all(fixtures: GalleryFixtures): Vector[GalleryPage] =
    val logLines = LogState()
    Seq("boot: reading config", "boot: 3 plugins loaded", "http: listening on :8080", "http: GET / 200").foreach(
      logLines.append
    )
    val ring     = LogRing(16)
    Seq(
      (LogLevel.Info, "app", "started in 412 ms"),
      (LogLevel.Warning, "db", "slow query: 1.2 s"),
      (LogLevel.Error, "http", "upstream timed out"),
    ).zipWithIndex.foreach { case ((level, logger, message), i) =>
      ring.append(LogRecord(fixtures.now.plusSeconds(i.toLong), level, logger, message))
    }
    val stream   = MarkdownStream("## Streaming answer\n\nTokens arrive *one at a time* and the view ")
    stream.append("re-renders only what changed.")
    val patch    = Patch.of("val x = 1\nval y = 2\nprintln(x)\n", "val x = 1\nval y = 3\nprintln(x + y)\n")
    val diff     = DiffViewState()
    val bytes    = "glyphora hex viewer \u0000\u0001\u0002 bytes".getBytes("UTF-8").toIndexedSeq
    val hex      = HexViewerState()
    val pixels   = Vector.tabulate[Color.Rgb](6, 16)((y, x) => Color.Rgb(x * 16, y * 40, 200))
    val picture  = gradient(32, 16)
    val config   = ServerConfig("localhost", 8443, tls = true, List("edge", "eu-west"))
    val versions = Seq("v0.20.0", "beta", "new")
    val version  = Signal(0)
    Vector(
      page("text", "a block of plain text, wrapped to its width")(
        text("glyphora draws every widget into a buffer,\nand the terminal only ever sees the cells that changed.")
      ),
      page("line", "one line of differently styled spans")(
        line("Status: ", "OK".styled(_.withFg(Color.Green).bold), "  ·  3 warnings")
      ),
      page("bigText", "large block-letter text")(bigText("TUI")),
      page("link", "a clickable OSC 8 hyperlink")(link("glyphora on GitHub", "https://github.com/worxbend/glyphora")),
      page("markdown", "CommonMark with GFM lists, tables and task items")(markdown(SampleMarkdown)),
      page("markdownStream", "Markdown that is still arriving")(markdownStream(stream)),
      page("notice", "a one-line message with a severity icon")(
        column(
          notice("saved", NoticeLevel.Success).length(1),
          notice("3 files changed").length(1),
          notice("disk almost full", NoticeLevel.Warning).length(1),
          notice("build failed", NoticeLevel.Error).length(1),
        )
      ),
      page("badge", "a short label or severity tag", Prop.Choice("label", versions, version))(
        row(
          badge(versions(version.get)).length(10),
          badge(NoticeLevel.Warning).length(8),
          badge(NoticeLevel.Error).length(8),
        )
      ),
      page("placeholder", "a labelled stand-in for a view not built yet")(placeholder("chart goes here")),
      page("tooltip", "a small bordered hint")(tooltip("Ctrl+S saves the file")),
      page("inspect", "any value drawn as a tree")(inspect(config)),
      page("qrCode", "a scannable QR code")(qrCode("https://github.com/worxbend/glyphora")),
      page("image", "true-colour pixels in half-block cells")(image(Image(pixels))),
      page("pixelImage", "an RGBA picture, through the terminal's graphics protocol when it has one")(
        pixelImage(picture)
      ),
      page("animatedText", "text with a looping colour sweep")(animatedText("glyphora 0.20.0")),
      page("animatedTextAt", "the same sweep at a caller-owned time")(
        animatedTextAt("glyphora 0.20.0", scala.concurrent.duration.FiniteDuration(600, "ms"))
      ),
      page("marquee", "text scrolling through a fixed width")(
        marquee("a long headline scrolls through a narrow box — ").length(1)
      ),
      page("marqueeAt", "the same scroll at a caller-owned time")(
        marqueeAt("a long headline scrolls through a narrow box — ", scala.concurrent.duration.FiniteDuration(2, "s"))
          .length(1)
      ),
      page("log", "an append-only, follow-the-tail text log")(log(logLines)),
      page("logConsole", "the tail of a LogRing, filterable by level")(logConsole(ring).zone(java.time.ZoneOffset.UTC)),
      page("diffView", "a unified diff with hunk navigation")(diffView(patch, diff)),
      page("hexViewer", "a scrollable hexdump -C view")(hexViewer(bytes, hex)),
    )

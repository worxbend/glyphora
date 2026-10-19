package io.worxbend.tui.examples.gallery

import io.worxbend.tui.core.{BufferExport, ExportOptions, KeyCode, KeyModifiers, Size}
import io.worxbend.tui.dsl.{AnimationClock, Snapshot, Theme}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, Pilot}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, LocalDate}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The gallery is the visual regression harness for every exported factory: one page per name in the
  * `export Element.{…}` block of `dsl.scala`, and one golden frame per page.
  *
  * The block is *read*, not copied — the same way `DocumentedFactorySpec` reads it — so a factory added to the DSL
  * fails this suite until it has a page and a golden, and a page left behind by a removed factory fails it too.
  *
  * Goldens live in `src/test/resources/gallery/<factory>.txt` rather than in the shared `golden/` directory, because
  * their names are computed from the export block and the repository's orphan check only recognises names written out
  * literally; this suite does its own orphan check instead. Re-record them with
  * `GLYPHORA_GOLDEN_UPDATE=<abs path>/examples/gallery/src/test/resources`. With `GLYPHORA_GALLERY_EXPORT=<dir>` set,
  * every page is also written as `<dir>/<factory>.svg` through `BufferExport.svg` — the docs' screenshot source, which
  * `scripts/render-gallery.sh` drives.
  */
final class GallerySpec extends AnyFunSuite:

  private val PageSize = Size(80, 16)

  private val ExportBlock = """export Element\.\{([^}]*)\}""".r

  private lazy val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null) // scalafix:ok DisableSyntax; `Path.getParent` returns null at the filesystem root
      .find(candidate => Files.isRegularFile(candidate.resolve("build.mill")))
      .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))

  private def exportedFactories: Vector[String] =
    val source = Files.readString(repoRoot.resolve("dsl/src/main/scala/io/worxbend/tui/dsl/dsl.scala"))
    ExportBlock
      .findFirstMatchIn(source)
      .map(_.group(1).split(",").iterator.map(_.trim).filter(_.nonEmpty).toVector)
      .getOrElse(Vector.empty)

  /** A directory with known contents, so the file-browsing pages draw the same tree on every machine. */
  private lazy val fixtures: GalleryFixtures =
    val project = Files.createTempDirectory("glyphora-gallery").resolve("project")
    Files.createDirectories(project.resolve("src"))
    Files.writeString(project.resolve("src/App.scala"), "object App\n")
    Files.writeString(project.resolve("README.md"), "# project\n")
    Files.writeString(project.resolve("build.mill"), "// build\n")
    GalleryFixtures(project, LocalDate.of(2026, 10, 19), Instant.parse("2026-10-19T09:30:00Z"))

  private def goldenDirectory: Path = repoRoot.resolve("examples/gallery/src/test/resources/gallery")

  private def recordInto: Option[Path] = sys.env.get("GLYPHORA_GOLDEN_UPDATE").filter(_.nonEmpty).map(Path.of(_))

  private def exportInto: Option[Path] = sys.env.get("GLYPHORA_GALLERY_EXPORT").filter(_.nonEmpty).map(Path.of(_))

  test("there is exactly one page per exported factory — the count is the export block's size"):
    val exported = exportedFactories
    assert(exported.sizeIs > 50, s"read only ${exported.size} names from the export block; it moved or was renamed")
    val pages    = Gallery.pages(fixtures).map(_.factory)
    assert(pages.distinct.size == pages.size, s"a factory has two pages: ${pages.diff(pages.distinct)}")
    assert(
      exported.filterNot(pages.contains).isEmpty,
      s"exported factories with no gallery page: ${exported.filterNot(pages.contains).mkString(", ")}",
    )
    assert(
      pages.filterNot(exported.contains).isEmpty,
      s"pages for no exported factory: ${pages.filterNot(exported.contains)}",
    )
    assert(pages.size == exported.size)

  test("every page matches its golden frame, one golden per exported factory"):
    AnimationClock.freezeAt(1.second)
    try
      val pages    = Gallery.pages(fixtures)
      val failures = pages.flatMap { page =>
        val buffer = Snapshot.render(PageSize, Theme.Dark)(page.framed)
        // trailing blank rows are not significant, the same rule `GoldenFrames` applies
        val frame  = BufferAssertions.text(buffer).replaceAll("\\R+$", "")
        exportInto.foreach { directory =>
          Files.createDirectories(directory)
          val svg = BufferExport.svg(buffer, ExportOptions(title = page.factory))
          Files.writeString(directory.resolve(s"${page.factory}.svg"), svg, UTF_8)
        }
        recordInto match
          case Some(resources) =>
            val file = resources.resolve("gallery").resolve(s"${page.factory}.txt")
            Files.createDirectories(file.getParent)
            Files.writeString(file, frame, UTF_8)
            None
          case None            =>
            val file = goldenDirectory.resolve(s"${page.factory}.txt")
            if !Files.isRegularFile(file) then Some(s"${page.factory}: no golden at $file")
            else
              val expected = Files.readString(file, UTF_8).replaceAll("\\R+$", "")
              Option.when(expected != frame)(s"${page.factory}: frame changed\nactual:\n$frame\nexpected:\n$expected")
      }
      assert(failures.isEmpty, failures.mkString("\n\n"))
      if recordInto.isEmpty then
        val goldens = Using.resource(Files.list(goldenDirectory))(
          _.iterator.asScala.map(_.getFileName.toString).filter(_.endsWith(".txt")).map(_.stripSuffix(".txt")).toSet
        )
        assert(goldens == exportedFactories.toSet, s"orphaned goldens: ${goldens -- exportedFactories.toSet}")
    finally AnimationClock.resume()

  /** Pages whose findings are deliberate, each with the reason. A finding is exempt only for the page named here. */
  private val A11yExemptions: Map[String, String] = Map.empty

  test("every page passes the accessibility lint — the widest a11y surface in the repository"):
    val backend = HeadlessBackend(Size(110, 24))
    val app     = GalleryApp(fixtures)
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      val findings = app.pages.indices.flatMap { index =>
        if index > 0 then pilot.pressKey(KeyCode.Char('n'), KeyModifiers.Alt).waitForIdle()
        val page = app.pages(index)
        if A11yExemptions.contains(page.factory) then Seq.empty
        else pilot.a11yReport(app).map(finding => s"${page.factory}: $finding")
      }
      pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
      assert(pilot.awaitTermination())
      assert(findings.isEmpty, findings.mkString("\n"))
    }

  test("the app lists every page, steps through them, and quits"):
    val backend = HeadlessBackend(Size(110, 24))
    val app     = GalleryApp(fixtures)
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      assert(pilot.screenText.contains(s"Gallery · ${exportedFactories.size} factories"), pilot.screenText)
      val first  = app.pages.head
      assert(pilot.screenText.contains(s"${first.factory} — ${first.family}"), pilot.screenText)
      pilot.pressKey(KeyCode.Char('n'), KeyModifiers.Alt).waitForIdle()
      val second = app.pages(1)
      assert(pilot.screenText.contains(s"${second.factory} — ${second.family}"), pilot.screenText)
      pilot.pressKey(KeyCode.Down).waitForIdle()
      val third  = app.pages(2)
      assert(pilot.screenText.contains(s"${third.factory} — ${third.family}"), pilot.screenText)
      pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
      assert(pilot.awaitTermination())
    }

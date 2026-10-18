package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Buffer, BufferExport, Color, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/** The styled counterpart of [[GoldenFramesSpec]]: a fixture that records colours, modifiers and links as well as
  * glyphs, compared through the ANSI export, with a visual report written next to the fixture when it differs.
  */
final class StyledGoldensSpec extends AnyFunSuite:

  private def styledFrame(word: String): Buffer =
    val buffer = Buffer(Rect(0, 0, 8, 2))
    buffer.setString(0, 0, "日本", Style.of(Color.Yellow, Color.Indexed(17)).bold)
    buffer.setString(4, 0, word, Style.fg(Color.Rgb(10, 20, 30)).italic.withLink("https://example.com"))
    // an RGB underline colour: SGR 58 has no spelling for a named one, so `Color.Green` would come back as its RGB
    buffer.setString(0, 1, "ok", Style.Default.curlyUnderline.withUnderlineColor(Color.Rgb(0, 200, 0)))
    buffer

  test("the ANSI export parses back into an equal buffer, wide glyphs and all"):
    val source = styledFrame("ab")
    assert(StyledGoldens.parseAnsi(BufferExport.ansi(source)) == source)

  test("a frame matches the styled fixture recorded from it"):
    val source = styledFrame("ab")
    TempWorkspace.withWorkspace("glyphora-styled") { directory =>
      GoldenFrames.writeStyledFixture(directory, "styled-same", source)
      val fixture = GoldenFrames.styledFixtureFile(directory, "styled-same")
      assert(fixture.getFileName.toString == "styled-same.ansi")
      GoldenFrames.assertMatchesStyledText("styled-same", source, Files.readString(fixture), directory)
      assert(!Files.exists(directory.resolve("styled-same.diff.html")), "a match writes no report")
    }

  test("a style-only difference fails, writes the three report files and names them"):
    val recorded = styledFrame("ab")
    val actual   = styledFrame("ab")
    actual.setString(4, 0, "ab", Style.fg(Color.Rgb(10, 20, 30)).withLink("https://example.com")) // italic dropped
    assert(BufferAssertions.text(recorded) == BufferAssertions.text(actual), "the glyphs must agree for this to count")
    TempWorkspace.withWorkspace("glyphora-styled") { directory =>
      val expected = BufferExport.ansi(recorded)
      val error    = intercept[AssertionError] {
        GoldenFrames.assertMatchesStyledText("styled-diff", actual, expected, directory)
      }
      val reports  = Seq("styled-diff.expected.svg", "styled-diff.actual.svg", "styled-diff.diff.html")
      reports.foreach { name =>
        assert(Files.exists(directory.resolve(name)), s"$name was not written")
        assert(error.getMessage.contains(directory.resolve(name).toString), s"the failure does not name $name")
      }
      assert(error.getMessage.startsWith("styled frame differs from golden/styled-diff.ansi"))
      val html     = Files.readString(directory.resolve("styled-diff.diff.html"))
      assert(html.contains("<svg"), "the report embeds both pictures")
      assert(html.contains("""stroke="#ff3b30""""), "differing cells are outlined")
      assert(html.contains("2 of 16 cells differ"))
    }

  test("a fixture an editor has given a trailing newline still matches"):
    val source = styledFrame("ab")
    TempWorkspace.withWorkspace("glyphora-styled") { directory =>
      GoldenFrames.assertMatchesStyledText("hand-edited", source, BufferExport.ansi(source) + "\n", directory)
    }

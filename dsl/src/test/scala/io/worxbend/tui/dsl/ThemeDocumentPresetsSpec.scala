package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Style}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** Every preset gives the document palettes — `markdown` and `syntax` — values of its own: no field is left silent, the
  * Markdown fences and the standalone highlighter share one syntax palette, and a styled golden of the same document
  * per preset pins what each draws.
  */
final class ThemeDocumentPresetsSpec extends AnyFunSuite:

  private val Document =
    """# Heading one
      |## Heading two
      |### Heading three
      |
      |Some **strong**, *emphasis*, `code` and a [link](https://example.com).
      |
      |> a quote
      |
      |- a bullet
      |- [x] done
      |- [ ] open
      |
      || key | value |
      ||-----|-------|
      || a   | 1     |
      |
      |```scala
      |def add(a: Int) = a + 1 // sum
      |val s = "text"
      |```
      |""".stripMargin

  private def documentUnder(theme: Theme): Buffer =
    val area            = Rect(0, 0, 48, 22)
    val buffer          = Buffer(area)
    given ReactiveScope = ReactiveScope.untracked
    ElementHost().render(area, buffer, theme, markdown(Document)(using theme))
    buffer

  /** Every `Style` field of a palette, by name. */
  private def styles(palette: Product): Seq[(String, Style)] =
    palette.productElementNames
      .zip(palette.productIterator)
      .collect { case (name, style: Style) => (name, style) }
      .toSeq

  test("no preset leaves a markdown or syntax style silent, except plain text where the theme's own text is"):
    Theme.presets.foreach { theme =>
      val silent = (styles(theme.markdown) ++ styles(theme.markdown.syntax).map((n, s) => (s"syntax.$n", s)) ++
        styles(theme.syntax).map((n, s) => (s"standalone.$n", s))).collect {
        case (name, Style.Default) if !(name.endsWith(".default") && theme.primary == Style.Default) => name
      }
      assert(silent.isEmpty, s"${theme.name} leaves ${silent.mkString(", ")} at Style.Default")
    }

  test("every preset's markdown fences and standalone highlighter share one syntax palette"):
    Theme.presets.foreach(theme => assert(theme.markdown.syntax == theme.syntax, theme.name))

  test("every preset but dark moves off the dark-tuned widget defaults"):
    Theme.presets.filterNot(_.name == "dark").foreach { theme =>
      assert(theme.markdown != io.worxbend.tui.widgets.MarkdownTheme(), theme.name)
      assert(theme.syntax != io.worxbend.tui.widgets.SyntaxTheme(), theme.name)
    }

  // one literal fixture name per test, so the fixture-discipline check can see which test owns which file

  test("the document sample under dark"):
    GoldenFrames.assertMatchesStyled("markdown-preset-dark", documentUnder(Theme.Dark))

  test("the document sample under light"):
    GoldenFrames.assertMatchesStyled("markdown-preset-light", documentUnder(Theme.Light))

  test("the document sample under high-contrast"):
    GoldenFrames.assertMatchesStyled("markdown-preset-high-contrast", documentUnder(Theme.HighContrast))

  test("the document sample under dracula"):
    GoldenFrames.assertMatchesStyled("markdown-preset-dracula", documentUnder(Theme.Dracula))

  test("the document sample under catppuccin"):
    GoldenFrames.assertMatchesStyled("markdown-preset-catppuccin", documentUnder(Theme.Catppuccin))

  test("the document sample under nord"):
    GoldenFrames.assertMatchesStyled("markdown-preset-nord", documentUnder(Theme.Nord))

  test("the document sample under gruvbox"):
    GoldenFrames.assertMatchesStyled("markdown-preset-gruvbox", documentUnder(Theme.Gruvbox))

  test("the document sample under solarized"):
    GoldenFrames.assertMatchesStyled("markdown-preset-solarized", documentUnder(Theme.Solarized))

  test("the document sample under ansi16"):
    GoldenFrames.assertMatchesStyled("markdown-preset-ansi16", documentUnder(Theme.Ansi16))

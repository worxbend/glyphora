package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** The `markup"…"` interpolator: inline styling in a string literal, with the tag names and their nesting checked by
  * the compiler. Interpolated values are text, never markup, which is what makes `markup"[bold]$input[/]"` safe to
  * write over input the program did not author.
  */
final class MarkupLiteralSpec extends AnyFunSuite:

  test("a tag styles the run it encloses and [/] returns to the style outside it"):
    val text = markup"[bold red]error[/] in plain"
    assert(text == Text(Seq(Line(Seq(Span("error", Style.fg(Color.Red).bold), Span.raw(" in plain"))))))

  private inline def errorsOf(inline snippet: String): String =
    scala.compiletime.testing.typeCheckErrors(snippet).map(_.message).mkString("; ")

  test("a tag name that is not a style is a compile error naming it"):
    val message = errorsOf("""import io.worxbend.tui.core.markup; markup"[bogus]x[/]"""")
    assert(message.contains("bogus"), s"the message does not name the offending word: $message")
    assert(message.contains("bold"), s"the message does not list what would have been accepted: $message")

  test("a tag that is never closed is a compile error"):
    val message = errorsOf("""import io.worxbend.tui.core.markup; markup"[bold]x"""")
    assert(message.contains("never closed"), message)

  test("a [/] with nothing to close, a [ without ] and an empty [] are all compile errors"):
    assert(errorsOf("""import io.worxbend.tui.core.markup; markup"x[/]"""").contains("closes nothing"))
    assert(errorsOf("""import io.worxbend.tui.core.markup; markup"[bold x"""").contains("never closed with ]"))
    assert(errorsOf("""import io.worxbend.tui.core.markup; markup"[]x"""").contains("empty tag"))

  test("a named closing tag closes the innermost tag when it names that tag"):
    assert(markup"[bold]a[/bold]b" == Text(Seq(Line(Seq(Span("a", Style.Default.bold), Span.raw("b"))))))
    assert(markup"[bold red]a[/bold red]".lines.head.spans == Seq(Span("a", Style.fg(Color.Red).bold)))
    assert(markup"[Bold  Red]a[/bold red]".lines.head.spans == Seq(Span("a", Style.fg(Color.Red).bold)))
    assert(markup"[fg=red]a[/fg]".lines.head.spans == Seq(Span("a", Style.fg(Color.Red))))
    val url = "https://example.com"
    assert(markup"[link=$url]a[/link]".lines.head.spans == Seq(Span("a", Style.Default.withLink(url))))
    assert(
      markup"[bold]a[red]b[/red]c[/bold]".lines.head.spans ==
        Seq(
          Span("a", Style.Default.bold),
          Span("b", Style.Default.bold.withFg(Color.Red)),
          Span("c", Style.Default.bold),
        )
    )

  test("a named closing tag that does not name the innermost open tag is a compile error"):
    val wrong   = errorsOf("""import io.worxbend.tui.core.markup; markup"[bold]x[/italic]"""")
    assert(wrong.contains("[/italic]") && wrong.contains("[bold]"), wrong)
    val crossed = errorsOf("""import io.worxbend.tui.core.markup; markup"[bold][red]x[/bold][/]"""")
    assert(crossed.contains("[/bold]") && crossed.contains("[red]"), crossed)
    val partial = errorsOf("""import io.worxbend.tui.core.markup; markup"[bold red]x[/bold]"""")
    assert(partial.contains("[bold red]"), partial)
    assert(errorsOf("""import io.worxbend.tui.core.markup; markup"x[/bold]"""").contains("closes nothing"))

  test("a valid literal does compile through the same check, so the rejections above are not vacuous"):
    assert(scala.compiletime.testing.typeChecks("""import io.worxbend.tui.core.markup; markup"[bold]x[/]""""))

  test("an interpolated value is text, never markup: brackets in it are characters, not a style"):
    val x = "[red]"
    assert(markup"[bold]$x[/]" == Text(Seq(Line(Seq(Span("[red]", Style.Default.bold))))))
    val n = 42
    assert(markup"n=$n" == Text(Seq(Line(Seq(Span.raw("n="), Span.raw("42"))))))

  test("tags nest, the inner one layered over the outer, and [/] closes only the innermost"):
    val text = markup"[bold]a[red]b[/]c[/]d"
    assert(
      text.lines.head.spans == Seq(
        Span("a", Style.Default.bold),
        Span("b", Style.Default.bold.withFg(Color.Red)),
        Span("c", Style.Default.bold),
        Span.raw("d"),
      )
    )

  test("the ticket's own example: a link whose URL and label are both interpolated"):
    val url  = "https://example.com/f.scala"
    val file = "f.scala"
    val text = markup"[bold red]error[/] in [link=$url]$file[/]"
    assert(
      text.lines.head.spans == Seq(
        Span("error", Style.fg(Color.Red).bold),
        Span.raw(" in "),
        Span("f.scala", Style.Default.withLink(url)),
      )
    )

  test("fg= and bg= take an interpolated Color, and the compiler checks the type"):
    val accent  = Color.Rgb(1, 2, 3)
    assert(markup"[fg=$accent]x[/]" == Text(Seq(Line(Seq(Span("x", Style.fg(accent)))))))
    assert(markup"[bold bg=$accent]x[/]".lines.head.spans.head.style == Style.bg(accent).bold)
    val message = errorsOf("""import io.worxbend.tui.core.markup; val s = "red"; markup"[fg=$s]x[/]"""")
    assert(message.contains("Color"), message)
    val link    = errorsOf("""import io.worxbend.tui.core.markup; val n = 3; markup"[link=$n]x[/]"""")
    assert(link.contains("String"), link)

  test("an interpolated value anywhere else inside a tag is a compile error"):
    val message = errorsOf("""import io.worxbend.tui.core.markup; val s = "bold"; markup"[$s]x[/]"""")
    assert(message.contains("interpolated value is text"), message)
    val tail    = errorsOf("""import io.worxbend.tui.core.markup; val s = "red"; markup"[fg=$s bold]x[/]"""")
    assert(tail.contains("end right after"), tail)

  test("[[ is a literal bracket and ] outside a tag needs no escape"):
    assert(markup"[[bold] is literal" == Text.raw("[bold] is literal"))
    assert(markup"a]b" == Text.raw("a]b"))

  test("a newline in the literal starts a new row, and plain markup reads as Text.raw does"):
    assert(markup"first\nsecond" == Text.raw("first\nsecond"))
    assert(markup"" == Text.raw(""))
    assert(markup"a\n" == Text.raw("a\n"))
    val text = markup"[bold]one\ntwo[/]"
    assert(text.lines == Seq(Line(Seq(Span("one", Style.Default.bold))), Line(Seq(Span("two", Style.Default.bold)))))

  test("every colour spelling Color.parse reads, plus ansi(n), 'on', and the attribute words"):
    def only(text: Text): Style = text.lines.head.spans.head.style
    assert(only(markup"[bright_cyan]x[/]") == Style.fg(Color.BrightCyan))
    assert(only(markup"[#ff8800]x[/]") == Style.fg(Color.Rgb(255, 136, 0)))
    assert(only(markup"[ansi(208)]x[/]") == Style.fg(Color.Indexed(208)))
    assert(only(markup"[red on blue]x[/]") == Style.of(Color.Red, Color.Blue))
    assert(only(markup"[fg=red bg=#000]x[/]") == Style.of(Color.Red, Color.Rgb(0, 0, 0)))
    assert(only(markup"[link=https://x.y]x[/]") == Style.Default.withLink("https://x.y"))
    assert(
      only(markup"[dim italic underline blink reverse hidden strike]x[/]") ==
        Style.Default.dim.italic.underline.blink.reverse.hidden.crossedOut
    )
    assert(only(markup"[underline=curly]x[/]") == Style.Default.curlyUnderline)
    assert(only(markup"[b i u]x[/]") == Style.Default.bold.italic.underline)
    assert(errorsOf("""import io.worxbend.tui.core.markup; markup"[ansi(300)]x[/]"""").contains("0..255"))

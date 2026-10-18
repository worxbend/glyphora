package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** `text(content: Text)`: the factory that shows a `Text` that already carries its own styles — a `markup"…"` literal
  * or `Text.fromAnsi` output — with the same layout behaviour `text(String)` has, so the two can stand next to each
  * other in a `column` without the styled one eating the rows the plain one left.
  */
final class RichTextElementSpec extends AnyFunSuite:

  private def lines(element: Element, width: Int, height: Int): Seq[String] =
    trimmedLines(rendered(element.widget, width, height))

  test("the spans' own styles reach the cells"):
    val buffer = rendered(text(markup"[bold red]err[/] ok").widget, 8, 1)
    assert(buffer.get(0, 0).style == Style.fg(Color.Red).bold)
    assert(buffer.get(3, 0).style == Style.Default)
    assert(buffer.get(4, 0).symbol == "o")

  test("the element's own style sits underneath the spans', so a span that chose a colour keeps it"):
    val buffer = rendered(text(markup"[red]a[/]b").dim.widget, 2, 1)
    assert(buffer.get(0, 0).style == Style.fg(Color.Red).dim)
    assert(buffer.get(1, 0).style == Style.Default.dim)

  test("a styled text claims the exact box its rows measure, in display columns, like text(String) does"):
    val claim = text(markup"[bold]日本[/]\ncdef").claim
    assert(claim.horizontal == Constraint.Length(4) && claim.vertical == Constraint.Length(2))
    assert(text(Text.fromAnsi("\u001b[1mabcdefgh\u001b[0m")).wrapped.intrinsicHeight(4) == Some(2))

  test("wrapping, clipping and alignment behave as they do on text(String)"):
    assert(lines(text(Text.raw("abcdefgh")).wrapped, 4, 3) == Seq("abcd", "efgh", ""))
    assert(lines(text(Text.raw("abcdefgh")).wrapped.clipped, 4, 2) == Seq("abcd", ""))
    assert(lines(text(Text.raw("ab")).centered, 6, 1) == Seq("  ab"))
    assert(lines(text(Text.raw("ab")).rightAligned, 6, 1) == Seq("    ab"))
    assert(text(Text.raw("long single line of prose")).wrapped.claim == SizeClaim.Fill)

  test("empty and zero-sized areas paint nothing and do not throw"):
    assert(lines(text(Text.Empty), 0, 0).isEmpty)
    assert(lines(text(Text.raw("")).wrapped, 4, 1) == Seq(""))

  test("the String overload is still what a bare `text` function value resolves to, so helpers keep compiling"):
    // `labels.map(text)` is how a list of captions is built in more than one app; the Text overload must not make it
    // ambiguous
    val captions: Seq[Element] = Seq("a", "b").map(text)
    assert(captions.size == 2)
    val styled: Seq[Element]   = Seq(Text.raw("a")).map(text)
    assert(styled.size == 1)

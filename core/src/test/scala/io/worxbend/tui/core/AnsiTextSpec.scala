package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** `Text.fromAnsi`: SGR and OSC 8 escape sequences read back into the styled [[Text]] they describe — the inverse of
  * the terminal module's encoder, so coloured subprocess output can be shown inside a widget.
  */
final class AnsiTextSpec extends AnyFunSuite:

  private val Esc = "\u001b"

  test("a bold red run followed by a reset becomes two spans, the second in the default style"):
    val text = Text.fromAnsi(s"$Esc[1;31mred$Esc[0m plain")
    assert(text.lines == Seq(Line(Seq(Span("red", Style.fg(Color.Red).bold), Span.raw(" plain")))))

  test("the width of a parsed line is measured in display columns, so CJK counts double"):
    val text = Text.fromAnsi(s"$Esc[32m日本語$Esc[0m ok")
    assert(text.width == 9) // three wide characters plus " ok"
    assert(text.lines.head.spans.head == Span("日本語", Style.fg(Color.Green)))

  test("an OSC 8 hyperlink lands on Style.link and an empty URI closes it"):
    val text = Text.fromAnsi(s"$Esc]8;;https://example.com$Esc\\docs$Esc]8;;$Esc\\ after")
    assert(
      text.lines.head.spans == Seq(Span("docs", Style.Default.withLink("https://example.com")), Span.raw(" after"))
    )

  test("an OSC 8 link terminated by BEL and carrying parameters is read the same way"):
    val text = Text.fromAnsi(s"$Esc]8;id=1;https://x.y\u0007link\u001b]8;;\u0007")
    assert(text.lines.head.spans == Seq(Span("link", Style.Default.withLink("https://x.y"))))

  test("a link survives an SGR reset, because it is not an SGR attribute"):
    val text = Text.fromAnsi(s"$Esc]8;;u$Esc\\$Esc[1mb$Esc[0mp$Esc]8;;$Esc\\")
    assert(
      text.lines.head.spans == Seq(Span("b", Style.Default.bold.withLink("u")), Span("p", Style.Default.withLink("u")))
    )

  test("a chunked parse equals the one-shot parse when a chunk boundary splits an SGR sequence"):
    val whole  = s"first $Esc[38;2;10;20;30mcolour$Esc[0m\nsecond line\ntail"
    val parser = AnsiParser()
    // the cut falls inside `ESC [ 38 ; 2 ; 10 ; 2 | 0 ; 30 m`
    val cut    = whole.indexOf("20;30m") + 1
    val lines  = parser.feed(whole.take(cut)) ++ parser.feed(whole.drop(cut))
    assert(Text(lines :+ parser.finish()) == Text.fromAnsi(whole))
    assert(lines.size == 2, "feed hands back only the lines a chunk completed")

  test("the style in force at the end of a chunk carries into the next one"):
    val parser = AnsiParser()
    assert(parser.feed(s"$Esc[1mbold\n") == Seq(Line(Seq(Span("bold", Style.Default.bold)))))
    assert(parser.feed("still\n") == Seq(Line(Seq(Span("still", Style.Default.bold)))))
    assert(parser.finish() == Line(Seq(Span("", Style.Default.bold))))

  test("plain text with no escape in it reads exactly as Text.raw does, trailing empty line included"):
    for sample <- Seq("", "a", "a\n", "a\nb", "\n\n", "日本\n語") do assert(Text.fromAnsi(sample) == Text.raw(sample))

  test("CRLF splits into clean rows and other control characters are dropped"):
    assert(Text.fromAnsi("a\r\nb\u0007c\td") == Text.raw("a\nbcd"))

  test("256-colour and truecolour foregrounds and backgrounds, in both their spellings"):
    def only(s: String): Style = Text.fromAnsi(s"${s}x").lines.head.spans.head.style
    assert(only(s"$Esc[38;5;208m") == Style.fg(Color.Indexed(208)))
    assert(only(s"$Esc[48;5;17m") == Style.bg(Color.Indexed(17)))
    assert(only(s"$Esc[38;2;1;2;3m") == Style.fg(Color.Rgb(1, 2, 3)))
    assert(only(s"$Esc[48;2;4;5;6m") == Style.bg(Color.Rgb(4, 5, 6)))
    assert(only(s"$Esc[38:5:208m") == Style.fg(Color.Indexed(208)))
    assert(only(s"$Esc[38:2::1:2:3m") == Style.fg(Color.Rgb(1, 2, 3)))
    assert(only(s"$Esc[38:2:1:2:3m") == Style.fg(Color.Rgb(1, 2, 3)))
    assert(only(s"$Esc[1;38;5;208;4m") == Style.fg(Color.Indexed(208)).bold.underline)
    assert(only(s"$Esc[38;5m") == Style.Default, "a truncated extended colour sets nothing")

  test("the bright colours, every attribute, and the reset code for each"):
    def only(s: String): Style = Text.fromAnsi(s"${s}x").lines.head.spans.head.style
    assert(only(s"$Esc[91;104m") == Style.of(Color.BrightRed, Color.BrightBlue))
    assert(only(s"$Esc[2;3;5;7;8;9m") == Style.Default.dim.italic.blink.reverse.hidden.crossedOut)
    assert(only(s"$Esc[6m") == Style.Default.rapidBlink)
    assert(only(s"$Esc[1;2;22m") == Style.Default, "22 clears bold and dim together")
    assert(only(s"$Esc[3;23m") == Style.Default)
    assert(only(s"$Esc[4;24m") == Style.Default)
    assert(only(s"$Esc[5;6;25m") == Style.Default)
    assert(only(s"$Esc[7;27;8;28;9;29m") == Style.Default)
    assert(only(s"$Esc[31;39m") == Style.Default, "39 is the default foreground, which in a Text is silence")
    assert(only(s"$Esc[41;49m") == Style.Default)
    assert(only(s"$Esc[m") == Style.Default, "an empty SGR is a reset")

  test("styled underlines and the underline colour, the way the encoder writes them"):
    def only(s: String): Style = Text.fromAnsi(s"${s}x").lines.head.spans.head.style
    assert(only(s"$Esc[4:3m") == Style.Default.curlyUnderline)
    assert(only(s"$Esc[4:2m") == Style.Default.doubleUnderline)
    assert(only(s"$Esc[4:4m") == Style.Default.dottedUnderline)
    assert(only(s"$Esc[4:5m") == Style.Default.dashedUnderline)
    assert(only(s"$Esc[21m") == Style.Default.doubleUnderline)
    assert(only(s"$Esc[4:1m") == Style.Default.underline)
    assert(only(s"$Esc[4:3;4:0m") == Style.Default, "4:0 turns a styled underline off")
    assert(only(s"$Esc[4:3;24m") == Style.Default, "24 clears the styled underline along with the plain one")
    assert(only(s"$Esc[58:2::9:8:7m") == Style.Default.withUnderlineColor(Color.Rgb(9, 8, 7)))
    assert(only(s"$Esc[58;5;3;59m") == Style.Default)

  test("sequences that are not styles are dropped whole, and the text around them is kept"):
    val cases = Seq(
      s"a$Esc[2Jb"              -> "ab", // clear screen
      s"a$Esc[1;1Hb"            -> "ab", // cursor position
      s"a$Esc[?25lb"            -> "ab", // private mode: hide cursor
      s"a$Esc[?1mb"             -> "ab", // a private-marker `m` is not an SGR
      s"a$Esc]0;title\u0007b"   -> "ab", // OSC 0 window title
      s"a$Esc]2;title$Esc\\b"   -> "ab", // OSC 2, ST-terminated
      s"a${Esc}P+q544e$Esc\\b"  -> "ab", // DCS
      s"a${Esc}_payload$Esc\\b" -> "ab", // APC
      s"a$Esc(Bb"               -> "ab", // charset designation (nF escape)
      s"a${Esc}7b"              -> "ab", // save cursor (two-byte escape)
      s"a$Esc[38;2;1;2;3;9Xb"   -> "ab", // a long CSI with a non-SGR final byte
    )
    for (input, expected) <- cases do
      assert(Text.fromAnsi(input) == Text.raw(expected), s"for ${input.replace(Esc, "ESC")}")

  test(
    "an escape sequence the stream breaks off in is dropped by finish, and the parser is reusable for the next line"
  ):
    val parser = AnsiParser()
    assert(parser.feed(s"ok$Esc[1;3") == Seq.empty)
    assert(parser.finish() == Line.raw("ok"))
    assert(parser.feed("next\n") == Seq(Line.raw("next")))

  test("adjacent runs in one style stay one span, even across a redundant SGR"):
    assert(Text.fromAnsi(s"$Esc[1ma$Esc[1mb").lines.head.spans == Seq(Span("ab", Style.Default.bold)))

  test("an out-of-range palette index or channel does not produce a colour the encoder could not write"):
    def only(s: String): Style = Text.fromAnsi(s"${s}x").lines.head.spans.head.style
    assert(only(s"$Esc[38;5;300m") == Style.Default)
    assert(only(s"$Esc[38;2;300;0;0m") == Style.fg(Color.Rgb(255, 0, 0)), "channels clamp, as Color.rgb does")

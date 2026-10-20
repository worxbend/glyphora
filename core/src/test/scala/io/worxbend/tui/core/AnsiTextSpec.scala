package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** `Text.fromAnsi`: SGR and OSC 8 escape sequences read back into the styled [[Text]] they describe — the inverse of
  * the terminal module's encoder, so coloured subprocess output can be shown inside a widget.
  */
final class AnsiTextSpec extends AnyFunSuite:

  private val Esc = "\u001b"

  test("control strings and CSI recover with bounded storage across chunks"):
    for opener <- Seq(s"$Esc]", s"${Esc}P", s"${Esc}_", s"$Esc[") do
      val parser = AnsiParser()
      parser.feed(opener + "1" * 4096)
      assert(parser.feed("ok\n").map(_.plainText) == Seq("ok"))
    assert(Text.fromAnsi(s"a$Esc[31\nmX").plainText == "a\nX")
    for cancel <- Seq('\u0018', '\u001a') do
      assert(Text.fromAnsi(s"$Esc[31${cancel}mX").lines.head == Line.raw("mX"))
      assert(Text.fromAnsi(s"$Esc]title${cancel}X").plainText == "X")

  test("tabs and overwrites measure graphemes across SGR and chunk boundaries"):
    for
      cluster  <- Seq("👩‍💻", "👍🏽", "e\u0301")
      boundary <- 0 to cluster.length
      if boundary == cluster.length || !Character.isLowSurrogate(cluster.charAt(boundary))
    do
      val first    = cluster.take(boundary)
      val rest     = cluster.drop(boundary)
      val split    = s"$first$Esc[31m$rest"
      val width    = CharWidth.of(cluster)
      assert(Text.fromAnsi(split + "\tq").plainText == cluster + " " * (8 - width) + "q")
      val input    = "XXXXXX\r" + split + "z"
      val expected = cluster + "z" + "X" * (5 - width)
      assert(Text.fromAnsi(input).plainText == expected)
      for cut <- 0 to input.length do
        val parser = AnsiParser()
        assert(
          Text(parser.feed(input.take(cut)) ++ parser.feed(input.drop(cut)) :+ parser.finish()).plainText == expected
        )

  test("long tab-separated output stays at eight-column stops"):
    val input = "a\t" * 32000 + "q"
    val line  = Text.fromAnsi(input).lines.head
    assert(line.width == 256001)
    assert(line.plainText == "a       " * 32000 + "q")

  test("tab expansion scales with the added text, not the accumulated line"):
    val clock                     = java.lang.management.ManagementFactory.getThreadMXBean
    if !clock.isCurrentThreadCpuTimeSupported then cancel("thread CPU clock unavailable")
    def elapsed(count: Int): Long =
      val input = "a\t" * count
      val start = clock.getCurrentThreadCpuTime
      assert(Text.fromAnsi(input).lines.head.width == count * 8)
      clock.getCurrentThreadCpuTime - start
    (0 until 4).foreach(_ => elapsed(2000))
    val short                     = (0 until 3).map(_ => elapsed(2000)).min
    val long                      = (0 until 3).map(_ => elapsed(32000)).min
    info(s"tab CPU nanos: 2000=$short, 32000=$long")
    assert(long < short * 40L + 100000000L, "16x the input must not perform quadratic prefix scans")

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
    assert(Text.fromAnsi("a\r\nb\u0007cd") == Text.raw("a\nbcd"))

  test("a tab expands to the next eight-column stop"):
    assert(Text.fromAnsi("a\tb") == Text.raw("a       b"))
    assert(Text.fromAnsi("\tx") == Text.raw("        x"))
    assert(Text.fromAnsi("12345678\tx") == Text.raw("12345678        x"))
    assert(Text.fromAnsi("a\t\tb") == Text.raw("a               b"))

  test("tab stops are counted in display columns, so a wide character takes two"):
    assert(Text.fromAnsi("漢\tx") == Text.raw("漢      x"))
    assert(Text.fromAnsi("e\u0301\tx") == Text.raw("e\u0301       x"))

  test("a tab keeps its column across a style change and a chunk boundary"):
    val parser = AnsiParser()
    assert(parser.feed(s"$Esc[1mab") == Seq.empty)
    assert(parser.feed(s"$Esc[0m\tc\n") == Seq(Line(Seq(Span("ab", Style.Default.bold), Span.raw("      c")))))

  test("a carriage return rewrites the line from its first column, so a progress bar shows its latest state"):
    assert(Text.fromAnsi("10%\r55%\r100%\n") == Text.raw("100%\n"))
    assert(Text.fromAnsi("ab\rxyz") == Text.raw("xyz"))

  test("a shorter rewrite leaves the tail of the longer one, as a terminal does, until an erase-line clears it"):
    assert(Text.fromAnsi("100%\r5%") == Text.raw("5%0%"))
    assert(Text.fromAnsi(s"100%\r$Esc[K5%") == Text.raw("5%"))
    assert(Text.fromAnsi(s"100%\r$Esc[2K5%") == Text.raw("5%"))
    assert(Text.fromAnsi(s"abcd\r12$Esc[0K") == Text.raw("12"))

  test("an overwrite keeps the styles of what it does not cover"):
    val line = Text.fromAnsi(s"$Esc[31mabc$Esc[0m\rX").lines.head
    assert(line == Line(Seq(Span.raw("X"), Span("bc", Style.fg(Color.Red)))))

  test("overwriting half of a wide character blanks its other half"):
    assert(Text.fromAnsi("漢字\rab") == Text.raw("ab字"))
    assert(Text.fromAnsi("漢字\r a") == Text.raw(" a字"))
    assert(Text.fromAnsi("ab字\r漢") == Text.raw("漢字"))
    assert(Text.fromAnsi("a字\r漢") == Text.raw("漢 "))

  test("a tab after a carriage return moves over the line without erasing it"):
    assert(Text.fromAnsi("0123456789\r\tX") == Text.raw("01234567X9"))
    assert(Text.fromAnsi("ab\r\tX") == Text.raw("ab      X"))

  test("a carriage return survives a chunk boundary"):
    val parser = AnsiParser()
    assert(parser.feed("loading 10%\r") == Seq.empty)
    assert(parser.feed("loading 99%\r") == Seq.empty)
    assert(parser.feed("done       \n") == Seq(Line.raw("done       ")))

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

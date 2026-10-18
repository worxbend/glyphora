package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

/** The encoder is the last stage before bytes reach the terminal, and the only stage `HeadlessBackend` does not
  * exercise: a headless test snapshots the composed [[Buffer]] and never asks how that buffer would be written out. So
  * every rule the encoder carries across cells — cursor position, SGR state, the open hyperlink — is pinned here or
  * nowhere.
  */
final class FrameEncoderSpec extends AnyFunSuite:

  private val encoder = FrameEncoder(ColorDepth.TrueColor)

  private def sgr(style: Style): String = Sgr.sgr(style, ColorDepth.TrueColor)

  private def frame(write: Buffer => Unit): Buffer =
    val buffer = Buffer(Rect(0, 0, 10, 2))
    write(buffer)
    buffer

  test("an unchanged frame encodes to nothing at all"):
    val previous = frame(_.setString(0, 0, "hello", Style.Default))
    val next     = frame(_.setString(0, 0, "hello", Style.Default))
    assert(encoder.encode(previous, next) == "")

  test("a run of changed cells is positioned once and then written straight through"):
    val previous = frame(_ => ())
    val next     = frame(_.setString(2, 1, "ok", Style.Default))
    // one move to the start of the run, then both symbols: the cursor advances on its own within a row
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(2, 1) + sgr(Style.Default) + "ok")

  test("a hyperlink left open by the last changed cell is closed before the frame ends"):
    val previous = frame(_ => ())
    val next     = frame(_.setString(0, 0, "x", Style.Default.withLink("https://example.invalid")))
    assert(encoder.encode(previous, next).endsWith(AnsiSequences.LinkClose))

  // ---------------------------------------------------------------- SGR carry-over

  test("two styles in one row emit their own SGR sequences, in order"):
    // an encoder that dropped the cell's style and always wrote `sgr(Style.Default)` would still produce a frame of the
    // right shape with the right glyphs in the right places — and a colourless, unstyled terminal.
    val warning  = Style.Default.withFg(Color.Rgb(220, 160, 40)).bold
    val muted    = Style.Default.withFg(Color.Rgb(120, 120, 120))
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      buffer.setString(0, 0, "ab", warning)
      buffer.setString(2, 0, "cd", muted)
    }
    // the first style on a frame is written in full, the second as the difference from it
    val second   = Sgr.sgrDelta(warning, muted, ColorDepth.TrueColor)
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(warning) + "ab" + second + "cd")

  test("a later style change on a frame writes only what moved, not a fresh reset"):
    // the point of the delta form, asserted where it actually pays off: two runs that share both colours and differ
    // only in the bold flag cost one parameter, where the absolute form would restate both truecolour selectors
    val plain    = Style.Default.withFg(Color.Rgb(220, 160, 40)).withBg(Color.Rgb(10, 20, 60))
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      buffer.setString(0, 0, "ab", plain)
      buffer.setString(2, 0, "cd", plain.bold)
    }
    val encoded  = encoder.encode(previous, next)
    assert(encoded.endsWith("ab\u001b[1mcd"))
    // exactly one reset on the frame: the anchor at its first painted cell
    assert(encoded.split("\u001b\\[0;", -1).length - 1 == 1)

  test("each frame re-anchors with a full sequence rather than trusting the previous frame's state"):
    // the encoder is called once per frame and keeps nothing between calls, so it cannot know what the terminal was
    // left holding — by the last frame, by the shell, or by a subprocess. Every frame therefore opens with a reset.
    val bold   = Style.Default.bold
    val first  = encoder.encode(frame(_ => ()), frame(_.setString(0, 0, "x", bold)))
    val second = encoder.encode(frame(_.setString(0, 0, "x", bold)), frame(_.setString(0, 0, "y", bold)))
    assert(first.contains(sgr(bold)))
    assert(second.contains(sgr(bold)))

  test("a run sharing one style emits that SGR once, even when every cell holds its own equal Style"):
    // what `Effect.fadeIn` and every themed widget produce: a fresh but equal `Style` per cell. The encoder compares
    // structurally as well as by reference, so the sequence is written at the head of the run and not repeated.
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      "hello".zipWithIndex.foreach { (character, x) =>
        buffer.setString(x, 0, character.toString, Style.Default.withFg(Color.Rgb(10, 20, 30)))
      }
    }
    val shared   = sgr(Style.Default.withFg(Color.Rgb(10, 20, 30)))
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + shared + "hello")

  // ---------------------------------------------------------------- the cursor model over wide graphemes

  test("the cursor is not repositioned across a wide grapheme, because the terminal advances two columns for it"):
    // `漢` occupies columns 1 and 2, so after writing it the terminal's cursor sits at column 3 — exactly where `b` is.
    // An encoder that assumed every glyph advances one column would think the cursor was at 2 and insert a move, and on
    // a real terminal every cell after the first wide glyph in a changed run would land one column left of where the
    // frame says it goes, overwriting the glyph's right half.
    val previous = frame(_ => ())
    val next     = frame(_.setString(0, 0, "a漢b", Style.Default))
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "a漢b")

  test("a changed cell past where a wide grapheme leaves the cursor is repositioned"):
    // the complement of the case above, so it cannot be satisfied by an encoder that never repositions within a row:
    // `漢` leaves the cursor at column 2, `b` is at column 3, and column 2 is unchanged so nothing was written there.
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      buffer.setString(0, 0, "漢", Style.Default)
      buffer.setString(3, 0, "b", Style.Default)
    }
    assert(
      encoder.encode(previous, next) ==
        AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "漢" + AnsiSequences.moveTo(3, 0) + "b"
    )

  test("a run of wide graphemes advances two columns each, with no repositioning between them"):
    // every cell in this row is non-ASCII, which is exactly the case the ordering inside `advanceOf` serves: the
    // buffer's continuation bookkeeping must answer the width question on its own — a single move for the whole run,
    // and any answer of one column per grapheme would show up as `moveTo` sequences between them
    val previous = frame(_ => ())
    val next     = frame(_.setString(0, 0, "漢字x", Style.Default))
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "漢字x")

  test("a frame of a different shape repaints in full instead of diffing"):
    // what a resize looks like from here: the previous frame describes a grid the terminal no longer has, so there is
    // nothing to compare against. `Buffer.diff` refuses such a pair outright, and the encoder is the caller that has to
    // know the difference between "no usable predecessor" and "somebody passed the wrong buffer".
    val previous = Buffer(Rect(0, 0, 2, 1))
    val next     = Buffer(Rect(0, 0, 4, 1))
    next.setString(0, 0, "grew", Style.Default)
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "grew")

  test("a repaint after a resize writes the blank cells too, so nothing of the old frame survives"):
    val previous = Buffer(Rect(0, 0, 6, 1))
    val next     = Buffer(Rect(0, 0, 3, 1))
    next.setString(0, 0, "a", Style.Default)
    // three cells written, not one: the two blanks are what clears whatever the old, wider frame had drawn there
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "a  ")

  test("a pathologically long symbol cannot swallow the cells drawn after it"):
    // The regression ratatui shipped two tests for. A `Cell`'s symbol is meant to hold a single grapheme cluster, but
    // nothing in the type stops a caller from putting a whole escape sequence — or a base character trailed by a dozen
    // combining marks — into one. Measuring such a symbol as a *string* answers "many columns"; the buffer reserved
    // one. The encoder used to believe that measurement, decide the cursor already stood past the next changed cell,
    // and omit that cell's `moveTo` — so every later cell in the row was painted in the wrong column.
    val smuggled = "e" + "́" * 40
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      buffer.set(0, 0, Cell(smuggled, Style.Default))
      buffer.setString(4, 0, "tail", Style.Default)
    }
    assert(
      encoder.encode(previous, next) ==
        AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + smuggled + AnsiSequences.moveTo(4, 0) + "tail"
    )

  test("a long symbol leaves the cell immediately beside it addressable"):
    // The tight half of the same bug: the cell at column 1 is where a one-column symbol at column 0 leaves the cursor,
    // so it must be written with no move at all. An over-measured symbol used to push `expectedX` past column 1, which
    // is how a whole row's worth of cells ended up shifted.
    val smuggled = "x" + "̀" * 3
    val previous = frame(_ => ())
    val next     = frame { buffer =>
      buffer.set(0, 0, Cell(smuggled, Style.Default))
      buffer.setString(1, 0, "y", Style.Default)
    }
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + smuggled + "y")

  // ---------------------------------------------------------------- rows printed into the scrollback

  test("a row encoded for printing carries its styling and no cursor movement"):
    // `encodeRow` is what `insertBefore` writes into the terminal's scrollback. Absolute cursor moves are exactly wrong
    // there: the terminal, not the app, decides which line printed text lands on.
    val warning = Style.Default.withFg(Color.Rgb(220, 160, 40)).bold
    val row     = frame(_.setString(0, 0, "warn", warning))
    val encoded = encoder.encodeRow(row, 0)
    assert(encoded == sgr(warning) + "warn" + AnsiSequences.ResetStyle)
    assert(!encoded.contains(AnsiSequences.moveTo(0, 0)))

  test("an encoded row always ends with a style reset"):
    // without it, a background colour set for the last cell bleeds into whatever the shell prints next — and that line
    // is durable scrollback, so the bleed stays on screen
    val row = frame(_.setString(0, 0, "x", Style.Default.withBg(Color.Rgb(80, 0, 0))))
    assert(encoder.encodeRow(row, 0).endsWith(AnsiSequences.ResetStyle))

  test("trailing padding is not encoded, so the line does not paint to the window edge"):
    val row = frame(_.setString(0, 0, "ab", Style.Default))
    assert(encoder.encodeRow(row, 0) == sgr(Style.Default) + "ab" + AnsiSequences.ResetStyle)

  test("a wide grapheme is written once, without its continuation column"):
    val row = frame(_.setString(0, 0, "漢b", Style.Default))
    assert(encoder.encodeRow(row, 0) == sgr(Style.Default) + "漢b" + AnsiSequences.ResetStyle)

  test("a blank row encodes to nothing at all"):
    assert(encoder.encodeRow(frame(_ => ()), 0) == "")

  test("a row outside the buffer encodes to nothing rather than failing"):
    assert(encoder.encodeRow(frame(_.setString(0, 0, "x", Style.Default)), 9) == "")

  test("a hyperlink in a printed row is opened and closed within that row"):
    val linked  = Style.Default.withLink("https://example.invalid")
    val row     = frame(_.setString(0, 0, "docs", linked))
    val encoded = encoder.encodeRow(row, 0)
    assert(encoded.contains(AnsiSequences.linkOpen("https://example.invalid")))
    assert(encoded.contains(AnsiSequences.LinkClose))

  // ---------------------------------------------------------------- cells the cursor does not advance past

  test("the cell after a zero-width one is positioned explicitly"):
    // A lone combining acute is a grapheme the terminal draws on top of the character to its left, so the terminal's
    // own cursor does not move for it. Assuming that it did painted every later cell of the row one column too far
    // left: "b" below used to be written straight after the mark, landing in column 1 instead of column 2.
    val combiningAcute = "́"
    val previous       = Buffer(Rect(0, 0, 5, 1))
    val next           = Buffer(Rect(0, 0, 5, 1))
    next.set(0, 0, Cell("a", Style.Default))
    next.set(1, 0, Cell(combiningAcute, Style.Default))
    next.set(2, 0, Cell("b", Style.Default))
    val expected       =
      AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + "a" + combiningAcute + AnsiSequences.moveTo(2, 0) + "b"
    assert(encoder.encode(previous, next) == expected)

  // ---------------------------------------------------------------- a malformed cell cannot smuggle control bytes

  test("a cell whose symbol carries a raw escape sequence is written as a blank, not verbatim"):
    // `Cell.symbol` is meant to hold exactly one printable grapheme cluster, but nothing enforces that at
    // construction — `Buffer.set` (unlike `Buffer.setString`) writes whatever string it is given. A widget's
    // caller-supplied glyph parameter (a `Scrollbar` track symbol, a `LineGauge` glyph) reaches `Buffer.set`
    // directly, so a control sequence there would otherwise reach the real terminal exactly as if the application
    // had emitted it — indistinguishable from the escapes this encoder is meant to be the sole author of.
    val escape   = "\u001b[2J" // clear-screen, smuggled in as if it were a one-column glyph
    val previous = Buffer(Rect(0, 0, 3, 1))
    val next     = Buffer(Rect(0, 0, 3, 1))
    next.set(0, 0, Cell(escape, Style.Default))
    val out      = encoder.encode(previous, next)
    assert(!out.contains(escape))
    assert(out == AnsiSequences.moveTo(0, 0) + sgr(Style.Default) + " ")

  test("a control byte inside encodeRow is likewise blanked, not printed to scrollback"):
    val buffer = Buffer(Rect(0, 0, 3, 1))
    buffer.set(0, 0, Cell("\u0007", Style.Default)) // BEL: would ring a bell or worse if ever printed raw
    assert(!encoder.encodeRow(buffer, 0).contains("\u0007"))

  test("an ordinary multi-column emoji glyph is unaffected by the control-byte guard"):
    val previous = Buffer(Rect(0, 0, 3, 1))
    val next     = Buffer(Rect(0, 0, 3, 1))
    next.set(0, 0, Cell("🟦", Style.Default))
    assert(encoder.encode(previous, next).contains("🟦"))

  // ---------------------------------------------------------------- empty symbols (R2-03)

  /** `Cell("")` is the blank `Cell.isBlank` says it is. `Buffer.set` stores it as a styled one-column space, so a frame
    * written through the buffer never carries one; the encoder is the last stage before the bytes reach the terminal
    * and guards the same rule itself, so a cell that reaches it unnormalised still claims its column instead of leaving
    * the previous glyph on screen in a column the buffer calls blank.
    */
  test("an empty-symbol cell is flushed as a blank"):
    val red      = Style.Default.withBg(Color.Rgb(200, 0, 0))
    val previous = frame(_.setString(0, 0, "abc", Style.Default))
    val next     = frame { buffer =>
      buffer.setString(0, 0, "abc", Style.Default)
      buffer.set(1, 0, Cell("", red))
    }
    assert(encoder.encode(previous, next) == AnsiSequences.moveTo(1, 0) + sgr(red) + " ")

  test("a buffer filled with an empty-symbol cell paints its background"):
    val blue     = Style.Default.withBg(Color.Rgb(0, 0, 200))
    val previous = frame(_ => ())
    val next     = frame(buffer => buffer.fill(buffer.area, Cell("", blue)))
    // every cell of both rows is written as a blank in the fill's style: one move per row, then ten spaces
    assert(
      encoder.encode(previous, next) ==
        AnsiSequences.moveTo(0, 0) + sgr(blue) + " " * 10 + AnsiSequences.moveTo(0, 1) + " " * 10
    )

  test("the encoder's own guard spells an empty symbol as a blank that advances one column"):
    // the seam the two tests above cannot reach through a `Buffer`, which normalises the cell first: a `Cell("")`
    // handed straight to the encoder is written as a space, and the cursor is known to have moved one column for it
    val empty = Cell("", Style.Default)
    assert(encoder.guarded(empty.symbol) == " ")
    assert(encoder.advanceOf(frame(_ => ()), 0, 0, empty) == 1)

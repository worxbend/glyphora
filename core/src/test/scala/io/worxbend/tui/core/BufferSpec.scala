package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class BufferSpec extends AnyFunSuite:

  private def buffer(width: Int, height: Int): Buffer = Buffer(Rect(0, 0, width, height))

  test("a new buffer is all empty cells"):
    val buf = buffer(2, 2)
    assert(buf.get(0, 0) == Cell.Empty)
    assert(buf.get(1, 1) == Cell.Empty)

  test("set then get round-trips inside the area"):
    val buf  = buffer(3, 3)
    val cell = Cell("x", Style.Default.bold)
    buf.set(1, 2, cell)
    assert(buf.get(1, 2) == cell)

  test("writes outside the area are silently clipped"):
    val buf = buffer(2, 2)
    buf.set(5, 5, Cell("x", Style.Default))
    buf.set(-1, 0, Cell("x", Style.Default))
    assert(buf.diff(buffer(2, 2)).isEmpty)

  test("reads outside the area return the empty cell"):
    assert(buffer(2, 2).get(9, 9) == Cell.Empty)

  test("buffer coordinates are absolute, not area-relative"):
    val buf = Buffer(Rect(10, 5, 3, 3))
    buf.set(11, 6, Cell("x", Style.Default))
    assert(buf.get(11, 6).symbol == "x")
    assert(buf.get(1, 1) == Cell.Empty) // outside the offset area

  test("setString writes one narrow character per cell"):
    val buf = buffer(5, 1)
    buf.setString(0, 0, "abc", Style.Default)
    assert(buf.get(0, 0).symbol == "a")
    assert(buf.get(1, 0).symbol == "b")
    assert(buf.get(2, 0).symbol == "c")

  test("setString stores a wide character in its left cell with an empty continuation"):
    val buf = buffer(4, 1)
    buf.setString(0, 0, "你a", Style.Default)
    assert(buf.get(0, 0).symbol == "你")
    assert(buf.get(1, 0) == Cell.Empty)
    assert(buf.get(2, 0).symbol == "a")

  test("overwriting a wide grapheme in place releases the column it reserved"):
    val buf = buffer(4, 1)
    buf.setString(0, 0, "你", Style.Default)
    buf.set(0, 0, Cell("a", Style.Default))
    buf.set(1, 0, Cell("b", Style.Default))
    // had the stale continuation on column 1 survived, the write at 1 would have blanked the 'a' next to it
    assert(buf.get(0, 0).symbol == "a")
    assert(buf.get(1, 0).symbol == "b")

  test("writing the empty cell onto a continuation blanks the wide grapheme that reserved it"):
    // a blank claims its column exactly as any other cell does. The grapheme can no longer draw across a column
    // somebody else has taken, so leaving it behind would show it inside a region the caller had blanked
    val buf = buffer(4, 1)
    buf.setString(0, 0, "你", Style.Default)
    buf.set(1, 0, Cell.Empty)
    assert(buf.get(0, 0) == Cell.Empty)
    assert(!buf.isContinuation(1, 0))
    assert(buf.get(1, 0) == Cell.Empty)

  test("setString drops a wide character that would only half-fit at the right edge"):
    val buf = buffer(3, 1)
    buf.setString(2, 0, "你", Style.Default)
    assert(buf.get(2, 0) == Cell.Empty)

  test("setString clips text past the right edge"):
    val buf = buffer(3, 1)
    buf.setString(0, 0, "abcdef", Style.Default)
    assert(buf.get(2, 0).symbol == "c")

  test("setString keeps a combining mark in its base character's cell"):
    val buf = buffer(3, 1)
    buf.setString(0, 0, "éx", Style.Default)
    assert(buf.get(0, 0).symbol == "é")
    assert(buf.get(1, 0).symbol == "x")

  test("setString skips a leading combining mark with no base"):
    val buf = buffer(3, 1)
    buf.setString(0, 0, "́a", Style.Default)
    assert(buf.get(0, 0).symbol == "a")

  test("reset restores every cell to empty"):
    val buf = buffer(2, 1)
    buf.setString(0, 0, "ab", Style.Default)
    buf.reset()
    assert(buf.get(0, 0) == Cell.Empty)
    assert(buf.get(1, 0) == Cell.Empty)

  test("set blanks a wide grapheme aimed at the last column of a row"):
    // there is no column left to reserve as its continuation, so storing the glyph itself would make the terminal
    // draw two columns where the buffer owns one: the row wraps, and the next frame's diff — believing the column
    // already holds the glyph — never repairs it. The style survives so a background fill reaches the edge.
    val buf = buffer(3, 1)
    buf.set(2, 0, Cell("好", Style.Default.withBg(Color.Blue)))
    assert(buf.get(2, 0) == Cell(" ", Style.Default.withBg(Color.Blue)))

  test("blit lands a wide grapheme at the destination's right edge as a blank"):
    // the guard belongs to the write, not to any one caller: `blit` goes through `set` like everything else
    val source = buffer(2, 1)
    source.setString(0, 0, "好", Style.Default)
    val target = buffer(3, 1)
    target.blit(source, Position(2, 0))
    assert(target.get(2, 0) == Cell.Empty)
    // and so nothing reaches a backend that would draw across a column this buffer does not own
    assert(buffer(3, 1).diff(target).isEmpty)

  test("blit copies a source buffer at an offset with clipping"):
    val source = buffer(3, 1)
    source.setString(0, 0, "abc", Style.Default)
    val target = buffer(4, 2)
    target.blit(source, Position(2, 1))
    assert(target.get(2, 1).symbol == "a")
    assert(target.get(3, 1).symbol == "b") // 'c' clipped at the right edge
    assert(target.get(0, 0) == Cell.Empty)

  test("blit with a region copies only that window"):
    val source = buffer(4, 2)
    source.setString(0, 0, "abcd", Style.Default)
    source.setString(0, 1, "efgh", Style.Default)
    val target = buffer(4, 2)
    target.blit(source, Position(0, 0), Rect(1, 1, 2, 1))
    assert(target.get(0, 0).symbol == "f")
    assert(target.get(1, 0).symbol == "g")
    assert(target.get(2, 0) == Cell.Empty)

  test("diff of identical buffers is empty"):
    val previous = buffer(3, 2)
    val next     = buffer(3, 2)
    assert(previous.diff(next).isEmpty)

  test("diff emits exactly the changed cells with their new content"):
    val previous = buffer(3, 1)
    val next     = buffer(3, 1)
    next.setString(0, 0, "ab", Style.Default)
    val changes  = previous.diff(next).toSeq
    assert(changes == Seq(Position(0, 0) -> Cell("a", Style.Default), Position(1, 0) -> Cell("b", Style.Default)))

  test("diff never emits the continuation cell of a wide character"):
    val previous         = buffer(3, 1)
    previous.setString(1, 0, "x", Style.Default)
    val next             = buffer(3, 1)
    next.setString(0, 0, "你", Style.Default)
    val changedPositions = previous.diff(next).map(_._1).toSeq
    assert(changedPositions == Seq(Position(0, 0)))

  test("emitAll paints every cell of a frame that has no usable predecessor"):
    // what a resize needs. `diff` used to do this itself whenever the two areas disagreed; it now says so instead, and
    // the repaint has its own name — see BufferEmitAllSpec for the contract in full.
    val previous = buffer(2, 1)
    val next     = buffer(3, 1)
    next.setString(0, 0, "abc", Style.Default)
    intercept[IllegalArgumentException](previous.diff(next).size)
    val painted  = Seq.newBuilder[String]
    next.emitAll((_, _, cell) => painted += cell.symbol)
    assert(painted.result() == Seq("a", "b", "c"))

  test("blit blanks wide graphemes cut in half at the window edges"):
    val source = buffer(6, 1)
    source.setString(0, 0, "a你b你", Style.Default) // cells: a 你 · b 你 ·
    val target = buffer(4, 1)
    // window starts at x=2 (the continuation of the first 你) and ends at x=5 (splitting the second 你)
    target.blit(source, Position(0, 0), Rect(2, 0, 3, 1))
    assert(target.get(0, 0) == Cell.Empty) // torn left half dropped
    assert(target.get(1, 0).symbol == "b")
    assert(target.get(2, 0) == Cell.Empty) // wide char whose continuation was cut off dropped

  test("blit trims a region that starts outside the source instead of shifting its content"):
    // an overlay laid out at a negative origin asks for cells the source does not have; clipping must drop those
    // columns, not slide the surviving ones left onto the landing point meant for the missing ones
    val source = buffer(3, 1)
    source.setString(0, 0, "abc", Style.Default)
    val target = buffer(5, 1)
    target.blit(source, Position(0, 0), Rect(-2, 0, 5, 1))
    assert(target.get(0, 0) == Cell.Empty) // the two columns left of the source stay blank
    assert(target.get(1, 0) == Cell.Empty)
    assert(target.get(2, 0).symbol == "a")
    assert(target.get(3, 0).symbol == "b")
    assert(target.get(4, 0).symbol == "c")

  test("a variation selector does not consume a cell of its own"):
    // a lone VS16 reported two columns, so setString claimed a cell plus a continuation for a glyph the terminal
    // never draws and pushed the rest of the row two columns right
    val buf = buffer(4, 1)
    buf.setString(0, 0, 0xfe0f.toChar.toString + "abc", Style.Default)
    assert(buf.get(0, 0).symbol == "a")
    assert(buf.get(1, 0).symbol == "b")
    assert(buf.get(2, 0).symbol == "c")

  test("a CJK ideograph followed by VS15 still claims its continuation cell"):
    // treating 你 + VS15 as one column left no continuation cell: everything right of it rendered one column off
    // for the rest of the row, and the diff engine confirmed the overwrite instead of suppressing it
    val buf = buffer(4, 1)
    buf.setString(0, 0, "你" + 0xfe0e.toChar.toString + "xy", Style.Default)
    assert(buf.get(1, 0) == Cell.Empty) // the continuation cell of the wide glyph
    assert(buf.get(2, 0).symbol == "x")
    assert(buf.get(3, 0).symbol == "y")

  test("a stray ZWJ does not let the following text overflow the buffer"):
    // joining unconditionally made "a ZWJ 你" one column wide, so the characters after it were written on top of
    // the ideograph instead of after it
    val buf = buffer(6, 1)
    buf.setString(0, 0, "a" + 0x200d.toChar.toString + "你XY", Style.Default)
    assert(buf.get(0, 0).symbol.startsWith("a"))
    assert(buf.get(1, 0).symbol == "你")
    assert(buf.get(2, 0) == Cell.Empty) // continuation of 你, not overwritten by X
    assert(buf.get(3, 0).symbol == "X")
    assert(buf.get(4, 0).symbol == "Y")

  test("fill writes the cell across the region and leaves its neighbours untouched"):
    val buf = buffer(4, 3)
    buf.fill(Rect(1, 1, 2, 1), Cell("#", Style.Default.bold))
    assert(buf.get(1, 1) == Cell("#", Style.Default.bold))
    assert(buf.get(2, 1) == Cell("#", Style.Default.bold))
    assert(buf.get(0, 1) == Cell.Empty)
    assert(buf.get(3, 1) == Cell.Empty)
    assert(buf.get(1, 0) == Cell.Empty)
    assert(buf.get(1, 2) == Cell.Empty)

  test("fill clips a region that reaches past the area instead of failing"):
    val buf = buffer(2, 2)
    buf.fill(Rect(-3, -3, 20, 20), Cell("#", Style.Default))
    assert(buf.get(0, 0).symbol == "#")
    assert(buf.get(1, 1).symbol == "#")

  test("fill of an empty region writes nothing"):
    val buf = buffer(2, 2)
    buf.fill(Rect(0, 0, 0, 2), Cell("#", Style.Default))
    buf.fill(Rect(0, 0, 2, 0), Cell("#", Style.Default))
    buf.fill(Rect(9, 9, 2, 2), Cell("#", Style.Default))
    assert(buf.get(0, 0) == Cell.Empty)
    assert(buf.get(1, 1) == Cell.Empty)

  test("fill with a two-column symbol advances by two and reserves each continuation"):
    // stepping one column at a time would paint over the continuation cell the previous write just claimed, which
    // leaves the buffer claiming a wide glyph that no longer owns the column beside it
    val buf = buffer(5, 1)
    buf.fill(Rect(0, 0, 5, 1), Cell("你", Style.Default))
    assert(buf.get(0, 0).symbol == "你")
    assert(buf.get(1, 0) == Cell.Empty)
    assert(buf.get(2, 0).symbol == "你")
    assert(buf.get(3, 0) == Cell.Empty)
    // the odd column at the right edge has no room for a second half, so `set` stores a blank in the same style
    assert(buf.get(4, 0) == Cell(" ", Style.Default))

  test("fill keeps a two-column symbol inside an odd-width region"):
    // the region ends at column 5, but the buffer is wider, so nothing stops a wide glyph starting at column 4 from
    // painting column 5 as well. The column past the region belongs to whoever drew there, not to the fill.
    val buf = buffer(8, 1)
    buf.set(5, 0, Cell("X", Style.Default))
    buf.fill(Rect(0, 0, 5, 1), Cell("你", Style.Default.bold))
    assert(buf.get(0, 0).symbol == "你")
    assert(buf.get(2, 0).symbol == "你")
    // column 4 is the odd one out: a blank carrying the fill's style, never half a glyph
    assert(buf.get(4, 0) == Cell(" ", Style.Default.bold))
    // and the neighbour outside the region is untouched
    assert(buf.get(5, 0) == Cell("X", Style.Default))

  test("Buffer.filled is a new buffer already filled"):
    val cell = Cell("·", Style.Default.dim)
    val buf  = Buffer.filled(Rect(0, 0, 2, 2), cell)
    assert(buf.get(0, 0) == cell)
    assert(buf.get(1, 1) == cell)

  test("setStyle restyles a region without touching its symbols"):
    val buf = buffer(4, 2)
    buf.setString(0, 0, "abcd", Style.Default)
    buf.setStyle(Rect(1, 0, 2, 1), Style.Default.bold)
    assert(buf.get(1, 0) == Cell("b", Style.Default.bold))
    assert(buf.get(2, 0) == Cell("c", Style.Default.bold))
    assert(buf.get(0, 0) == Cell("a", Style.Default))
    assert(buf.get(3, 0) == Cell("d", Style.Default))

  test("setStyle also restyles blank cells, so a background reaches the whole rectangle"):
    val buf = buffer(3, 1)
    buf.setString(0, 0, "a", Style.Default)
    buf.setStyle(Rect(0, 0, 3, 1), Style.Default.withBg(Color.Blue))
    assert(buf.get(1, 0) == Cell(Cell.Empty.symbol, Style.Default.withBg(Color.Blue)))
    assert(buf.get(2, 0).style == Style.Default.withBg(Color.Blue))

  test("setStyle clips to the area and a fully outside region is a no-op"):
    val buf = buffer(2, 1)
    buf.setString(0, 0, "ab", Style.Default)
    buf.setStyle(Rect(1, 0, 10, 1), Style.Default.bold)
    assert(buf.get(0, 0).style == Style.Default)
    assert(buf.get(1, 0).style == Style.Default.bold)
    buf.setStyle(Rect(50, 50, 3, 3), Style.Default.italic)
    assert(buf.get(0, 0).style == Style.Default)

  test("setStyle leaves the continuation cell of a wide grapheme empty"):
    // the terminal paints a wide grapheme across both of its columns from the *left* cell's style, so restyling the
    // right one changes nothing on screen while breaking the invariant that a flagged cell holds Cell.Empty
    val buf = buffer(4, 1)
    buf.setString(0, 0, "你好", Style.Default)
    buf.setStyle(Rect(0, 0, 4, 1), Style.Default.bold)
    assert(buf.get(0, 0) == Cell("你", Style.Default.bold))
    assert(buf.get(1, 0) == Cell.Empty)
    assert(buf.get(2, 0) == Cell("好", Style.Default.bold))
    assert(buf.get(3, 0) == Cell.Empty)
    // and both reservations are still flagged, so the diff still knows which columns it must not paint twice
    assert(buf.isContinuation(1, 0))
    assert(buf.isContinuation(3, 0))

  test("mapStyle derives each new style from the current one and writes nothing when it is unchanged"):
    val buf    = buffer(3, 1)
    buf.setString(0, 0, "a", Style.Default.withFg(Color.Red))
    buf.setString(1, 0, "b", Style.Default.withFg(Color.Green))
    buf.mapStyle(Rect(0, 0, 3, 1))(_.bold)
    assert(buf.get(0, 0).style == Style.Default.withFg(Color.Red).bold)
    assert(buf.get(1, 0).style == Style.Default.withFg(Color.Green).bold)
    assert(buf.get(2, 0).style == Style.Default.bold)
    val before = buf.snapshot
    buf.mapStyle(Rect(0, 0, 3, 1))(identity)
    assert(buf.diff(before).isEmpty)

  test("mapStyle re-runs the transform only when the style changes"):
    // consecutive cells almost always share one style; the cached last input is what keeps a full-frame pass from
    // building ten thousand identical Style values
    val buf   = buffer(4, 1)
    buf.setString(0, 0, "aa", Style.Default.withFg(Color.Red))
    buf.setString(2, 0, "bb", Style.Default.withFg(Color.Blue))
    var calls = 0
    buf.mapStyle(Rect(0, 0, 4, 1)) { style =>
      calls += 1
      style.bold
    }
    assert(calls == 2)
    assert(buf.get(1, 0).style == Style.Default.withFg(Color.Red).bold)
    assert(buf.get(3, 0).style == Style.Default.withFg(Color.Blue).bold)
  test("a bounded write stops at the column budget and reports how far it got"):
    val buf = buffer(10, 1)
    assert(buf.setString(0, 0, "hello", Style.Default, 3) == 3)
    assert((0 until 4).map(x => buf.get(x, 0).symbol) == Seq("h", "e", "l", " "))

  test("a bounded write shorter than its budget reports the text's own width"):
    val buf = buffer(10, 1)
    assert(buf.setString(2, 0, "hi", Style.Default, 6) == 2)

  test("a bounded write never crosses the area's right edge, whatever the budget says"):
    val buf = buffer(4, 1)
    assert(buf.setString(2, 0, "hello", Style.Default, 100) == 2)
    assert(buf.setString(0, 1, "hello", Style.Default, 4) == 0) // the row is outside the area

  test("a bounded write drops a two-column cluster that only half-fits the budget"):
    // splitting the cluster would draw its right half one column past the budget, so it is dropped whole and the
    // answer is one less than the budget — which is the point of returning the advance instead of the budget
    val buf = buffer(10, 1)
    assert(buf.setString(0, 0, "漢字", Style.Default, 3) == 2)
    assert(buf.get(0, 0).symbol == "漢")
    assert(buf.get(2, 0) == Cell.Empty)

  test("a bounded write with a zero or negative budget writes nothing"):
    val buf = buffer(6, 1)
    assert(buf.setString(0, 0, "hi", Style.Default, 0) == 0)
    assert(buf.setString(0, 0, "hi", Style.Default, -5) == 0)
    assert(buf.get(0, 0) == Cell.Empty)

  test("a bounded write counts a combining mark as part of its base character"):
    val buf = buffer(6, 1)
    // "e" + combining acute is one grapheme cluster, so it fills one column and leaves two of the budget
    assert(buf.setString(0, 0, "e" + 0x0301.toChar.toString + "xy", Style.Default, 3) == 3)
    assert(buf.get(0, 0).symbol == "e" + 0x0301.toChar.toString)
    assert(buf.get(2, 0).symbol == "y")

  test("consecutive bounded writes chain by their own answers"):
    // the reason the count is returned at all: laying a row out as segments without measuring any of them twice
    val buf    = buffer(8, 1)
    var column = 0
    Seq("漢", "ab", "cdef").foreach { segment =>
      column += buf.setString(column, 0, segment, Style.Default, 8 - column)
    }
    assert(column == 8)
    assert((0 until 8).map(x => buf.get(x, 0).symbol) == Seq("漢", " ", "a", "b", "c", "d", "e", "f"))

  test("get and set accept a Position as well as a pair of coordinates"):
    val buf = buffer(3, 2)
    buf.set(Position(1, 1), Cell("z", Style.Default))
    assert(buf.get(Position(1, 1)) == Cell("z", Style.Default))
    assert(buf.get(1, 1) == buf.get(Position(1, 1)))
    assert(buf.get(Position(9, 9)) == Cell.Empty)

  test("foreach visits every cell of the grid in row-major order"):
    val buf     = buffer(2, 2)
    buf.setString(0, 0, "ab", Style.Default)
    val visited = Seq.newBuilder[(Int, Int, String)]
    buf.foreach((x, y, cell) => visited += ((x, y, cell.symbol)))
    assert(visited.result() == Seq((0, 0, "a"), (1, 0, "b"), (0, 1, " "), (1, 1, " ")))

  test("foreachIn clips the region to the buffer and visits nothing when they do not overlap"):
    val buf     = buffer(4, 4)
    val visited = Seq.newBuilder[Position]
    buf.foreachIn(Rect(2, 3, 10, 10))((x, y, _) => visited += Position(x, y))
    assert(visited.result() == Seq(Position(2, 3), Position(3, 3)))
    val none    = Seq.newBuilder[Position]
    buf.foreachIn(Rect(20, 20, 2, 2))((x, y, _) => none += Position(x, y))
    assert(none.result().isEmpty)

  test("foreach shows continuation cells as blanks that isContinuation identifies"):
    val buf     = buffer(3, 1)
    buf.setString(0, 0, "漢", Style.Default)
    val symbols = Seq.newBuilder[String]
    buf.foreach((_, _, cell) => symbols += cell.symbol)
    assert(symbols.result() == Seq("漢", " ", " "))
    assert(buf.isContinuation(1, 0))
    assert(!buf.isContinuation(2, 0))
    assert(!buf.isContinuation(-1, 0)) // outside the area

  test("merged grows to the union of both areas and lets the argument win the overlap"):
    val base    = Buffer(Rect(0, 0, 3, 1))
    base.setString(0, 0, "abc", Style.Default)
    val overlay = Buffer(Rect(2, 0, 3, 2))
    overlay.setString(2, 0, "XY", Style.Default.bold)
    val merged  = base.merged(overlay)
    assert(merged.area == Rect(0, 0, 5, 2))
    assert(merged.get(0, 0) == Cell("a", Style.Default))
    assert(merged.get(2, 0) == Cell("X", Style.Default.bold)) // the overlay wins where they overlap
    assert(merged.get(4, 0) == Cell.Empty)                    // covered by the overlay's area but never written
    assert(merged.get(0, 1) == Cell.Empty) // covered by neither input's content

  test("merged leaves both inputs untouched"):
    val base    = Buffer(Rect(0, 0, 2, 1))
    base.setString(0, 0, "ab", Style.Default)
    val overlay = Buffer(Rect(5, 5, 1, 1))
    overlay.set(5, 5, Cell("z", Style.Default))
    val before  = base.snapshot
    val _       = base.merged(overlay)
    assert(base == before)
    assert(base.area == Rect(0, 0, 2, 1))

  test("merged blanks a two-column grapheme cut by the seam rather than drawing half of it"):
    val base    = Buffer(Rect(0, 0, 2, 1))
    base.setString(0, 0, "漢", Style.Default)
    val overlay = Buffer(Rect(1, 0, 2, 1))
    overlay.setString(1, 0, "字", Style.Default)
    val merged  = base.merged(overlay)
    assert(merged.area == Rect(0, 0, 3, 1))
    assert(merged.get(0, 0) == Cell.Empty) // 漢 lost the column 字 claimed
    assert(merged.get(1, 0).symbol == "字")

  test("two buffers holding the same content are equal and hash alike"):
    val one = buffer(6, 2)
    one.setString(0, 0, "hi", Style.Default.bold)
    val two = buffer(6, 2)
    two.setString(0, 0, "hi", Style.Default.bold)
    assert(one == two)
    assert(one.hashCode() == two.hashCode())
    assert(one == one.snapshot)

  test("buffers whose glyphs match but whose styles differ are not equal"):
    // the regression this closes: a symbols-only comparison (what a test that renders both frames to text does)
    // calls these two frames identical, so a widget that lost its highlight passes
    val plain = buffer(6, 1)
    plain.setString(0, 0, "hi", Style.Default)
    val bold  = buffer(6, 1)
    bold.setString(0, 0, "hi", Style.Default.bold)
    assert(plain != bold)

  test("buffers covering different areas are never equal"):
    assert(buffer(6, 1) != buffer(6, 2))
    assert(Buffer(Rect(0, 0, 4, 1)) != Buffer(Rect(1, 0, 4, 1)))

  test("a buffer is not equal to a non-buffer"):
    assert(!buffer(2, 1).equals("Buffer"))

  test("equality distinguishes a wide grapheme's filler from a plain blank"):
    // both buffers hold Cell.Empty at column 1; only one of them has the column reserved as 漢's right half
    val wide  = buffer(4, 1)
    wide.setString(0, 0, "漢", Style.Default)
    val blank = buffer(4, 1)
    blank.set(0, 0, Cell.Empty)
    assert(wide != blank)

  test("the debug dump prints the rows, the style runs and the hidden columns"):
    val buf  = buffer(4, 1)
    buf.setString(0, 0, "漢", Style.Default.withFg(Color.Red))
    val dump = buf.toString
    assert(dump.contains("area=" + Rect(0, 0, 4, 1).toString))
    assert(dump.contains("\"漢   \"")) // the filler and the two untouched columns each print one space
    assert(dump.contains("x: 0, y: 0, Style(fg=Red)"))
    assert(dump.contains("x: 1, y: 0, hidden by a two-column grapheme"))

  test("the debug dump lists style runs, not one line per cell"):
    val buf  = buffer(6, 1)
    buf.setString(0, 0, "ab", Style.Default.bold)
    val runs = buf.toString.linesIterator.filter(_.contains("Style")).toSeq
    // one line opening the bold run at column 0, one closing it at column 2 where the untouched cells begin
    assert(runs.size == 2, runs)
    assert(runs.head.contains("x: 0, y: 0"))
    assert(runs(1).contains("x: 2, y: 0"))

  test("an empty buffer still produces a well-formed dump"):
    val dump = Buffer(Rect(0, 0, 0, 0)).toString
    assert(dump == s"Buffer(area=${Rect(0, 0, 0, 0)}, content=[\n], styles=[\n], hidden=[\n])")

  test("withLines sizes the buffer from the widest line and keeps each span's style"):
    val built = Buffer.withLines(Line.raw("hi"), Line.styled("total", Style.Default.bold))
    assert(built.area == Rect(0, 0, 5, 2))
    assert(built.get(0, 0) == Cell("h", Style.Default))
    assert(built.get(2, 0) == Cell.Empty) // the short line's untouched tail
    assert(built.get(0, 1) == Cell("t", Style.Default.bold))

  test("withLines advances by display columns, so a wide grapheme does not shift the rest of the line"):
    val built = Buffer.withLines(Line(Seq(Span.raw("漢"), Span("ok", Style.Default.withFg(Color.Red)))))
    assert(built.area == Rect(0, 0, 4, 1))
    assert(built.get(0, 0).symbol == "漢")
    assert(built.isContinuation(1, 0))
    assert(built.get(2, 0) == Cell("o", Style.Default.withFg(Color.Red)))

  test("withLines of nothing is an empty buffer"):
    assert(Buffer.withLines().area == Rect(0, 0, 0, 0))

  test("withText builds the same buffer as withLines over the text's lines"):
    val text = Text(Seq(Line.raw("a"), Line.raw("bb")))
    assert(Buffer.withText(text) == Buffer.withLines(Line.raw("a"), Line.raw("bb")))

  test("withText folds in the text's own base style, the way rendering it does"):
    // `Paragraph` lays the text's style under each line's, so a frame built with this helper to compare against a
    // rendered one has to lay it under too — otherwise every expected frame written for a styled text differs from
    // the real one in nothing but the style, which is exactly the difference the comparison exists to catch
    val red      = Style.Default.withFg(Color.Red)
    val expected = Buffer.withText(Text.raw("hi").withStyle(red))
    assert(expected.get(0, 0).style.fg.contains(Color.Red))
    // a span's own style still wins over the text's, as it does everywhere else
    val layered  = Buffer.withText(Text(Seq(Line(Seq(Span("hi", Style.Default.bold)))), style = red))
    assert(layered.get(0, 0).style.fg.contains(Color.Red))
    assert(layered.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("adding the companion did not shadow the buffer constructor"):
    assert(Buffer(Rect(1, 2, 3, 4)).area == Rect(1, 2, 3, 4))

  test("blitting a blank fragment over the right half of a wide grapheme erases the grapheme"):
    // an overlay rendered into its own buffer and composited at an odd column used to leave the CJK character on
    // screen, spilling one column into the rectangle the overlay had declared blank
    val dest = buffer(4, 1)
    dest.setString(0, 0, "漢", Style.Default)
    val src  = buffer(2, 1)
    dest.blit(src, Position(1, 0))
    assert(dest.get(0, 0) == Cell.Empty)
    assert(!dest.isContinuation(1, 0))
    assert(dest.get(1, 0) == Cell.Empty)

  test("filling with the default blank clears a wide grapheme the region's left edge cuts in half"):
    // `fill` is the one owner of "blank a box"; filling with a coloured style already cleared the pair, and only the
    // default blank — the cell every "clear this box" caller passes — was silently refused
    val buf = buffer(4, 1)
    buf.setString(0, 0, "漢", Style.Default)
    buf.fill(Rect(1, 0, 3, 1), Cell.Empty)
    assert(buf.get(0, 0) == Cell.Empty)
    assert(!buf.isContinuation(1, 0))

  test("setString accepts a column budget too large to add to x without overflowing"):
    // Int.MaxValue is the natural spelling for "no column limit" (LayoutSolver already uses it for an uncapped
    // Constraint.Max); `x + maxWidth` wrapped negative and the write silently stopped before it started
    val buf     = buffer(10, 1)
    val written = buf.setString(1, 0, "hi", Style.Default, Int.MaxValue)
    assert(written == 2)
    assert(buf.get(1, 0).symbol == "h")
    assert(buf.get(2, 0).symbol == "i")

  test("setLine does not promote a later span into the column a half-fitting wide cluster gave up"):
    // the loop only stopped when the budget was spent, so a leading span that could draw nothing was skipped and the
    // next span was written at its column — the row then showed a character from later in the line as if it were first
    val buf     = buffer(10, 1)
    val written = buf.setLine(0, 0, Line(Seq(Span.raw("漢"), Span.raw("x"))), 1)
    assert(written == 0)
    assert(buf.get(0, 0) == Cell.Empty)

  test("setLine stops at the span the budget cut short rather than drawing the one after it"):
    val buf     = buffer(10, 1)
    val written = buf.setLine(0, 0, Line(Seq(Span.raw("a漢"), Span.raw("x"))), 2)
    assert(written == 1)
    assert(buf.get(0, 0).symbol == "a")
    assert(buf.get(1, 0) == Cell.Empty)

  test("set stores an empty symbol as a one-column blank in the cell's style"):
    // `Cell.isBlank` already says an empty symbol means blank. Stored verbatim it would be a zero-width cell the
    // encoder never paints — the terminal keeps the old glyph while the buffer claims the column is blank
    val buf = buffer(3, 1)
    buf.setString(0, 0, "abc", Style.Default)
    val red = Style.Default.withBg(Color.Red)
    buf.set(1, 0, Cell("", red))
    assert(buf.get(1, 0) == Cell(" ", red))

  test("fill with an empty symbol paints the region as styled blanks"):
    val buf = buffer(3, 1)
    val red = Style.Default.withBg(Color.Red)
    buf.fill(Rect(0, 0, 3, 1), Cell("", red))
    assert((0 until 3).forall(x => buf.get(x, 0) == Cell(" ", red)))

package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Line, Span, Style}

/** Lays a GFM table out to a width: the one owner of column measurement, shrinking and cell overflow for [[Markdown]].
  *
  * Columns are separated by ` │ ` and the header from the body by a `─┼─` rule, both in `tableBorder`. Every width is
  * measured through `CharWidth` (via `Line.width`), so a CJK or emoji cell takes the columns it draws in. When the
  * natural widths do not fit, the currently widest column gives up a column at a time until the table fits or every
  * column is down to one; each produced line is then at most `width` columns, so the paragraph that draws the document
  * never re-wraps a table row. Pure functions over the values passed in.
  */
private[widgets] object MarkdownTable:

  private val Separator = " │ "

  def lines(
      header: Seq[Line],
      alignments: Seq[Alignment],
      rows: Seq[Seq[Line]],
      width: Int,
      theme: MarkdownTheme,
      overflow: TableOverflow,
  ): Seq[Line] =
    val columns = header.size
    if columns == 0 || width <= 0 then Seq.empty
    else
      val all      = header +: rows
      val natural  = (0 until columns).map(column => math.max(1, all.map(_(column).width).max))
      val budget   = width - Separator.length * (columns - 1)
      val widths   = shrunk(natural, budget)
      val border   = theme.tableBorder
      val styled   = header.map(_.under(theme.tableHeader))
      val rule     = Line(Seq(Span(widths.map("─" * _).mkString("─┼─"), border)))
      val rendered = tableRow(styled, widths, alignments, border, overflow) ++ Seq(rule) ++
        rows.flatMap(row => tableRow(row, widths, alignments, border, overflow))
      rendered.map(line => truncated(line, width, ellipsis = false))

  /** Takes a column from the widest column until the widths fit `budget` or every column is one wide. */
  private def shrunk(natural: IndexedSeq[Int], budget: Int): IndexedSeq[Int] =
    val widths = natural.toArray
    var total  = widths.sum
    while total > budget && widths.exists(_ > 1) do
      val widest = widths.indexOf(widths.max)
      widths(widest) -= 1
      total -= 1
    widths.toIndexedSeq

  /** One source row as however many screen rows its tallest wrapped cell needs. */
  private def tableRow(
      cells: Seq[Line],
      widths: IndexedSeq[Int],
      alignments: Seq[Alignment],
      border: Style,
      overflow: TableOverflow,
  ): Seq[Line] =
    val fitted = cells.zip(widths).map { (cell, columnWidth) =>
      overflow match
        case TableOverflow.Wrap     => Paragraph.wrapLine(cell, columnWidth)
        case TableOverflow.Truncate => Seq(truncated(cell, columnWidth, ellipsis = true))
    }
    val height = fitted.map(_.size).max
    (0 until height).map { row =>
      val parts = fitted.indices.map { column =>
        val line = fitted(column).lift(row).getOrElse(Line(Seq.empty))
        padded(line, widths(column), alignments.lift(column).getOrElse(Alignment.Left))
      }
      Line(parts.reduceLeft((left, right) => left ++ Line(Seq(Span(Separator, border))) ++ right).spans)
    }

  private def padded(line: Line, columnWidth: Int, alignment: Alignment): Line =
    val extra  = math.max(0, columnWidth - line.width)
    val before = alignment match
      case Alignment.Left   => 0
      case Alignment.Center => extra / 2
      case Alignment.Right  => extra
    val after  = extra - before
    Line(
      (Option.when(before > 0)(Span.raw(" " * before)) ++ line.spans ++ Option.when(after > 0)(
        Span.raw(" " * after)
      )).toSeq
    )

  /** `line` cut to `width` columns, whole grapheme clusters only, ending in `…` when `ellipsis` is set. */
  private[widgets] def truncated(line: Line, width: Int, ellipsis: Boolean): Line =
    if line.width <= width then line
    else if width <= 0 then Line(Seq.empty)
    else
      val budget = if ellipsis then width - 1 else width
      val spans  = Seq.newBuilder[Span]
      var used   = 0
      var last   = Style.Default
      val kept   = line.styledGraphemes(Style.Default).takeWhile { grapheme =>
        val fits = used + grapheme.width <= budget
        if fits then used += grapheme.width
        fits
      }
      // a cut that lands just after a blank would leave `one …`; the ellipsis belongs against the last word
      val words  = kept.toVector
      val shown  = if ellipsis then words.reverse.dropWhile(_.cluster.isBlank).reverse else words
      shown.foreach { grapheme =>
        spans += Span(grapheme.cluster, grapheme.style)
        last = grapheme.style
      }
      val cut    = spans.result()
      Line(if ellipsis then cut :+ Span("…", last) else cut)

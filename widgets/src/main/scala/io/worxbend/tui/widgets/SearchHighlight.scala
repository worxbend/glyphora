package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Color, Rect, Style}

import java.text.Normalizer
import java.util.Locale

/** The one search matcher and highlighter behind every searched view — [[Log]], [[ScrollView]], [[Searchable]] and
  * [[TextAreaState]]'s incremental search.
  *
  * Matching works on grapheme clusters, never on `char`s: a needle of `n` clusters matches `n` consecutive clusters of
  * a row, compared after NFC normalisation (so `é` matches `e` + combining acute) and, unless the query holds a capital
  * letter, after lower-casing. Matches do not overlap. Columns come from [[CharWidth]], so a match over wide CJK text
  * or an emoji spans the columns the terminal draws.
  */
private[widgets] object SearchHighlight:

  /** The highlight of a match that is not current. */
  val DefaultMatchStyle: Style = Style.of(Color.Black, Color.Yellow)

  /** The highlight of the current match. */
  val DefaultCurrentStyle: Style = Style.of(Color.Black, Color.BrightRed).bold

  /** One drawn cluster of a row: where it starts and how many columns it covers. */
  final case class Glyph(cluster: String, column: Int, width: Int)

  /** The non-overlapping `[start, end)` cluster ranges of `clusters` that match `query`. */
  def clusterMatches(clusters: IndexedSeq[String], query: String): Vector[(Int, Int)] =
    val sensitive = query.exists(_.isUpper)
    val needle    = needleOf(query, sensitive)
    if needle.isEmpty then Vector.empty
    else
      val folded = clusters.map(fold(_, sensitive))
      val found  = Vector.newBuilder[(Int, Int)]
      var start  = 0
      while start + needle.size <= folded.size do
        if needle.indices.forall(offset => folded(start + offset) == needle(offset)) then
          found += ((start, start + needle.size))
          start += needle.size
        else start += 1
      found.result()

  /** Every match of `query` over `rows`, row `i` of the result being row `i` of the input. */
  def find(rows: IndexedSeq[IndexedSeq[Glyph]], query: String): Vector[SearchMatch] =
    if query.isEmpty then Vector.empty
    else
      rows.indices.iterator.flatMap { row =>
        val glyphs = rows(row)
        clusterMatches(glyphs.map(_.cluster), query).iterator.map { (from, until) =>
          val first = glyphs(from)
          val last  = glyphs(until - 1)
          SearchMatch(row, first.column, last.column + last.width - first.column)
        }
      }.toVector

  /** The clusters `text` draws as, with their columns — zero-width clusters are not drawn, so they take no column. */
  def glyphsOf(text: String): IndexedSeq[Glyph] =
    val glyphs = IndexedSeq.newBuilder[Glyph]
    var column = 0
    CharWidth.graphemeClusters(CharWidth.withoutControls(text)).foreach { cluster =>
      val width = CharWidth.of(cluster)
      if width > 0 then
        glyphs += Glyph(cluster, column, width)
        column += width
    }
    glyphs.result()

  /** The rows of `region` as they were drawn into `buffer`, columns counted from `region.x`; rows outside the buffer
    * come back empty.
    */
  def glyphsIn(buffer: Buffer, region: Rect): IndexedSeq[IndexedSeq[Glyph]] =
    val drawn = region.intersection(buffer.area)
    (region.y until region.bottom).map { y =>
      if y < drawn.y || y >= drawn.bottom then IndexedSeq.empty
      else
        (drawn.x until drawn.right).iterator
          .filterNot(x => buffer.isContinuation(x, y))
          .map { x =>
            val symbol = buffer.get(x, y).symbol
            Glyph(symbol, x - region.x, math.max(1, CharWidth.of(symbol)))
          }
          .toIndexedSeq
    }

  /** Patches the match styles over every match of `state`, a match at content `(row, column)` landing at
    * `(originX + rowShift(row) + column, originY + row)`; nothing is painted outside `clip`. `rowShift` is how far a
    * row's own alignment moved it right of `originX` — zero for left-aligned content — and is asked only about rows
    * that land inside `clip`.
    */
  def paint(
      buffer: Buffer,
      clip: Rect,
      originX: Int,
      originY: Int,
      state: SearchState,
      matchStyle: Style,
      currentStyle: Style,
      rowShift: Int => Int = _ => 0,
  ): Unit =
    val current = state.current
    state.matches.iterator.zipWithIndex.foreach { (hit, index) =>
      val y      = originY + hit.row
      val region =
        if y < clip.y || y >= clip.bottom then Rect(0, 0, 0, 0)
        else Rect(originX + rowShift(hit.row) + hit.column, y, hit.width, 1).intersection(clip)
      if !region.isEmpty then
        val style = if current.contains(index) then currentStyle else matchStyle
        buffer.mapStyle(region)(_.patch(style))
    }

  private def needleOf(query: String, sensitive: Boolean): Vector[String] =
    CharWidth.graphemeClusters(Normalizer.normalize(query, Normalizer.Form.NFC)).map(fold(_, sensitive)).toVector

  private def fold(cluster: String, sensitive: Boolean): String =
    val composed = Normalizer.normalize(cluster, Normalizer.Form.NFC)
    if sensitive then composed else composed.toLowerCase(Locale.ROOT)

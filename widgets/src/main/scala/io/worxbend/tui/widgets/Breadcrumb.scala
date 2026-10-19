package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, Widget}

/** A one-row navigation trail — `Home › Projects › glyphora` — with the last item, the place the user is, drawn in
  * `currentStyle`.
  *
  * When the trail is wider than its area the middle items fold into a single `ellipsis`, earliest first, so the first
  * item (where the trail starts) and the items nearest the end (where the user is) stay readable for as long as they
  * can: `Home › … › widgets › Breadcrumb`, then `Home › … › Breadcrumb`. Narrower still the first item goes too (`… ›
  * Breadcrumb`), and finally the last item alone is clipped by display width with a trailing ellipsis. Every width is
  * measured through `CharWidth`, so a CJK label takes two columns per character.
  *
  * [[segments]] and [[itemAt]] replay the same fold, so a caller resolving a click lands on exactly the item drawn
  * under the pointer. Stateless and immutable; render-thread use only, like every widget.
  */
final case class Breadcrumb(
    items: Seq[String],
    separator: String = " › ",
    style: Style = Style.Default,
    currentStyle: Style = Style.Default.bold,
    separatorStyle: Style = Style.Default.dim,
    ellipsis: String = "…",
) extends Widget
    with Measured:

  /** The whole trail's width, unfolded; one row at any width. */
  override def widthAt(height: Int): Option[Int] =
    val _ = height
    Some(widthOf(items.indices.map(Some(_))))

  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(1)

  /** What is drawn at `width` columns, left to right: each visible item (`Some(index)`) or the fold (`None`), with the
    * column it starts at relative to the area and the columns it takes. Separators lie between consecutive segments.
    */
  def segments(width: Int): Seq[Breadcrumb.Segment] =
    var x = 0
    folded(math.max(0, width)).zipWithIndex.map { case (item, position) =>
      if position > 0 then x += CharWidth.of(separator)
      val label = labelOf(item, width)
      val start = x
      x += CharWidth.of(label)
      Breadcrumb.Segment(item, start, CharWidth.of(label))
    }

  /** The index of the item drawn at column `x` (relative to the area) of a `width`-column trail; `None` over a
    * separator, the fold or blank space.
    */
  def itemAt(width: Int, x: Int): Option[Int] =
    segments(width).find(segment => x >= segment.x && x < segment.x + segment.width).flatMap(_.item)

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val y = area.y
      segments(area.width).zipWithIndex.foreach { case (segment, position) =>
        if position > 0 then
          val sepX = area.x + segment.x - CharWidth.of(separator)
          buffer.setString(sepX, y, CharWidth.substringByWidth(separator, area.right - sepX), separatorStyle)
        val label     = labelOf(segment.item, area.width)
        val itemStyle =
          if segment.item.contains(items.size - 1) then style.patch(currentStyle)
          else if segment.item.isEmpty then style.patch(separatorStyle)
          else style
        val x         = area.x + segment.x
        buffer.setString(x, y, CharWidth.substringByWidth(label, area.right - x), itemStyle)
      }

  /** The label a segment draws: the item itself, or the fold; a lone last item narrower than its label is clipped. */
  private def labelOf(item: Option[Int], width: Int): String =
    item.fold(ellipsis) { index =>
      val label = items(index)
      if CharWidth.of(label) <= width then label
      else
        val room = math.max(0, width - CharWidth.of(ellipsis))
        CharWidth.substringByWidth(label, room) + ellipsis
    }

  /** The items shown at `width`: the whole trail when it fits, else first + fold + the longest tail that fits, else
    * fold + last, else the last item alone (clipped by [[labelOf]]).
    */
  private def folded(width: Int): Seq[Option[Int]] =
    val all = items.indices.map(Some(_))
    if items.size <= 1 || widthOf(all) <= width then all
    else
      val last   = items.size - 1
      val tails  = (2 to last).map(from => Some(0) +: None +: (from to last).map(Some(_)))
      val nested = tails.find(widthOf(_) <= width)
      nested.getOrElse {
        val bare = Seq(None, Some(last))
        if widthOf(bare) <= width then bare else Seq(Some(last))
      }

  private def widthOf(shown: Seq[Option[Int]]): Int =
    val labels = shown.map(_.fold(CharWidth.of(ellipsis))(index => CharWidth.of(items(index))))
    labels.sum + CharWidth.of(separator) * math.max(0, shown.size - 1)

object Breadcrumb:

  /** One drawn piece of a trail: the item it shows (`None` for the fold), its first column and its width. */
  final case class Segment(item: Option[Int], x: Int, width: Int)

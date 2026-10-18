package io.worxbend.tui.core

/** Where a segment sits on the axis a [[Layout]] does *not* split — across a column, or up and down a row.
  *
  * A layout decides each segment's extent along its own [[Direction]]; across the other axis every segment used to span
  * the whole area, so a button in a column was always as wide as the column. `Stretch` keeps that. The other three
  * shrink the segment to its content's measured cross size and put it at the near edge, the middle, or the far edge.
  *
  * The cross size comes from the content — a [[Measured]] widget's `widthAt(height)` in a column, `heightAt(width)` in
  * a row — and when the content cannot say (`None`) every mode falls back to `Stretch`, for the same reason `Measured`
  * forbids reading `None` as zero: an unmeasurable child drawn at zero width would simply vanish.
  */
enum CrossAlign:
  case Stretch, Start, Center, End

  /** `segment` shrunk across `direction`'s other axis to `crossSize` and placed by this mode; `segment` unchanged for
    * `Stretch` or when `crossSize` is `None`. A measured size larger than the segment is clipped to it, so the result
    * is always inside `segment`.
    */
  def place(segment: Rect, direction: Direction, crossSize: Option[Int]): Rect =
    (this, crossSize) match
      case (Stretch, _) | (_, None) => segment
      case (_, Some(measured))      =>
        direction match
          case Direction.Vertical   =>
            val width = math.max(0, math.min(measured, segment.width))
            Rect(segment.x + offset(segment.width, width), segment.y, width, segment.height)
          case Direction.Horizontal =>
            val height = math.max(0, math.min(measured, segment.height))
            Rect(segment.x, segment.y + offset(segment.height, height), segment.width, height)

  /** How far from the near edge a `size`-cell segment starts inside `extent` cells under this mode. */
  private def offset(extent: Int, size: Int): Int =
    this match
      case Stretch | Start => 0
      case Center          => (extent - size) / 2
      case End             => extent - size

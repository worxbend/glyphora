package io.worxbend.tui.widgets

import io.worxbend.tui.core.{CharWidth, Constraint, Rect}

/** Whether a legend of a given size is allowed to take room from the plot it is drawn over.
  *
  * A key is only worth drawing while the data underneath it can still be read. Each axis carries a
  * [[io.worxbend.tui.core.Constraint]] that the legend's own demand on that axis has to satisfy; if either axis fails,
  * the caller drops the legend entirely, so a small pane degrades to a plain plot rather than to a key with no room
  * left for data.
  *
  * The rule lives here rather than inside one widget because more than one chart wants it and, like [[BlockLadder]], it
  * is the kind of arithmetic that is only worth correcting in one place.
  *
  * These are pure functions holding no state; a caller runs them on the render thread and inherits no constraint of its
  * own.
  */
private[widgets] object LegendFit:

  /** The width the legend needs: the widest entry, measured in terminal columns, plus `padding` extra cells.
    *
    * Measuring with [[io.worxbend.tui.core.CharWidth]] rather than `String.length` is what makes the answer right for a
    * name written in CJK characters or emoji, where one character occupies two columns.
    */
  def width(entries: Seq[String], padding: Int): Int =
    entries.map(CharWidth.of).maxOption.getOrElse(0) + padding

  /** Whether a `legendWidth` by `legendHeight` legend satisfies both `constraints` inside `area`.
    *
    * `constraints.horizontal` limits how many of the area's columns the key may claim, `constraints.vertical` how many
    * of its rows. A legend of zero width or zero height never fits — there is nothing to draw.
    */
  def fits(area: Rect, legendWidth: Int, legendHeight: Int, constraints: LegendConstraints): Boolean =
    legendWidth > 0 && legendHeight > 0 &&
      legendWidth <= budget(constraints.horizontal, area.width) &&
      legendHeight <= budget(constraints.vertical, area.height)

  /** How many of `total` cells a constraint permits the legend to use.
    *
    * `Min`, `Fill` and `Auto` describe a floor, a share of leftover space and the content's own size rather than a
    * ceiling, so as a limit on a legend they mean "no limit" and permit the whole extent. A `Bounded` permits its
    * base's budget held under its ceiling.
    */
  private def budget(constraint: Constraint, total: Int): Int = constraint match
    case Constraint.Length(cells)                                 => math.min(cells, total)
    case Constraint.Percentage(pct)                               => total * pct / 100
    case Constraint.Ratio(numerator, denominator)                 =>
      if denominator == 0 then 0 else total * numerator / denominator
    case Constraint.Max(cells)                                    => math.min(cells, total)
    case Constraint.Min(_) | Constraint.Fill(_) | Constraint.Auto => total
    case Constraint.Bounded(_, max, base)                         => math.min(max, budget(base, total))

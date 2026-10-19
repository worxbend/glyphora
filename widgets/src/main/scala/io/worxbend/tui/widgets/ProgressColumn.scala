package io.worxbend.tui.widgets

import io.worxbend.tui.core.DurationText

import java.util.Locale

/** One column of a [[MultiProgress]] row: what it reads off a [[ProgressTask]] and how it prints it.
  *
  * Every column except [[Bar]] is text, measured with `CharWidth` and padded to the widest value any row of the table
  * produces, so a column lines up down the table. Numeric columns are right-aligned, [[Description]] is left-aligned. A
  * value that cannot be known yet — an ETA before any progress, a percentage of an unknown total — prints a placeholder
  * (`-`, `--:--`) rather than a zero, because a zero is a claim.
  */
enum ProgressColumn:

  /** The task's label, left-aligned. */
  case Description

  /** The bar: `width` cells, or — with `None` — an equal share of what the text columns leave. */
  case Bar(width: Option[Int] = None)

  /** `completed / total` as a whole percentage, `" 40%"`. */
  case Percentage

  /** Estimated time left at the average rate, `mm:ss`. */
  case Eta

  /** Average units per second: `"12.5 it/s"`, or with `bytes` a binary-prefixed byte rate, `"1.2 MiB/s"`. */
  case Rate(bytes: Boolean = false)

  /** Completed over total as byte sizes, `"1.0 KiB/4.0 KiB"`. */
  case Bytes

  /** Time since the task started, frozen when it finished, `mm:ss`. */
  case Elapsed

  /** Units still to do, `"3072 left"`. */
  case Remaining

  /** A spinner that turns while the task runs (driven by its elapsed time) and becomes `done` once it finishes. */
  case SpinnerColumn(preset: SpinnerPreset = SpinnerPreset.Dots, done: String = "✓")

  /** Whether the column's text is right-aligned in its slot. */
  private[widgets] def alignsRight: Boolean =
    this match
      case Description | _: SpinnerColumn | _: Bar => false
      case _                                       => true

  /** The narrowest the column's slot may be, so a percentage does not widen the table as it climbs to `100%`. */
  private[widgets] def minWidth: Int =
    this match
      case Percentage => 4
      case _          => 0

  /** The column's text for `task`; empty for [[Bar]], which draws instead of printing. */
  private[widgets] def textFor(task: ProgressTask): String =
    this match
      case Description            => task.label
      case _: Bar                 => ""
      case Percentage             =>
        task.fraction.fold("-")(f => String.format(Locale.ROOT, "%d%%", (Fraction.clamped(f) * 100).toInt))
      case Eta                    => task.eta.fold("--:--")(DurationText.clock)
      case Rate(bytes)            =>
        if bytes then task.rate.fold("- B/s")(r => s"${ProgressColumn.byteSize(r)}/s")
        else task.rate.fold("- it/s")(r => String.format(Locale.ROOT, "%.1f it/s", r))
      case Bytes                  =>
        val total = task.total.fold("?")(t => ProgressColumn.byteSize(t.toDouble))
        s"${ProgressColumn.byteSize(task.completed.toDouble)}/$total"
      case Elapsed                => DurationText.clock(task.elapsed)
      case Remaining              => task.remaining.fold("-")(left => s"$left left")
      case SpinnerColumn(p, done) => if task.isFinished then done else p.frameAt(task.elapsed)

object ProgressColumn:

  /** Rich's default row: label, bar, percentage, ETA. */
  val Default: Seq[ProgressColumn] = Seq(Description, Bar(), Percentage, Eta)

  private val Units: Vector[String] = Vector("B", "KiB", "MiB", "GiB", "TiB")

  /** `bytes` with a binary prefix: whole bytes under 1 KiB, one decimal from there up. */
  private[widgets] def byteSize(bytes: Double): String =
    if bytes < 1024 then String.format(Locale.ROOT, "%d B", bytes.toLong)
    else
      var value = bytes
      var unit  = 0
      while value >= 1024 && unit < Units.size - 1 do
        value /= 1024
        unit += 1
      String.format(Locale.ROOT, "%.1f %s", value, Units(unit))

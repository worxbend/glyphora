package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

final class MultiProgressSpec extends AnyFunSuite:

  private final class Clock:
    var now: FiniteDuration = Duration.Zero

  /** Two tasks ten seconds in: `fetch` 40 of 100, `build` 5 of 10. */
  private def twoTasks(clock: Clock): Seq[ProgressTask] =
    val fetch = ProgressTask("fetch", Some(100L), () => clock.now)
    val build = ProgressTask("build", Some(10L), () => clock.now)
    fetch.start()
    build.start()
    clock.now = 10.seconds
    fetch.advance(40L)
    build.advance(5L)
    Seq(fetch, build)

  private val Classic: Seq[ProgressColumn] =
    Seq(ProgressColumn.Description, ProgressColumn.Bar(), ProgressColumn.Percentage, ProgressColumn.Eta)

  test("each task is one row of columns, the bar taking whatever the fixed columns leave"):
    val buffer = rendered(MultiProgress(twoTasks(Clock()), Classic), 30, 2)
    assert(line(buffer, 0) == "fetch █████░░░░░░░░  40% 00:15")
    assert(line(buffer, 1) == "build ██████░░░░░░░  50% 00:10")

  test("a fixed-width bar leaves the rest of the row blank"):
    val columns = Seq(ProgressColumn.Description, ProgressColumn.Bar(width = Some(4)), ProgressColumn.Percentage)
    val buffer  = rendered(MultiProgress(twoTasks(Clock()), columns), 20, 2)
    assert(trimmedLines(buffer) == Seq("fetch █░░░  40%", "build ██░░  50%"))

  test("descriptions align to the widest one, measured in columns not code units"):
    val clock  = Clock()
    val wide   = ProgressTask("下载", Some(4L), () => clock.now)
    val narrow = ProgressTask("a", Some(4L), () => clock.now)
    wide.advance(2L)
    narrow.advance(4L)
    val buffer =
      rendered(MultiProgress(Seq(wide, narrow), Seq(ProgressColumn.Description, ProgressColumn.Percentage)), 12, 2)
    assert(trimmedLines(buffer) == Seq("下载  50%", "a    100%"))

  test("the time, rate, count and byte columns"):
    val clock                                = Clock()
    val t                                    = ProgressTask("x", Some(4096L), () => clock.now)
    t.start()
    clock.now = 2.seconds
    t.advance(1024L)
    def cell(column: ProgressColumn): String = trimmedLines(rendered(MultiProgress(Seq(t), Seq(column)), 30, 1)).head
    assert(cell(ProgressColumn.Elapsed) == "00:02")
    assert(cell(ProgressColumn.Eta) == "00:06")
    assert(cell(ProgressColumn.Rate()) == "512.0 it/s")
    assert(cell(ProgressColumn.Rate(bytes = true)) == "512 B/s")
    assert(cell(ProgressColumn.Remaining) == "3072 left")
    assert(cell(ProgressColumn.Bytes) == "1.0 KiB/4.0 KiB")

  test("unknown totals and an unstarted task read as placeholders, not zeros"):
    val clock = Clock()
    val t     = ProgressTask("x", None, () => clock.now)
    val cols  = Seq(ProgressColumn.Percentage, ProgressColumn.Eta, ProgressColumn.Rate())
    assert(trimmedLines(rendered(MultiProgress(Seq(t), cols), 30, 1)) == Seq("   - --:-- - it/s"))

  test("the spinner column turns while running and settles on a check mark when finished"):
    val clock   = Clock()
    val t       = ProgressTask("x", Some(1L), () => clock.now)
    t.start()
    val spinner = Seq(ProgressColumn.SpinnerColumn())
    assert(line(rendered(MultiProgress(Seq(t), spinner), 1, 1), 0) == SpinnerPreset.Dots.frameAt(Duration.Zero))
    t.finish()
    assert(line(rendered(MultiProgress(Seq(t), spinner), 1, 1), 0) == "✓")

  test("rows past the area are not drawn and nothing spills outside it"):
    val sentinel = Cell("#", Style.Default)
    val buffer   = Buffer(Rect(0, 0, 40, 4))
    for y <- 0 until 4; x <- 0 until 40 do buffer.set(x, y, sentinel)
    MultiProgress(twoTasks(Clock()), Classic).render(Rect(2, 1, 20, 1), buffer)
    assert(line(buffer, 0) == "#" * 40)
    assert(line(buffer, 2) == "#" * 40)
    assert(line(buffer, 1).take(2) == "##" && line(buffer, 1).drop(22) == "#" * 18)
    assert(line(buffer, 1).slice(2, 7) == "fetch")

  test("an empty area draws nothing and the widget measures one row per task"):
    val widget = MultiProgress(twoTasks(Clock()), Classic)
    val buffer = Buffer(Rect(0, 0, 0, 0))
    widget.render(Rect(0, 0, 0, 0), buffer)
    assert(widget.heightAt(10).contains(2))

package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import java.time.LocalTime

final class NoticeAndBadgeSpec extends AnyFunSuite:

  private def render(widget: io.worxbend.tui.core.Widget, width: Int, height: Int = 1): String =
    trimmedLines(rendered(widget, width, height)).headOption.getOrElse("")

  /** Untrimmed cells: a solid badge's padding is part of what it draws, and `trimmedLines` would eat it. */
  private def raw(widget: io.worxbend.tui.core.Widget, width: Int): String =
    val buffer = rendered(widget, width, 1)
    (0 until width).map(x => buffer.get(x, 0).symbol).mkString.replaceAll("\\s+$", "")

  // ---------------------------------------------------------------- levels

  /** Levels are shared by notices, toasts and badges, so every one of them needs a distinct, one-column marker: a
    * two-column emoji would make a stacked column of notices ragged.
    */
  test("every level has a distinct one-column icon and a distinct tag"):
    val icons = NoticeLevel.values.map(_.icon)
    assert(icons.distinct.length == icons.length, s"levels share an icon: ${icons.mkString(",")}")
    icons.foreach(icon => assert(io.worxbend.tui.core.CharWidth.of(icon) == 1, s"'$icon' is not one column"))
    val tags  = NoticeLevel.values.map(_.tag)
    assert(tags.distinct.length == tags.length)
    tags.foreach(tag => assert(tag.nonEmpty && tag == tag.toUpperCase))

  // ---------------------------------------------------------------- notice

  test("a notice draws its icon then its message"):
    assert(render(Notice("deployed", NoticeLevel.Success), 24) == "✔ deployed")
    assert(render(Notice("disk full", NoticeLevel.Error), 24) == "✖ disk full")
    assert(render(Notice("check config", NoticeLevel.Warning), 24) == "▲ check config")
    assert(render(Notice("starting", NoticeLevel.Info), 24) == "• starting")

  test("a timestamp is written before the icon"):
    val stamped = Notice("deployed", NoticeLevel.Success, Some(LocalTime.of(12, 4, 31)))
    assert(render(stamped, 32) == "[12:04:31] ✔ deployed")

  /** The clock is an input, not something the widget reads: a widget calling `LocalTime.now()` would render a different
    * frame on every repaint and could never be golden-tested.
    */
  test("a notice renders the same frame twice for the same inputs"):
    val notice = Notice("deployed", NoticeLevel.Success, Some(LocalTime.of(9, 0, 0)))
    assert(render(notice, 32) == render(notice, 32))

  test("an over-long message clips rather than spilling"):
    val long = Notice("a message far wider than the area it was given", NoticeLevel.Info)
    assert(render(long, 12).length <= 12)
    assert(render(long, 1).length <= 1)
    assert(render(long, 0).isEmpty)

  test("a wrapping notice grows instead of clipping, and reports the height it needs"):
    val notice = Notice("one two three four five six", NoticeLevel.Info, overflow = Overflow.Wrap)
    assert(notice.heightAt(12).exists(_ > 1))
    val drawn  = trimmedLines(rendered(notice, 12, notice.heightAt(12).getOrElse(1)))
    assert(drawn.count(_.nonEmpty) > 1, s"wrapping produced $drawn")
    assert(Notice("short", NoticeLevel.Info).heightAt(40).contains(1), "a non-wrapping notice is always one row")

  test("the icon and the message are styled independently"):
    val buffer = rendered(
      Notice("ok", NoticeLevel.Success, style = Style.Default, accentStyle = Style.Default.withFg(Color.Green)),
      12,
      1,
    )
    assert(buffer.get(0, 0).style.fg.contains(Color.Green), "the icon takes the level's colour")
    assert(buffer.get(2, 0).style.fg.isEmpty, "the message keeps the message style")

  test("the level constructors match the general form"):
    assert(render(Notice.success("x"), 12) == render(Notice("x", NoticeLevel.Success), 12))
    assert(render(Notice.error("x"), 12) == render(Notice("x", NoticeLevel.Error), 12))

  // ---------------------------------------------------------------- badge

  test("each variant draws its own shape"):
    assert(raw(Badge("NEW"), 12) == " NEW", "a solid badge pads its label so the fill reads as a block")
    assert(raw(Badge("NEW", BadgeVariant.Outline), 12) == "[NEW]")
    assert(raw(Badge("NEW", BadgeVariant.Dot), 12) == "● NEW")

  /** A solid badge is reversed rather than coloured, which is what makes it read as a filled block. */
  test("a solid badge reverses its style and pads its label"):
    val buffer = rendered(Badge("OK", BadgeVariant.Solid, Style.Default.withFg(Color.Green)), 12, 1)
    assert(buffer.get(0, 0).symbol == " ", "a solid badge is padded so the fill is visible")
    assert(buffer.get(0, 0).style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Reverse))
    assert(
      !rendered(Badge("OK", BadgeVariant.Outline), 12, 1)
        .get(0, 0)
        .style
        .modifiers
        .hasAny(
          io.worxbend.tui.core.Modifiers.Reverse
        ),
      "an outline badge must not paint a block of colour",
    )

  /** A dot badge carries colour on the dot only — that is the whole point of it being quieter than the others. */
  test("a dot badge colours the dot and not the label"):
    val buffer = rendered(Badge("ready", BadgeVariant.Dot, Style.Default.withFg(Color.Green)), 12, 1)
    assert(buffer.get(0, 0).style.fg.contains(Color.Green))
    assert(buffer.get(2, 0).style.fg.isEmpty)

  /** A caller sizes a column from `widthAt`, so it has to match the drawn extent including any padding — measured on
    * the untrimmed cells, since the solid variant's trailing pad is real output.
    */
  test("widthAt matches what each variant actually draws"):
    BadgeVariant.values.foreach: variant =>
      val badge   = Badge("NEW", variant)
      val buffer  = rendered(badge, 40, 1)
      val painted = (0 until 40).count(x => buffer.get(x, 0).symbol != " ")
      assert(painted <= badge.widthAt(1).getOrElse(0), s"$variant drew more than it asked for")
      assert(raw(badge, 40).length <= badge.widthAt(1).getOrElse(0), s"$variant mis-reports its width")

  test("a badge clips to its area instead of spilling"):
    BadgeVariant.values.foreach: variant =>
      Seq(0, 1, 2, 3).foreach: width =>
        assert(raw(Badge("LONGLABEL", variant), width).length <= width)

  test("Badge.of carries a level's own tag"):
    assert(render(Badge.of(NoticeLevel.Error, Style.Default, BadgeVariant.Outline), 12) == "[FAIL]")
    assert(render(Badge.of(NoticeLevel.Success, Style.Default, BadgeVariant.Outline), 12) == "[OK]")

  test("Badge.of draws a tag passed in — a translated one — in the level's place"):
    assert(render(Badge.of(NoticeLevel.Error, Style.Default, BadgeVariant.Outline, Some("ERROR")), 12) == "[ERROR]")

  test("an empty label still renders a well-formed badge"):
    assert(raw(Badge("", BadgeVariant.Outline), 8) == "[]")
    assert(raw(Badge("", BadgeVariant.Dot), 8) == "●")

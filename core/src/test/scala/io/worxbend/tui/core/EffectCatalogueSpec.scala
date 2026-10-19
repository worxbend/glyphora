package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/** The 0.20.0 effects, each sampled at the start, the middle and the end of a one-second run on a 6x2 frame. */
final class EffectCatalogueSpec extends AnyFunSuite:

  private val red = Color.Rgb(255, 0, 0)

  /** Row 0 "abc" then three blanks, row 1 "defghi", all text pure red. */
  private def frame(): Buffer =
    val buffer = Buffer(Rect(0, 0, 6, 2))
    buffer.setString(0, 0, "abc", Style.Default.withFg(red))
    buffer.setString(0, 1, "defghi", Style.Default.withFg(red))
    buffer

  private def at(effect: Effect, elapsed: FiniteDuration): Buffer =
    val buffer = frame()
    effect.process(elapsed, buffer, buffer.area)
    buffer

  private def rows(buffer: Buffer): Seq[String] =
    (0 until buffer.area.height).map(y => (0 until buffer.area.width).map(x => buffer.get(x, y).symbol).mkString)

  private def fgAt(buffer: Buffer, x: Int, y: Int): Option[Color] = buffer.get(x, y).style.fg

  private val start = Duration.Zero
  private val mid   = 500.millis
  private val end   = 1.second

  test("hslShift rotates the hue of text through the run and leaves blanks alone"):
    val fx = Effect.hslShift(hue = 120.0, saturation = 0.0, lightness = 0.0, duration = 1.second)
    assert(fgAt(at(fx, start), 0, 0).contains(Color.Rgb(255, 0, 0)))
    assert(fgAt(at(fx, mid), 0, 0).contains(Color.Rgb(255, 255, 0)))
    assert(fgAt(at(fx, end), 5, 1).contains(Color.Rgb(0, 255, 0)))
    assert(at(fx, end).get(4, 0) == Cell.Empty)
    assert(rows(at(fx, mid)) == rows(frame()))

  test("hslShift moves lightness too"):
    val fx = Effect.hslShift(hue = 0.0, saturation = 0.0, lightness = 0.5, duration = 1.second)
    assert(fgAt(at(fx, end), 0, 0).contains(Color.Rgb(255, 255, 255)))

  test("paint mixes text towards a colour"):
    val fx = Effect.paint(Color.Rgb(0, 0, 255), 1.second)
    assert(fgAt(at(fx, start), 0, 0).contains(red))
    assert(fgAt(at(fx, mid), 0, 0).contains(Color.Rgb(128, 0, 128)))
    assert(fgAt(at(fx, end), 0, 0).contains(Color.Rgb(0, 0, 255)))
    assert(at(fx, end).get(4, 0) == Cell.Empty)

  private def bgAt(buffer: Buffer, x: Int, y: Int): Option[Color] = buffer.get(x, y).style.bg

  /** Row 0 "ab" on a pure-blue background with the rest of the row a blue blank, row 1 plain red text, no background.
    */
  private def panel(): Buffer =
    val buffer = Buffer(Rect(0, 0, 4, 2))
    buffer.setString(0, 0, "ab  ", Style.Default.withFg(red).withBg(Color.Rgb(0, 0, 255)))
    buffer.setString(0, 1, "cd", Style.Default.withFg(red))
    buffer

  private def onPanel(effect: Effect, elapsed: FiniteDuration): Buffer =
    val buffer = panel()
    effect.process(elapsed, buffer, buffer.area)
    buffer

  test("paint can target the background, blank cells included, leaving the foreground alone"):
    val fx = Effect.paint(Color.Rgb(255, 0, 0), 1.second, target = ColorTarget.Background)
    assert(bgAt(onPanel(fx, mid), 0, 0).contains(Color.Rgb(128, 0, 128)))
    assert(bgAt(onPanel(fx, end), 3, 0).contains(Color.Rgb(255, 0, 0)), "a blank cell's background is painted too")
    assert(fgAt(onPanel(fx, end), 0, 0).contains(red))
    // a cell with no background of its own is painted from black
    assert(bgAt(onPanel(fx, mid), 0, 1).contains(Color.Rgb(128, 0, 0)))

  test("paint can target both layers"):
    val fx = Effect.paint(Color.Rgb(0, 255, 0), 1.second, target = ColorTarget.Both)
    val at = onPanel(fx, end)
    assert(fgAt(at, 0, 0).contains(Color.Rgb(0, 255, 0)))
    assert(bgAt(at, 0, 0).contains(Color.Rgb(0, 255, 0)))

  test("hslShift can target the background"):
    val fx = Effect.hslShift(hue = 120.0, saturation = 0.0, lightness = 0.0, 1.second, target = ColorTarget.Background)
    // blue (240°) turned 120° is red (0°)
    assert(bgAt(onPanel(fx, end), 3, 0).contains(Color.Rgb(255, 0, 0)))
    assert(fgAt(onPanel(fx, end), 0, 0).contains(red), "the foreground is untouched")
    val both = Effect.hslShift(hue = 120.0, saturation = 0.0, lightness = 0.0, 1.second, target = ColorTarget.Both)
    assert(fgAt(onPanel(both, end), 0, 0).contains(Color.Rgb(0, 255, 0)))
    assert(bgAt(onPanel(both, end), 0, 0).contains(Color.Rgb(255, 0, 0)))

  test("glitch scrambles text while it runs, deterministically, and leaves a clean frame at the end"):
    val full    = Effect.glitch(intensity = 1.0, duration = 1.second)
    Seq(start, mid).foreach: elapsed =>
      val glitched = at(full, elapsed)
      (0 until 3).foreach(x => assert(Effect.GlitchGlyphs.contains(glitched.get(x, 0).symbol), s"$elapsed col $x"))
      assert(glitched.get(4, 0).isBlank, "blank cells are not glitched")
      assert(rows(at(full, elapsed)) == rows(glitched), "same elapsed, same frame")
    assert(rows(at(full, end)) == rows(frame()))
    assert(rows(at(Effect.glitch(0.0, 1.second), mid)) == rows(frame()))
    val half    = rows(at(Effect.glitch(0.5, 1.second), mid)).mkString
    val changed = half.zip(rows(frame()).mkString).count(_ != _)
    assert(changed > 0 && changed < 9, s"half intensity changed $changed of 9 text cells")

  test("evolve runs text through the symbol set and settles on the real glyph"):
    val fx = Effect.evolve(Seq("1", "2", "3"), 1.second)
    assert(rows(at(fx, start)) == Seq("111   ", "111111"))
    assert(rows(at(fx, mid)) == Seq("333   ", "333333"))
    assert(rows(at(fx, end)) == rows(frame()))
    assert(fgAt(at(fx, mid), 1, 1).contains(red), "the cell keeps its style")

  test("reversed plays an effect backwards"):
    val fx = Effect.reversed(Effect.sweepIn(1.second, Easing.Linear))
    assert(fx.duration == 1.second)
    assert(rows(at(fx, start)) == rows(frame()))
    assert(rows(at(fx, mid)) == Seq("abc   ", "def   "))
    assert(rows(at(fx, end)) == Seq("      ", "      "))

  test("pingPong plays forward then back, ending where it began"):
    val fx = Effect.pingPong(Effect.sweepIn(500.millis, Easing.Linear))
    assert(fx.duration == 1.second)
    assert(rows(at(fx, start)) == Seq("      ", "      "))
    assert(rows(at(fx, mid)) == rows(frame()))
    assert(rows(at(fx, 250.millis)) == Seq("abc   ", "def   "))
    assert(rows(at(fx, end)) == rows(at(fx, start)))

  test("an endless effect passes through reversed and pingPong unchanged"):
    assert(Effect.reversed(Effect.pulse()).duration == Duration.Inf)
    assert(Effect.pingPong(Effect.pulse()).duration == Duration.Inf)

  test("slideIn travels in each of the four directions"):
    def slide(direction: MotionDirection, elapsed: FiniteDuration) =
      rows(at(Effect.slideIn(direction, 1.second, Easing.Linear), elapsed))
    MotionDirection.values.foreach: direction =>
      assert(slide(direction, start).forall(_.isBlank), direction)
      assert(slide(direction, end) == rows(frame()), direction)
    assert(slide(MotionDirection.Left, mid) == Seq("   abc", "   def"))
    assert(slide(MotionDirection.Right, mid) == Seq("      ", "ghi   "))
    assert(slide(MotionDirection.Up, mid) == Seq("      ", "abc   "))
    assert(slide(MotionDirection.Down, mid) == Seq("defghi", "      "))

  test("slideInFromRight is slideIn travelling left"):
    val legacy = rows(at(Effect.slideInFromRight(1.second, Easing.Linear), 300.millis))
    assert(legacy == rows(at(Effect.slideIn(MotionDirection.Left, 1.second, Easing.Linear), 300.millis)))

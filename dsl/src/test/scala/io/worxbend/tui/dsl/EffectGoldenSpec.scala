package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class EffectGoldenSpec extends AnyFunSuite:

  test("slideIn(MotionDirection.Up) halfway: the top half of the content has risen into the bottom half of the frame"):
    val buffer = Buffer(Rect(0, 0, 8, 4))
    Seq("first", "second", "third", "fourth").zipWithIndex.foreach((line, y) =>
      buffer.setString(0, y, line, Style.Default)
    )
    Effect.slideIn(MotionDirection.Up, 1.second, Easing.Linear).process(500.millis, buffer, buffer.area)
    GoldenFrames.assertMatches("effect-slide-in-up-half", buffer)

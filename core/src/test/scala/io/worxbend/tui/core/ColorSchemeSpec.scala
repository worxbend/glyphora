package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** Reading light or dark off a background colour — the rule `Theme.auto` stands on. */
final class ColorSchemeSpec extends AnyFunSuite:

  test("a near-black background is dark and a white one is light"):
    assert(ColorScheme.of(Color.Rgb(0x1e, 0x1e, 0x2e)) == ColorScheme.Dark)
    assert(ColorScheme.of(Color.Rgb(0xff, 0xff, 0xff)) == ColorScheme.Light)

  /** The cutoff is where black and white text read equally well, not the middle of the channel range: on a mid-grey
    * black text is the better choice, so the background counts as light.
    */
  test("a mid-grey background counts as light because black text reads better on it"):
    assert(ColorScheme.of(Color.Rgb(0x80, 0x80, 0x80)) == ColorScheme.Light)
    assert(ColorScheme.of(Color.Rgb(0x40, 0x40, 0x40)) == ColorScheme.Dark)

  test("named colours answer against the nominal palette and Reset stays unknown"):
    assert(ColorScheme.of(Color.Black) == ColorScheme.Dark)
    assert(ColorScheme.of(Color.White) == ColorScheme.Light)
    assert(ColorScheme.of(Color.Reset) == ColorScheme.Unknown)

package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, TerminalRunner}

import org.scalatest.funsuite.AnyFunSuite

/** Pins the app-level snapshot: a whole running app's frame compared against a fixture, without the test reaching for
  * `pilot.lastFrame` itself.
  *
  * The comparison path is the one `GoldenFramesSpec` already covers, so what matters here is the wiring — that the
  * frame the pilot last drew is the frame that gets compared, and that a snapshot of an app that has drawn nothing
  * fails rather than matching an empty fixture.
  */
final class PilotGoldenSpec extends AnyFunSuite:

  private def painting(text: String): Pilot =
    Pilot.start(Size(12, 2)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (_, _) => EventOutcome.Ignored,
        frame =>
          frame.renderWidget(
            (area, buffer) => buffer.setString(area.x, area.y, text, Style.Default),
            frame.area,
          ),
      )
    }

  test("assertGolden compares the frame the app last drew, and hands the pilot back"):
    // the fixture is `test-support/src/test/resources/golden/pilot-app-frame.txt`, which holds exactly "hello"
    val pilot = painting("hello")
    try
      pilot.waitForIdle()
      assert(pilot.assertGolden("pilot-app-frame") eq pilot)
    finally pilot.close()

  test("a frame that does not match the fixture fails, naming the fixture"):
    val pilot = painting("goodbye")
    try
      pilot.waitForIdle()
      val error = intercept[AssertionError](pilot.assertGolden("pilot-app-frame"))
      assert(error.getMessage.contains("frame differs from golden/pilot-app-frame.txt"))
      assert(error.getMessage.contains("goodbye"))
    finally pilot.close()

  test("a snapshot of an app that has drawn nothing fails rather than matching an empty fixture"):
    val pilot = Pilot.start(Size(4, 1))(_ => Right(()))
    try
      val error = intercept[AssertionError](pilot.assertGolden("never-drawn"))
      assert(error.getMessage.contains("nothing has been drawn yet"))
    finally pilot.close()

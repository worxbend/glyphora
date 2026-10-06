package io.worxbend.tui.examples.helloworld

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** The smallest end-to-end test in the repository, and the reason it exists: every example is claimed to have one, and
  * CI now runs `examples.<name>.test` for each module it discovers, so an example without a test breaks the matrix
  * rather than quietly opting out of it.
  */
final class HelloWorldSpec extends AnyFunSuite:

  private def withApp[A](body: Pilot => A): A =
    val backend = HeadlessBackend(Size(40, 8))
    Pilot.using(backend) { HelloWorldApp().runWith(backend) } { pilot =>
      pilot.waitForIdle()
      body(pilot)
    }

  test("the panel renders its title and both lines"):
    withApp { pilot =>
      assert(pilot.screenText.contains("Hello"))
      assert(pilot.screenText.contains("Welcome to glyphora!"))
      assert(pilot.screenText.contains("Press 'q' to quit"))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    }

  test("q quits and anything else does not"):
    withApp { pilot =>
      pilot.pressKey(KeyCode.Char('x')).waitForIdle()
      assert(pilot.isRunning, "an unbound key should not end the app")
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    }

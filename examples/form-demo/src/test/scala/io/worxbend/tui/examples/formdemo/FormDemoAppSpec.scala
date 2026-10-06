package io.worxbend.tui.examples.formdemo

import io.worxbend.tui.core.{KeyCode, KeyModifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** End-to-end form validation: typed field checks run on submit, an invalid input surfaces its validation error in the
  * rendered UI, and a corrected form assembles the case class.
  */
final class FormDemoAppSpec extends AnyFunSuite:

  private def withApp[A]()(body: (FormDemoApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(50, 12))
    val app     = FormDemoApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      body(app, pilot)
    }

  private def submit(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('s'), KeyModifiers.Ctrl).waitForIdle()

  test("the derived form renders a control per case-class field"):
    withApp() { (_, pilot) =>
      assert(pilot.screenText.contains("username:"))
      assert(pilot.screenText.contains("age:"))
      assert(pilot.screenText.contains("[ ] subscribe"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("an invalid field surfaces its validation error in the UI"):
    withApp() { (app, pilot) =>
      pilot.typeText("ada").pressKey(KeyCode.Tab).typeText("12").waitForIdle()
      submit(pilot)
      assert(pilot.screenText.contains("! must be 18 or older"))
      assert(app.formState.result.peek.isEmpty)
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("an empty required text field surfaces its error once numeric input parses"):
    withApp() { (_, pilot) =>
      pilot.pressKey(KeyCode.Tab).typeText("36").waitForIdle()
      submit(pilot)
      assert(pilot.screenText.contains("! required"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("a valid form assembles the case class and clears old errors"):
    withApp() { (app, pilot) =>
      submit(pilot) // provoke errors first
      assert(pilot.screenText.contains("is not a whole number"))
      pilot.typeText("ada").pressKey(KeyCode.Tab).typeText("36").pressKey(KeyCode.Tab).pressKey(KeyCode.Char(' '))
      pilot.waitForIdle()
      submit(pilot)
      assert(app.formState.result.peek.contains(Signup("ada", 36, true)))
      assert(pilot.screenText.contains("""last submitted: Signup(ada,36,true)"""))
      assert(!pilot.screenText.contains("! required"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

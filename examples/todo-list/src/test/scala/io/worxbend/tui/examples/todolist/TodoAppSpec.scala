package io.worxbend.tui.examples.todolist

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Headless end-to-end tests for the todo list's primary interaction paths. */
final class TodoAppSpec extends AnyFunSuite:

  private def withApp[A]()(body: (TodoApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(50, 10))
    val app     = TodoApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      body(app, pilot)
    }

  test("typing and pressing Enter adds a todo and clears the input"):
    withApp() { (app, pilot) =>
      pilot.typeText("buy milk").pressKey(KeyCode.Enter).waitForIdle()
      assert(app.items.peek == Vector("buy milk"))
      assert(app.inputState.value == "")
      assert(pilot.screenText.contains("· buy milk"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("tab switches focus to the list where arrows select and d deletes"):
    withApp() { (app, pilot) =>
      pilot.typeText("one").pressKey(KeyCode.Enter)
      pilot.typeText("two").pressKey(KeyCode.Enter).waitForIdle()
      assert(app.items.peek == Vector("one", "two"))
      pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Down).pressKey(KeyCode.Down).waitForIdle()
      assert(app.listState.selected.contains(1))
      pilot.pressKey(KeyCode.Char('d')).waitForIdle()
      assert(app.items.peek == Vector("one"))
      assert(pilot.screenText.contains("· one"))
      assert(!pilot.screenText.contains("· two"))
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

  test("typing goes to the input again after tabbing back"):
    withApp() { (app, pilot) =>
      pilot.typeText("a").pressKey(KeyCode.Enter).waitForIdle()
      pilot.pressKey(KeyCode.Tab).waitForIdle() // to the list
      pilot.pressKey(KeyCode.Tab).waitForIdle() // wraps back to the input
      pilot.typeText("b").waitForIdle()
      assert(app.inputState.value == "b")
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
    }

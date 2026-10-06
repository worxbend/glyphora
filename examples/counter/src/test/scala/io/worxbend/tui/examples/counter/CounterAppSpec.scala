package io.worxbend.tui.examples.counter

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Headless end-to-end test for the counter's primary interaction path.
  *
  * A fresh `CounterApp()` per test, never the `object Main`: `TuiApp` keeps its signals and screen stack on the
  * instance and does not reset them between runs, so a shared object would carry the previous scenario's count into the
  * next one.
  *
  * The keys go through the app's declared `bindings` rather than an element handler, so this also covers the status bar
  * staying in step with them — the hints it renders come from the same values `press` dispatches against.
  */
final class CounterAppSpec extends AnyFunSuite:

  test("increments and decrements re-render the count, and q quits"):
    val backend = HeadlessBackend(Size(52, 14))
    val app     = CounterApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      assert(pilot.screenText.contains("Count: 0"))
      assert(pilot.screenText.contains("+ increment"), "the status bar renders the declared bindings")
      pilot.press("+", "+", "+").waitForIdle()
      assert(pilot.screenText.contains("Count: 3"))
      pilot.press("-").waitForIdle()
      assert(pilot.screenText.contains("Count: 2"))
      assert(app.count.peek == 2)
      pilot.press("r").waitForIdle()
      assert(pilot.screenText.contains("Count: 0"))
      pilot.press("q")
      assert(pilot.awaitTermination())
    }

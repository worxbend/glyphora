package io.worxbend.tui.widgets

import io.worxbend.tui.core.Modifiers
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class WizardSpec extends AnyFunSuite:

  private val titles = Seq("Account", "Profile", "Confirm")

  test("the header marks finished steps, the current one and those still ahead"):
    val buffer = rendered(Wizard(titles, current = 1), 40, 1)
    assert(line(buffer, 0).stripTrailing == "✓ Account ─ ● Profile ─ ○ Confirm")
    assert(buffer.get(12, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(24, 0).style.modifiers.hasAny(Modifiers.Dim))

  test("too narrow for every title, the header says which step of how many"):
    assert(line(rendered(Wizard(titles, current = 1), 24, 1), 0).stripTrailing == "Step 2 of 3 · Profile")
    assert(line(rendered(Wizard(titles, current = 2), 8, 1), 0) == "Step 3 o")

  test("it measures one row and the width of the full header"):
    assert(Wizard(titles, 0).widthAt(1).contains(33))
    assert(Wizard(titles, 0).heightAt(10).contains(1))

  test("state: next stops at the last step, back at the first, and a failure is cleared by moving"):
    val state = WizardState()
    state.back()
    assert(state.current == 0)
    state.fail("name is required")
    assert(state.error.contains("name is required"))
    state.next(3)
    assert(state.current == 1 && state.error.isEmpty)
    state.next(3)
    state.next(3)
    assert(state.current == 2)
    state.back()
    assert(state.current == 1)

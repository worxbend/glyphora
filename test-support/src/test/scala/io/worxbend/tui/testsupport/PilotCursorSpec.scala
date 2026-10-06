package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, KeyCode, Position, Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, TerminalRunner}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

/** Pins the cursor half of the [[Pilot]] surface.
  *
  * There are two different things a terminal application can call a cursor, and until this existed a test could only
  * see one of them. The first is a styled cell — a reversed block a text field paints where the caret is — which shows
  * up in the drawn frame and which `cellAt` has always been able to read. The second is the terminal's own hardware
  * caret, which a view requests with `Frame.setCursorPosition`: an input method editor anchors its candidate popup to
  * it and a screen reader reports it as the insertion point, and it appears nowhere in the drawn frame at all. These
  * tests are about the second one.
  */
final class PilotCursorSpec extends AnyFunSuite:

  /** Runs a view that parks the hardware caret wherever `wanted` currently says, redrawing on every key. */
  private def pilotOver(wanted: AtomicReference[Option[Position]]): Pilot =
    Pilot.start(Size(10, 3)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (event, _) =>
          event match
            case Event.Key(key) if key.code == KeyCode.Char('r') => EventOutcome.Redraw
            case _                                               => EventOutcome.Ignored
        ,
        frame =>
          frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, "edit", Style.Default), frame.area)
          wanted.get().foreach(frame.setCursorPosition),
      )
    }

  test("a view that parks the caret is visible to assertCursorAt"):
    val wanted = AtomicReference[Option[Position]](Some(Position(4, 1)))
    val pilot  = pilotOver(wanted)
    try
      pilot.waitForIdle()
      assert(pilot.cursorPosition == Some(Position(4, 1)))
      val _ = pilot.assertCursorAt(4, 1)
    finally pilot.close()

  test("assertCursorAt names the position the caret actually holds"):
    val wanted = AtomicReference[Option[Position]](Some(Position(4, 1)))
    val pilot  = pilotOver(wanted)
    try
      pilot.waitForIdle()
      val error = intercept[AssertionError](pilot.assertCursorAt(0, 0))
      // an assertion that only said "wrong" would leave the reader running the app again to find out where it went
      assert(error.getMessage.contains("Position(4,1)"))
    finally pilot.close()

  test("assertCursorAt says so when the app never asked for a caret at all"):
    val pilot = pilotOver(AtomicReference[Option[Position]](None))
    try
      pilot.waitForIdle()
      val error = intercept[AssertionError](pilot.assertCursorAt(0, 0))
      assert(error.getMessage.contains("no cursor position was requested"))
      val _     = pilot.assertNoCursor()
    finally pilot.close()

  test("a caret the app withdraws stops being reported, so it cannot be left owned by a pane the user has left"):
    // the regression this pair exists for: withdrawing a caret is a `hideCursor`, because there is nowhere to move a
    // caret to. The backend's last-parked position therefore survives underneath, and a Pilot that reported it would
    // say a text field still owns the insertion point after the user tabbed away.
    val wanted = AtomicReference[Option[Position]](Some(Position(2, 0)))
    val pilot  = pilotOver(wanted)
    try
      pilot.waitForIdle()
      val _ = pilot.assertCursorAt(2, 0)
      wanted.set(None)
      pilot.press("r").waitForIdle()
      val _ = pilot.assertNoCursor()
      // the stale value is still readable straight off the backend, for a test that wants to assert on the withdrawal
      assert(pilot.backend.cursorPosition == Some(Position(2, 0)))
    finally pilot.close()

  test("assertNoCursor names where the caret is when one is being shown"):
    val pilot = pilotOver(AtomicReference[Option[Position]](Some(Position(1, 2))))
    try
      pilot.waitForIdle()
      val error = intercept[AssertionError](pilot.assertNoCursor())
      assert(error.getMessage.contains("Position(1,2)"))
    finally pilot.close()

  test("the caret follows the view rather than the drawn frame"):
    // the whole reason this is not a `cellAt` assertion: the frame is identical either way, so a test reading only the
    // buffer cannot tell a caret that moved from one that did not.
    val wanted = AtomicReference[Option[Position]](Some(Position(0, 0)))
    val pilot  = pilotOver(wanted)
    try
      pilot.waitForIdle()
      val before = pilot.screenText
      wanted.set(Some(Position(3, 2)))
      pilot.press("r").waitForIdle()
      val _      = pilot.assertCursorAt(3, 2)
      assert(pilot.screenText == before)
    finally pilot.close()

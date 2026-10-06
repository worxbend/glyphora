package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, RunnerError, TerminalRunner}
import io.worxbend.tui.terminal.{BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

/** Pins the overload of `Pilot.start` that owns the headless backend it runs the app against.
  *
  * The value of the overload is that a test stops naming the backend twice, so the interesting assertions are that the
  * backend really is the size that was asked for, that the body was handed *that* backend and not another one, and that
  * the failure reporting of the older overload is inherited rather than reimplemented.
  */
final class PilotStartSpec extends AnyFunSuite:

  test("start(size) builds a backend of that size"):
    Pilot.using(Size(12, 3)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (_, _) => EventOutcome.Ignored,
        frame => frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, "hi", Style.Default), frame.area),
      )
    } { pilot =>
      pilot.waitForIdle()
      assert(pilot.backend.size == Right(Size(12, 3)))
      assert(pilot.screenLines.size == 3)
      assert(pilot.screenText.startsWith("hi"))
    }

  test("start(size) hands the body the very backend it exposes afterwards"):
    val seen = AtomicReference[Option[HeadlessBackend]](None)
    Pilot.using(Size(8, 2)) { backend =>
      seen.set(Some(backend))
      TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Ignored, _ => ())
    } { pilot =>
      pilot.waitForIdle()
      // Reference equality: a second backend would have a different event queue.
      assert(seen.get().exists(_ eq pilot.backend))
    }

  test("start(size) reports a failed run the same way the backend-owning overload does"):
    val failure = RunnerError.Backend(BackendError.UnsupportedTerminal("cannot restore"))
    val pilot   = Pilot.start(Size(6, 2))(_ => Left(failure))
    val error   = intercept[AssertionError](pilot.awaitTermination())
    assert(error.getMessage.contains("cannot restore"))

  test("start(size) reports a throwable that escaped the body"):
    val pilot = Pilot.start(Size(6, 2))(_ => throw IllegalStateException("the view blew up"))
    val error = intercept[AssertionError](pilot.waitForIdle())
    assert(error.getCause.getMessage == "the view blew up")

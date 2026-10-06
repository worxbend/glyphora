package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, KeyModifiers, MouseEventKind, Size}
import io.worxbend.tui.runtime.{EventOutcome, TerminalRunner}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

/** Pins the byte-level input path: what a terminal actually sends, decoded by the production decoder, reaching a
  * running application.
  *
  * `press("ctrl+s")` and a binding declared as `"ctrl+s"` both go through the key-spec parser, so they agree with each
  * other by construction and say nothing about the decoder. If the decoder turned the bytes a terminal sends for Ctrl+S
  * into a different `KeyEvent`, every pilot test would still be green and the application would be dead in a real
  * terminal. These tests are the ones that would not be.
  */
final class PilotSendBytesSpec extends AnyFunSuite:

  /** Starts an app that records every event it is handed, and hands back the pilot and the recording. */
  private def startRecording(): (Pilot, AtomicReference[Seq[Event]]) =
    val seen  = AtomicReference[Seq[Event]](Seq.empty)
    val pilot = Pilot.start(Size(20, 2)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (event, _) =>
          val _ = seen.updateAndGet(_ :+ event)
          EventOutcome.Ignored
        ,
        _ => (),
      )
    }
    (pilot, seen)

  test("the byte a terminal sends for Ctrl+S is the key an app binds as ctrl+s"):
    val (pilot, seen) = startRecording()
    try
      pilot.sendBytes(0x13).waitForIdle()
      // the same spelling an application declares its binding with, parsed by the same parser the application uses
      val expected = KeyEvent.parse("ctrl+s")
      assert(seen.get() == Seq(Event.Key(expected.getOrElse(fail("ctrl+s is not a valid key spec")))))
    finally pilot.close()

  test("an arrow key arrives as the named key, not as three characters"):
    val (pilot, seen) = startRecording()
    try
      pilot.sendEscape("[A").waitForIdle()
      assert(seen.get() == Seq(Event.Key(KeyEvent(KeyCode.Up, KeyModifiers.None))))
    finally pilot.close()

  test("a bracketed paste arrives as one paste event carrying the whole payload"):
    val (pilot, seen) = startRecording()
    try
      // ESC [ 2 0 0 ~  h i 日  ESC [ 2 0 1 ~ — the sequence a terminal wraps pasted text in. It goes in a single call
      // because one call is one decoder: split across two, the first decoder would reach the end of its input in the
      // middle of the paste and report the empty paste it had read so far.
      val esc   = 0x1b.toChar
      val paste = s"$esc[200~hi日$esc[201~"
      pilot.sendBytes(paste.map(_.toInt)*).waitForIdle()
      assert(seen.get() == Seq(Event.Paste("hi日")))
    finally pilot.close()

  test("an SGR mouse report arrives at the zero-based position the app works in"):
    val (pilot, seen) = startRecording()
    try
      // SGR counts columns and rows from one; every coordinate above the terminal boundary counts from zero
      pilot.sendEscape("[<0;5;3M").waitForIdle()
      val mouse = seen.get().collect { case Event.Mouse(event) => event }
      assert(mouse.map(_.position.x) == Seq(4))
      assert(mouse.map(_.position.y) == Seq(2))
      assert(mouse.map(_.kind) == Seq(MouseEventKind.Down))
    finally pilot.close()

  test("a device-attributes reply reaches the app as no event at all"):
    val (pilot, seen) = startRecording()
    try
      // a capability probe's answer must never be synthesised into an Escape: that would close the user's dialog
      pilot.sendEscape("[?62;1;4c").waitForIdle()
      assert(seen.get().isEmpty)
    finally pilot.close()

  test("plain text arrives one key event per character"):
    val (pilot, seen) = startRecording()
    try
      pilot.sendBytes('h'.toInt, 'i'.toInt).waitForIdle()
      val plain = Seq('h', 'i').map(c => Event.Key(KeyEvent(KeyCode.Char(c), KeyModifiers.None)))
      assert(seen.get() == plain)
    finally pilot.close()

  test("sending nothing posts nothing"):
    val (pilot, seen) = startRecording()
    try
      pilot.sendBytes().waitForIdle()
      assert(seen.get().isEmpty)
    finally pilot.close()

package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size}
import io.worxbend.tui.runtime.{EventOutcome, Frame, RunnerHandle, TerminalRunner}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Pins the unit of iteration of [[Pilot.typeText]]: one key event per Unicode code point, matching what the real
  * `InputDecoder` delivers. The astral case is the regression this file exists for — iterating UTF-16 code units split
  * an emoji into two lone surrogates, which is not a keypress any application can act on.
  */
final class PilotTypeTextSpec extends AnyFunSuite:

  /** The combining acute accent, U+0301, written as a code point rather than as a literal so that the source file's own
    * encoding and any editor normalisation cannot change what this test asserts.
    */
  private val CombiningAcute: Int = 0x0301

  /** U+1F680 ROCKET: outside the Basic Multilingual Plane, so one code point but two UTF-16 code units. */
  private val Rocket: Int = 0x1f680

  /** Runs an app that records every key code it is handed and quits on Escape, then reports what `typeText(text)`
    * delivered. `seen` is written by the app thread and read by the test thread after `awaitTermination`, whose join
    * establishes the happens-before edge that makes those writes visible.
    */
  private def keysDeliveredFor(text: String): Seq[KeyCode] =
    val seen                                                          = mutable.ArrayBuffer.empty[KeyCode]
    val backend                                                       = HeadlessBackend(Size(20, 3))
    def handleEvent(event: Event, handle: RunnerHandle): EventOutcome =
      event match
        case Event.Key(KeyEvent(KeyCode.Escape, _)) =>
          handle.quit()
          EventOutcome.Ignored
        case Event.Key(KeyEvent(code, _))           =>
          seen += code
          EventOutcome.Ignored
        case _                                      => EventOutcome.Ignored
    val pilot = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), handleEvent, (_: Frame) => ()))
    try
      pilot.typeText(text).waitForIdle()
      pilot.pressKey(KeyCode.Escape)
      assert(pilot.awaitTermination())
      seen.toSeq
    finally pilot.close()

  test("ASCII text arrives as one key event per character"):
    assert(keysDeliveredFor("hi") == Seq(KeyCode.Char('h'), KeyCode.Char('i')))

  test("an astral code point arrives as one key event, not two lone surrogates"):
    val rocket = Character.toString(Rocket)
    assert(rocket.length == 2) // two UTF-16 code units, one code point
    assert(keysDeliveredFor(rocket) == Seq(KeyCode.Char(Rocket)))

  test("a combining mark is its own key event, as a real terminal reports it"):
    val accented = "e" + Character.toString(CombiningAcute)
    assert(keysDeliveredFor(accented) == Seq(KeyCode.Char('e'), KeyCode.Char(CombiningAcute)))

  test("the event count follows code points, not string length"):
    val mixed = "a" + Character.toString(Rocket) + "b"
    assert(mixed.length == 4)
    assert(keysDeliveredFor(mixed).size == 3)

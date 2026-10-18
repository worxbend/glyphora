package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, KeyModifiers}

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.terminal.ScriptedInput.{Esc, NothingAvailable}

import java.io.InterruptedIOException

import scala.concurrent.duration.DurationInt

/** The production boundary for a wake that lands inside a sequence: [[EventPump.poll]] turns the interrupt into
  * `Right(None)`, and the decoder underneath it must have kept the consumed prefix for the next poll.
  */
final class EventPumpWakeSpec extends AnyFunSuite:

  /** A reader whose second read — the wait for the byte after `ESC` — is cut short by a wake. */
  private def escThenWake(): InputDecoder =
    var reads = 0
    InputDecoder { _ =>
      reads += 1
      if reads == 1 then Esc
      else if reads == 2 then throw new InterruptedIOException("wake")
      else NothingAvailable
    }

  test("a wake landing after ESC answers one poll with nothing and the next with the Escape key"):
    // the Escape used to be lost at this boundary: the first poll answered `Right(None)` and the `ESC` went with it
    val pump = EventPump(escThenWake())
    assert(pump.poll(100.millis) == Right(None))
    assert(pump.poll(100.millis) == Right(Some(Event.Key(KeyEvent(KeyCode.Escape, KeyModifiers.None)))))

  test("ESC, wake, x reaches the runner as Alt+x"):
    var reads = 0
    val input = InputDecoder { _ =>
      reads += 1
      reads match
        case 1 => Esc
        case 2 => throw new InterruptedIOException("wake")
        case 3 => 'x'.toInt
        case _ => NothingAvailable
    }
    val pump  = EventPump(input)
    assert(pump.poll(100.millis) == Right(None))
    assert(pump.poll(100.millis) == Right(Some(Event.Key(KeyEvent(KeyCode.Char('x'), KeyModifiers.Alt)))))

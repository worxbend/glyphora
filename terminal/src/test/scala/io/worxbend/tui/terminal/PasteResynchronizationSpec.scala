package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent}

import org.scalatest.funsuite.AnyFunSuite

import java.io.InterruptedIOException

/** Exhausting a paste budget yields to the caller, never hands its untrusted remainder to key decoding. */
final class PasteResynchronizationSpec extends AnyFunSuite:

  test("drain exhaustion keeps bounded resynchronization across wakes and a split terminator"):
    val opening    = "\u001b[200~"
    val limit      = 1 << 20
    val drainLimit = limit * 16
    // The second drain ends halfway through the terminator. Its rolling tail must survive the yield and wake.
    val remaining  = "1~x"
    val prefix     = "\u001b[20"
    var consumed   = 0
    var interrupts = List(opening.length + 100, opening.length + drainLimit + 1, opening.length + 2 * drainLimit)
    val input      = InputDecoder { _ =>
      if interrupts.headOption.contains(consumed) then
        interrupts = interrupts.tail
        throw new InterruptedIOException("wake")
      else
        val at = consumed - opening.length
        val c  =
          if consumed < opening.length then opening.charAt(consumed).toInt
          else if at < drainLimit then 'a'.toInt
          else if at == drainLimit + 1 then '\n'.toInt
          else if at < 2 * drainLimit - prefix.length then 'q'.toInt
          else if at < 2 * drainLimit then prefix.charAt(at - (2 * drainLimit - prefix.length)).toInt
          else if at < 2 * drainLimit + remaining.length then remaining.charAt(at - 2 * drainLimit).toInt
          else InputDecoder.ReadExpired
        consumed += 1
        c
    }
    assert(input.decode(10L).isEmpty)
    assert(input.decode(10L).contains(Event.Paste("a" * limit)))
    assert(consumed == opening.length + drainLimit)
    assert(input.decode(10L).isEmpty) // wake in the discarded payload
    assert(input.decode(10L).isEmpty) // the next bounded batch, not a q command or a second paste
    assert(consumed == opening.length + 2 * drainLimit)
    assert(input.decode(10L).isEmpty) // wake inside the terminator
    assert(input.decode(10L).isEmpty) // consume its remainder, without another paste event
    assert(input.decode(10L).contains(Event.Key(KeyEvent.char('x'))))

  test("late payload after stall exhaustion is discarded until the real terminator"):
    val opening     = "\u001b[200~half"
    val remaining   = "q\n\u001b[201~x"
    var at          = 0
    var interrupted = false
    val input       = InputDecoder { _ =>
      if at == opening.length + 10 + 5 && !interrupted then
        interrupted = true
        throw new InterruptedIOException("wake inside late terminator")
      else
        val c = if at < opening.length then opening.charAt(at).toInt
        else if at < opening.length + 10 then InputDecoder.ReadExpired
        else if at < opening.length + 10 + remaining.length then remaining.charAt(at - opening.length - 10).toInt
        else InputDecoder.ReadExpired
        at += 1
        c
    }
    assert(input.decode(10L).contains(Event.Paste("half")))
    assert(at == opening.length + 10)
    assert(input.decode(10L).isEmpty)
    assert(input.decode(10L).isEmpty)
    assert(input.decode(10L).contains(Event.Key(KeyEvent.char('x'))))

  test("EOF while resynchronizing a stalled paste latches without delivering the payload twice"):
    val script  =
      ("\u001b[200~half".map(_.toInt) ++ Vector.fill(10)(InputDecoder.ReadExpired) ++ "q\n".map(_.toInt)).iterator
    var reads   = 0
    val input   = InputDecoder { _ =>
      reads += 1
      if script.hasNext then script.next() else InputDecoder.EndOfStream
    }
    assert(input.decode(10L).contains(Event.Paste("half")))
    assert(input.decode(10L).isEmpty)
    val endedAt = reads
    assert(input.decode(10L).contains(Event.EndOfInput))
    assert(input.decode(10L).contains(Event.EndOfInput))
    assert(reads == endedAt)

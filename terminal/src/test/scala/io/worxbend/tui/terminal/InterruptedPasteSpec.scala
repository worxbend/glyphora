package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent, Size}

import org.scalatest.funsuite.AnyFunSuite

import java.io.InterruptedIOException
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.DurationInt

/** A wake may cut a paste read short, but must never turn its remaining text into application commands. */
final class InterruptedPasteSpec extends AnyFunSuite:

  test("an interrupted paste resumes its payload and terminator instead of reporting ordinary keys"):
    val opening = "\u001b[200~"
    val payload = "aq\n😀\u001b[A"
    val closing = "\u001b[201~"
    val script  = opening + payload + closing + "x"
    // Every boundary once the paste opener is complete, including inside UTF-16 pairs and the rolling terminator.
    for offset <- opening.length until opening.length + payload.length + closing.length do
      var at          = 0
      var interrupted = false
      val input       = InputDecoder { _ =>
        if at == offset && !interrupted then
          interrupted = true
          throw new InterruptedIOException("wake")
        else if at < script.length then
          val c = script.charAt(at).toInt
          at += 1
          c
        else InputDecoder.ReadExpired
      }
      assert(input.decode(10L).isEmpty, s"wake at $offset")
      assert(input.decode(10L).contains(Event.Paste(payload)), s"resume at $offset")
      assert(input.decode(10L).contains(Event.Key(KeyEvent.char('x'))), s"next key at $offset")
      assert(input.decode(10L).isEmpty)

  /** Interrupt offsets count consumed entries, so an interrupt never consumes a buffered character or timeout. */
  private final class InterruptingScript(script: IndexedSeq[Int], offsets: List[Int], exhausted: Int):
    private var at            = 0
    private var interruptions = offsets
    private var readCount     = 0

    val decoder: InputDecoder = InputDecoder { _ =>
      readCount += 1
      if interruptions.headOption.contains(at) then
        interruptions = interruptions.tail
        throw new InterruptedIOException("wake")
      else if at < script.length then
        val c = script(at)
        at += 1
        c
      else exhausted
    }

    def reads: Int = readCount

  test("repeated interrupts do not restart the paste's consecutive-stall budget"):
    val script = ("\u001b[200~half".map(_.toInt) ++ Vector.fill(10)(InputDecoder.ReadExpired)).toIndexedSeq
    val input  = InterruptingScript(script, List(19, 19), InputDecoder.ReadExpired)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).contains(Event.Paste("half")))
    assert(input.reads == script.length + 2)
    assert(input.decoder.decode(10L).isEmpty)

  test("EOF after an interrupted paste delivers the retained text and latches end of input"):
    val text   = "aq\u001b[20"
    val script = ("\u001b[200~" + text).map(_.toInt).toIndexedSeq
    val input  = InterruptingScript(script, List(7, 9), InputDecoder.EndOfStream)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).contains(Event.Paste(text)))
    val reads  = input.reads
    assert(input.decoder.decode(10L).contains(Event.EndOfInput))
    assert(input.decoder.decode(10L).contains(Event.EndOfInput))
    assert(input.reads == reads)

  test("an oversized interrupted paste stays truncated and drains through its terminator"):
    val limit   = 1 << 20
    val payload = "a" * limit + "q\nignored"
    val script  = ("\u001b[200~" + payload + "\u001b[201~x").map(_.toInt).toIndexedSeq
    val input   = InterruptingScript(script, List(6 + limit - 1, 6 + limit + 2), InputDecoder.ReadExpired)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).isEmpty)
    assert(input.decoder.decode(10L).contains(Event.Paste("a" * limit)))
    assert(input.decoder.decode(10L).contains(Event.Key(KeyEvent.char('x'))))
    assert(input.decoder.decode(10L).isEmpty)

  test("an interrupted runaway paste retains its cumulative drain limit"):
    val opening    = "\u001b[200~"
    val limit      = 1 << 20
    val drainLimit = limit * 16
    var consumed   = 0
    var interrupts = List(opening.length + limit, opening.length + drainLimit - 1)
    val input      = InputDecoder { _ =>
      if interrupts.headOption.contains(consumed) then
        interrupts = interrupts.tail
        throw new InterruptedIOException("wake")
      else
        val c = if consumed < opening.length then opening.charAt(consumed).toInt else 'a'.toInt
        consumed += 1
        c
    }
    assert(input.decode(10L).isEmpty)
    assert(input.decode(10L).isEmpty)
    assert(input.decode(10L).contains(Event.Paste("a" * limit)))
    assert(consumed == opening.length + drainLimit)

  test("wake and signal delivery through EventPump preserve an in-flight paste"):
    val actions: List[(EventPump => Unit, Option[Event])] = List(
      (pump => pump.wake(), None),
      (pump => pump.postInterrupt(), Some(Event.Interrupt)),
      (pump => pump.postResize(Size(80, 24)), Some(Event.Resize(Size(80, 24)))),
    )
    for (wake, pending) <- actions do
      val ready   = CountDownLatch(1)
      val release = CountDownLatch(1)
      val script  = "\u001b[200~aq\n\u001b[201~x"
      var at      = 0
      var blocked = false
      val input   = InputDecoder { _ =>
        if at == 7 && !blocked then
          blocked = true
          ready.countDown()
          try if !release.await(2L, TimeUnit.SECONDS) then throw new InterruptedIOException("gate timed out")
          catch case _: InterruptedException => throw new InterruptedIOException("wake")
        if at < script.length then
          val c = script.charAt(at).toInt
          at += 1
          c
        else InputDecoder.ReadExpired
      }
      val pump    = EventPump(input)
      val result  = AtomicReference[Option[Either[BackendError, Option[Event]]]](None)
      val reader  = Thread(() => result.set(Some(pump.poll(2.seconds))), "interrupted-paste-reader")
      reader.start()
      try
        assert(ready.await(2L, TimeUnit.SECONDS), "reader never reached the paste payload")
        wake(pump)
        reader.join(2000L)
        assert(!reader.isAlive, "wake did not release the paste read")
        assert(result.get().contains(Right(None)))
        pending.foreach(event => assert(pump.poll(2.seconds) == Right(Some(event))))
        assert(pump.poll(2.seconds) == Right(None)) // the wake flag is drained before another read
        assert(pump.poll(2.seconds) == Right(Some(Event.Paste("aq\n"))))
        assert(pump.poll(2.seconds) == Right(Some(Event.Key(KeyEvent.char('x')))))
      finally
        release.countDown()
        reader.interrupt()
        reader.join(2000L)

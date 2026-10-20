package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent}

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.jline.utils.NonBlockingReader
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.{Duration, DurationInt}

final class InputHandoverSpec extends AnyFunSuite:
  test("suspend joins the event reader, pauses JLine, and retains interrupt plus interrupted paste resynchronization"):
    val sink                     = ByteArrayOutputStream()
    val prefix                   = "\u001b[200~prefix"
    val readingPayload           = CountDownLatch(1)
    var reads                    = 0
    val polling                  = AtomicBoolean(false)
    var pauses                   = 0
    var resumes                  = 0
    var isPaused                 = false
    val terminal                 =
      new LineDisciplineTerminal("input-handover", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN):
        private lazy val trackedReader: NonBlockingReader =
          val delegate = super.reader()
          new NonBlockingReader:
            override protected def read(timeout: Long, peek: Boolean): Int                              =
              polling.set(true)
              try
                reads += 1
                if reads == prefix.length + 1 then readingPayload.countDown()
                if peek then delegate.peek(timeout) else delegate.read(timeout)
              finally polling.set(false)
            override def readBuffered(chars: Array[Char], offset: Int, length: Int, timeout: Long): Int =
              delegate.readBuffered(chars, offset, length, timeout)
        override def reader(): NonBlockingReader          = trackedReader
        override def canPauseResume(): Boolean            = true
        override def paused(): Boolean                    = isPaused
        override def pause(wait: Boolean): Unit           =
          assert(wait)
          assert(!polling.get(), "the decoder must relinquish the input reader before JLine is paused")
          isPaused = true
          pauses += 1
        override def resume(): Unit                       =
          isPaused = false
          resumes += 1
    val backend                  =
      JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, knownCapabilities = Some(TerminalCapabilities.unknown))
    val outcome                  = AtomicReference[Either[BackendError, Option[Event]]]()
    val finished                 = CountDownLatch(1)
    def feed(text: String): Unit = text.getBytes(UTF_8).foreach(value => terminal.processInputByte(value & 0xff))
    try
      assert(backend.enableRawMode().isRight)
      feed(prefix)
      val reader = Thread(() =>
        try outcome.set(backend.readEvent(Duration.Inf))
        finally finished.countDown()
      )
      reader.start()
      assert(readingPayload.await(10, TimeUnit.SECONDS))
      assert(backend.suspend {
        assert(finished.await(10, TimeUnit.SECONDS))
        assert(terminal.paused())
        assert(backend.readEvent(10.millis) == Right(None))
        terminal.raise(Terminal.Signal.INT)
        assert(backend.readEvent(10.millis) == Right(None), "posted signals wait until input ownership returns")
      }.isRight)
      reader.join(10000)
      assert(!reader.isAlive)
      assert(outcome.get() == Right(None))
      assert(pauses == 1 && resumes == 1 && !terminal.paused())
      assert(backend.readEvent(10.millis) == Right(Some(Event.Interrupt)))
      feed("q\n\u001b[201~z")
      val events = (0 until 10).flatMap(_ => backend.readEvent(20.millis).toOption.flatten)
      assert(events.contains(Event.Paste("prefixq\n")))
      assert(events.contains(Event.Key(KeyEvent.char('z'))))
      assert(!events.contains(Event.Key(KeyEvent.char('q'))))
      assert(sink.toString(UTF_8).contains(AnsiSequences.PopKittyKeyboard))
    finally backend.close()

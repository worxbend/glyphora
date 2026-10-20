package io.worxbend.tui.terminal

import org.jline.nativ.CLibrary
import org.jline.terminal.Terminal
import org.jline.terminal.impl.{AbstractUnixSysTerminal, PosixSysTerminal}
import org.jline.utils.{NonBlocking, NonBlockingInputStream, NonBlockingReader}

import java.io.{FileDescriptor, FileInputStream, InterruptedIOException}

/** A synchronous reader cannot steal a subprocess's first key after its caller has stopped polling.
  *
  * JLine 4's flattened Unix terminal uses a background pump even with software signals disabled. In 4.4.5 its declared
  * JNI pollForInput method is missing from the shipped native library, so the pump can remain blocked indefinitely.
  * Poll the JDK descriptor's available bytes synchronously instead, with interruptible bounded waits and JLine's
  * incremental charset decoder. isatty distinguishes a hung-up terminal from an idle one. This path is only for Unix
  * system terminals; the older PTY terminal already exposes a timed stream. Glyphora handles control keys/signals.
  * Other implementations retain their reader and use Terminal.pause(true) where supported during handover.
  */
private[terminal] object TerminalInput:
  // Never close this shared descriptor; one wrapper for the process, not one retained FileDescriptor parent per run.
  private lazy val stdin = FileInputStream(FileDescriptor.in)

  def readerFor(terminal: Terminal): NonBlockingReader =
    terminal match
      case _: AbstractUnixSysTerminal =>
        NonBlocking.nonBlocking(terminal.getName, PolledStdin(), terminal.inputEncoding())
      case system: PosixSysTerminal   =>
        system.getPty.getSlaveInput match
          case input: NonBlockingInputStream =>
            NonBlocking.nonBlocking(terminal.getName, input, terminal.inputEncoding())
          case _                             => terminal.reader()
      case _                          => terminal.reader()

  private final class PolledStdin extends NonBlockingInputStream:
    private var peeked = Option.empty[Int]

    override def read(timeout: Long, peek: Boolean): Int =
      val next = peeked.getOrElse(poll(timeout))
      peeked = if peek && next >= 0 then Some(next) else None
      next

    private def poll(timeout: Long): Int =
      val started   = System.nanoTime()
      var remaining = timeout
      var result    = NonBlockingInputStream.READ_EXPIRED
      var waiting   = true
      while waiting do
        if Thread.interrupted() then throw InterruptedIOException()
        if stdin.available() > 0 then
          result = stdin.read()
          waiting = false
        else if CLibrary.isatty(0) == 0 then
          result = NonBlockingInputStream.EOF
          waiting = false
        else
          val slice = if timeout <= 0 then 5L else math.min(5L, math.max(1L, remaining))
          try Thread.sleep(slice)
          catch case _: InterruptedException => throw InterruptedIOException()
          if timeout > 0 then
            remaining = timeout - (System.nanoTime() - started) / 1000000L
            waiting = remaining > 0
      result

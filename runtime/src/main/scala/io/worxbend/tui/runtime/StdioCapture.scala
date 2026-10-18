package io.worxbend.tui.runtime

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8

/** The stream swap behind [[CaptureStdio]], for the lifetime of one [[TerminalRunner.run]].
  *
  * [[install]] replaces `System.out`/`System.err` with line-buffered streams whose completed lines go to `outSink` and
  * `errSink`; [[restore]] puts the originals back and flushes whatever partial line was still buffered to them.
  * [[passthrough]] routes writes straight to the originals for the duration of its body — what `suspend` needs.
  *
  * Scala's `Console.out` is a dynamic variable initialised from `System.out` once, so `System.setOut` alone misses
  * every Scala `println`; the runner therefore also binds `Console.withOut(outStream)`/`Console.withErr(errStream)`
  * around the loop, which covers the render thread and every thread it starts from then on.
  *
  * The two `PrintStream`s are created with autoflush on, and the underlying stream's `flush` deliberately does nothing
  * while capturing: `print("a"); println("b")` must arrive as one line `ab`, not as `a` flushed to the original and `b`
  * captured. Restoration is defensive about ownership — a stream is put back only if ours is still the one installed —
  * and a stream left installed by an overlapping run keeps passing writes through to its original after [[restore]].
  *
  * Owned by the run that installed it; [[install]] and [[restore]] are called on the render thread, the streams
  * themselves are written from any thread and synchronise internally.
  */
private[runtime] final class StdioCapture(outSink: String => Unit, errSink: String => Unit):

  /** The streams that were installed when this capture was created, and that [[restore]] puts back. */
  val priorOut: PrintStream = System.out
  val priorErr: PrintStream = System.err

  private val out = StdioCapture.LineStream(outSink, priorOut)
  private val err = StdioCapture.LineStream(errSink, priorErr)

  val outStream: PrintStream = PrintStream(out, true, UTF_8)
  val errStream: PrintStream = PrintStream(err, true, UTF_8)

  def install(): Unit =
    System.setOut(outStream)
    System.setErr(errStream)

  /** Runs `body` with both streams passing straight through to the originals, the buffered partial lines flushed to
    * them first.
    */
  def passthrough[A](body: => A): A =
    out.beginPassthrough()
    err.beginPassthrough()
    try body
    finally
      out.endPassthrough()
      err.endPassthrough()

  /** Puts the original streams back (where ours are still installed), flushes the buffered partial lines to them and
    * leaves ours passing through for good, so a write through a stale reference still reaches the terminal.
    */
  def restore(): Unit =
    if System.out eq outStream then System.setOut(priorOut)
    if System.err eq errStream then System.setErr(priorErr)
    out.release()
    err.release()

private[runtime] object StdioCapture:

  /** Runs `body` under `capture`'s passthrough when there is one, plainly otherwise. */
  def passingThrough[A](capture: Option[StdioCapture])(body: => A): A =
    capture match
      case Some(active) => active.passthrough(body)
      case None         => body

  /** Buffers bytes until a newline, then hands the decoded line (without its terminator, a trailing `\r` trimmed) to
    * `sink`. In passthrough mode every byte goes straight to `original` instead. All methods synchronise on the stream,
    * so lines written concurrently from two threads come out whole, if interleaved by line.
    */
  private final class LineStream(sink: String => Unit, original: PrintStream) extends OutputStream:

    private val pending     = new ByteArrayOutputStream
    private var passthrough = false
    private var released    = false

    override def write(b: Int): Unit = synchronized {
      if passthrough then original.write(b)
      else if b == '\n' then emitPending()
      else pending.write(b)
    }

    override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = synchronized {
      if passthrough then original.write(bytes, offset, length)
      else
        var index = offset
        val end   = offset + length
        while index < end do
          val b = bytes(index)
          if b == '\n' then emitPending() else pending.write(b.toInt)
          index += 1
    }

    /** Nothing to do while capturing: a flush must not break a line that is still being written. */
    override def flush(): Unit = synchronized { if passthrough then original.flush() }

    def beginPassthrough(): Unit = synchronized {
      flushPendingToOriginal()
      passthrough = true
    }

    def endPassthrough(): Unit = synchronized { if !released then passthrough = false }

    def release(): Unit = synchronized {
      flushPendingToOriginal()
      passthrough = true
      released = true
    }

    private def emitPending(): Unit =
      val text = pending.toString(UTF_8)
      pending.reset()
      sink(text.stripSuffix("\r"))

    private def flushPendingToOriginal(): Unit =
      if pending.size() > 0 then
        pending.writeTo(original)
        pending.reset()
        original.flush()

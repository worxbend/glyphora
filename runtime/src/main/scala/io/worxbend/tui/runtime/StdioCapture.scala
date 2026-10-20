package io.worxbend.tui.runtime

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.logging.{ConsoleHandler, Logger, StreamHandler}

/** The stream swap behind [[CaptureStdio]], for the lifetime of one [[TerminalRunner.run]].
  *
  * [[install]] replaces `System.out`/`System.err` with line-buffered streams whose completed lines go to `outSink` and
  * `errSink`; [[restore]] returns to the newest surviving capture, or the original streams after the last capture, and
  * flushes whatever partial line was still buffered to this capture's predecessors. [[passthrough]] routes writes
  * straight to the originals for the duration of its body — what `suspend` needs.
  *
  * Scala's `Console.out` is a dynamic variable initialised from `System.out` once, so `System.setOut` alone misses
  * every Scala `println`; the runner therefore also binds `Console.withOut(outStream)`/`Console.withErr(errStream)`
  * around the whole lifecycle. Toolkit executor threads inherit a stable forwarding binding, so a reused worker does
  * not retain the capture of its first run. Third-party existing pools must opt in with Console.withOut/withErr.
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

  /** The construction-time predecessors used for passthrough and writes through retired stream references. */
  val priorOut: PrintStream = System.out
  val priorErr: PrintStream = System.err

  private val out = StdioCapture.LineStream(outSink, priorOut)
  private val err = StdioCapture.LineStream(errSink, priorErr)

  val outStream: PrintStream = PrintStream(out, true, UTF_8)
  val errStream: PrintStream = PrintStream(err, true, UTF_8)

  // Guarded by the process-global JUL ownership monitor, not by a particular render thread.
  private var installed = false

  def install(): Unit = StdioCapture.julOwnership.synchronized {
    if !installed then
      // Force lazy root-handler initialization before swapping stderr.
      val root     = Logger.getLogger("")
      val handlers = root.getHandlers.collect { case handler: ConsoleHandler => handler }.toVector
      StdioCapture.acquireJul(this, root, handlers)
      System.setOut(outStream)
      System.setErr(errStream)
      installed = true
  }

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

  /** Returns installed streams to the newest live capture or their original owner, flushes partial lines to this
    * capture's predecessors and leaves ours passing through for good, so stale references still reach the terminal.
    */
  def restore(): Unit = StdioCapture.julOwnership.synchronized {
    if installed then
      StdioCapture.releaseJul(this)
      installed = false
    out.release()
    err.release()
  }

private[runtime] object StdioCapture:

  // Only the first capture replaces root handlers; only the last restores them. Per-run snapshots would reinstall
  // raw or already-retired handlers when concurrent runners finish out of order.
  private val julOwnership = Object()
  private var julOwners    = Vector.empty[StdioCapture]
  private var priorStreams = Option.empty[(PrintStream, PrintStream)]
  private var julHandlers  = Vector.empty[(ConsoleHandler, StreamHandler)]

  private def acquireJul(capture: StdioCapture, root: Logger, handlers: Vector[ConsoleHandler]): Unit =
    if julOwners.isEmpty then
      priorStreams = Some((System.out, System.err))
      julHandlers = handlers.map { original =>
        // ConsoleHandler pins stderr at construction. Route each write instead so the surviving capture receives
        // records regardless of teardown order. Never close a handler here: that could close a caller-owned stream.
        val replacement = new StreamHandler(currentErr, original.getFormatter):
          override def publish(record: java.util.logging.LogRecord): Unit =
            // A bridge owns records directly, without a second text copy.
            if !JulBridge.attachedTo(root) then
              super.publish(record)
              flush() // match ConsoleHandler's per-record flush
        replacement.setLevel(original.getLevel)
        replacement.setFilter(original.getFilter)
        replacement.setEncoding(original.getEncoding)
        root.removeHandler(original)
        root.addHandler(replacement)
        (original, replacement)
      }
    julOwners :+= capture

  private def releaseJul(capture: StdioCapture): Unit =
    julOwners = julOwners.filterNot(_ eq capture)
    // Restore the newest *live* capture, not this run's construction-time snapshot (which may be raw or retired).
    // Leave caller-installed streams alone, just as the individual capture did before coordination.
    priorStreams.foreach { (out, err) =>
      if System.out eq capture.outStream then System.setOut(julOwners.lastOption.fold(out)(_.outStream))
      if System.err eq capture.errStream then System.setErr(julOwners.lastOption.fold(err)(_.errStream))
    }
    if julOwners.isEmpty then
      val root = Logger.getLogger("")
      julHandlers.foreach { (original, replacement) =>
        root.removeHandler(replacement)
        root.addHandler(original)
      }
      julHandlers = Vector.empty
      priorStreams = None

  // Toolkit-owned process-lifetime executors must not retain the capture inherited from their first run.
  // Delegation happens per write, so child threads inherit a stable router rather than a retired capture.
  private def forwarding(stream: () => PrintStream): PrintStream =
    PrintStream(
      new OutputStream:
        override def write(value: Int): Unit                                   = stream().write(value)
        override def write(bytes: Array[Byte], offset: Int, length: Int): Unit = stream().write(bytes, offset, length)
        override def flush(): Unit                                             = stream().flush()
      ,
      true,
      UTF_8,
    )

  private val currentOut = forwarding(() => System.out)
  private val currentErr = forwarding(() => System.err)

  def withCurrentSystemStreams[A](body: => A): A =
    Console.withOut(currentOut)(Console.withErr(currentErr)(body))

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
      flushPendingToOriginal(terminate = true)
      passthrough = true
    }

    def endPassthrough(): Unit = synchronized { if !released then passthrough = false }

    def release(): Unit = synchronized {
      flushPendingToOriginal()
      passthrough = true
      released = true
    }

    private def emitPending(): Unit =
      val text      = pending.toString(UTF_8)
      pending.reset()
      val completed = text.stripSuffix("\r") // CRLF terminates the preceding update, not an empty one
      sink(completed.drop(completed.lastIndexOf('\r') + 1))

    private def flushPendingToOriginal(terminate: Boolean = false): Unit =
      if pending.size() > 0 then
        pending.writeTo(original)
        pending.reset()
        if terminate then original.println()
        original.flush()

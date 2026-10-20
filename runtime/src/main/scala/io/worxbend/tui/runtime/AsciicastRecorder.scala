package io.worxbend.tui.runtime

import io.worxbend.tui.core.Buffer
import io.worxbend.tui.terminal.{ColorDepth, FrameEncoder}

import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch}
import scala.util.control.NonFatal

/** Writes the frames of one run as an [[https://docs.asciinema.org/manual/asciicast/v2/ asciicast v2]] recording: a
  * JSON header line, then one `[time, "o", data]` event per frame that changed something on screen.
  *
  * `data` is the ANSI a real terminal would have received — `FrameEncoder`'s diff against the previous frame, and a
  * clear-screen plus full repaint for the first frame and after a resize — so `asciinema play` and the web player show
  * exactly what the terminal showed, and a frame that changed nothing writes no event at all. Timestamps are seconds
  * since the first frame, read from `nanoTime` when the frame is handed in; `epochSeconds` stamps the header the way
  * the format expects.
  *
  * Ownership and threading: [[apply]] is called by one thread — in a run configured with `RunnerConfig.withRecording`
  * that is the render thread, inside `onFrame` — and does no I/O there: it stamps the frame and hands it to a bounded
  * queue drained by a daemon writer thread this recorder owns, which encodes the diff and writes it. A full queue makes
  * `apply` wait for room, so a disk slower than the frame rate slows the app down rather than growing memory or
  * dropping frames. [[flush]] waits until every frame handed in so far is written and flushed (the runner calls it
  * before `suspend` hands the terminal over); [[close]] does the same, closes the writer and stops the thread, and the
  * runner calls it on every exit path. An I/O failure on the writer thread is kept and rethrown on the caller at the
  * next `apply`, `flush` or `close` — on the render thread it ends the run as `RunnerError.Handler` rather than
  * silently producing a truncated recording. Frames handed in must not be mutated afterwards — the runner's snapshots
  * never are.
  */
final class AsciicastRecorder(out: Writer, nanoTime: () => Long, epochSeconds: () => Long)
    extends (CompletedFrame => Unit)
    with AutoCloseable:

  import AsciicastRecorder.Job

  private val queue                                = ArrayBlockingQueue[Job](AsciicastRecorder.QueueCapacity)
  @volatile private var failure: Option[Throwable] = None
  private var closed                               = false // the calling thread's own view; the writer never reads it

  // writer-thread state: only `writeFrame` touches these
  private val encoder                  = FrameEncoder(ColorDepth.TrueColor)
  private var previous: Option[Buffer] = None
  private var startedAt                = 0L

  private val writer: Thread =
    val thread = Thread(() => drain(), "glyphora-asciicast-writer")
    thread.setDaemon(true)
    thread.start()
    thread

  /** Records `frame`: stamps it now and queues it for the writer thread, which writes the header on the first frame and
    * then the diff that moves the screen to this one.
    */
  def apply(frame: CompletedFrame): Unit =
    if !closed then
      rethrowFailure()
      enqueue(Job.Record(frame, nanoTime()))

  /** Waits until every frame handed in so far is written and the writer flushed. A no-op once closed. */
  def flush(): Unit =
    if !closed then
      val done = CountDownLatch(1)
      enqueue(Job.Flush(done))
      awaitUninterruptibly(done.await())
      rethrowFailure()

  /** Writes what is queued, flushes and closes the writer, and stops the writer thread; later frames are ignored.
    * Idempotent.
    */
  def close(): Unit =
    if !closed then
      closed = true
      enqueue(Job.Stop)
      awaitUninterruptibly(writer.join())
      rethrowFailure()

  private def enqueue(job: Job): Unit = awaitUninterruptibly(queue.put(job))

  private def rethrowFailure(): Unit = failure.foreach(error => throw error)

  /** Runs `block`, retrying it when the calling thread is interrupted, and restores the interrupt afterwards — losing a
    * frame or leaving the writer running because a handler happened to be interrupted would be the worse outcome.
    */
  private def awaitUninterruptibly(block: => Unit): Unit =
    var interrupted = false
    var finished    = false
    while !finished do
      try
        block
        finished = true
      catch case _: InterruptedException => interrupted = true
    if interrupted then Thread.currentThread().interrupt()

  /** The writer thread's loop: one job at a time until `Stop`. After a failure it keeps taking jobs, so a caller
    * blocked on a full queue or a flush is released, but writes nothing more.
    */
  private def drain(): Unit =
    var running = true
    while running do
      queue.take() match
        case Job.Record(frame, at) => guarded(writeFrame(frame, at))
        case Job.Flush(done)       =>
          guarded(out.flush())
          done.countDown()
        case Job.Stop              =>
          try out.close()
          catch case NonFatal(error) => if failure.isEmpty then failure = Some(error)
          running = false

  private def guarded(step: => Unit): Unit =
    if failure.isEmpty then
      try step
      catch case NonFatal(error) => failure = Some(error)

  private def writeFrame(frame: CompletedFrame, now: Long): Unit =
    if previous.isEmpty then
      startedAt = now
      out.write(
        s"""{"version":2,"width":${frame.area.width},"height":${frame.area.height},"timestamp":${epochSeconds()},""" +
          s""""env":{"TERM":"xterm-256color","SHELL":"/bin/sh"}}\n"""
      )
    val elapsed = String.format(Locale.ROOT, "%.6f", Double.box((now - startedAt) / 1e9))
    previous.filter(_.area.size != frame.area.size).foreach { _ =>
      out.write(s"""[$elapsed,"r","${frame.area.width}x${frame.area.height}"]\n""")
    }
    val data    = previous match
      case Some(shown) if shown.area == frame.area => encoder.encode(shown, frame.buffer)
      case _                                       => AsciicastRecorder.Preamble + encoder.encodeAll(frame.buffer)
    if data.nonEmpty then out.write(s"""[$elapsed,"o",${AsciicastRecorder.jsonString(data)}]\n""")
    previous = Some(frame.buffer)

object AsciicastRecorder:

  /** How many frames may wait for the writer thread before [[AsciicastRecorder.apply]] waits for room. */
  private val QueueCapacity: Int = 256

  /** What the calling thread hands the writer thread. */
  private enum Job:
    case Record(frame: CompletedFrame, at: Long)
    case Flush(done: CountDownLatch)
    case Stop

  /** The JVM system property that names a recording file when the configuration does not: `-Dglyphora.record=x.cast`.
    */
  val SystemProperty: String = "glyphora.record"

  /** Cursor home, clear screen, hide cursor — what a frame drawn against an unknown screen is preceded by. Written out
    * here rather than taken from `terminal.AnsiSequences`, which stays private to its package.
    */
  private val Preamble: String = "\u001b[H\u001b[2J\u001b[?25l"

  /** A recorder writing to `path`, created or truncated, in UTF-8. */
  def toFile(
      path: Path,
      nanoTime: () => Long = () => System.nanoTime(),
      epochSeconds: () => Long = () => System.currentTimeMillis() / 1000L,
  ): AsciicastRecorder =
    AsciicastRecorder(Files.newBufferedWriter(path, StandardCharsets.UTF_8), nanoTime, epochSeconds)

  /** The file `-Dglyphora.record` names, if the property is set to something non-empty. */
  def systemPropertyPath: Option[Path] = sys.props.get(SystemProperty).filter(_.nonEmpty).map(Path.of(_))

  /** `text` as a JSON string literal: quotes and backslashes escaped, every control character — ESC above all — as a
    * `\\u00XX` escape, everything else written as the UTF-8 the file is in.
    */
  private[runtime] def jsonString(text: String): String =
    val out = StringBuilder("\"")
    text.foreach {
      case '"'           => out ++= "\\\""
      case '\\'          => out ++= "\\\\"
      case '\n'          => out ++= "\\n"
      case '\r'          => out ++= "\\r"
      case '\t'          => out ++= "\\t"
      case c if c < 0x20 => out ++= f"\\u${c.toInt}%04x"
      case other         => out += other
    }
    (out += '"').result()

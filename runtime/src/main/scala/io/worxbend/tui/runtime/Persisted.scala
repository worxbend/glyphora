package io.worxbend.tui.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal

/** A [[Signal]] whose value survives a restart: loaded from a file when the signal is created, written back whenever
  * the signal changes.
  *
  * {{{
  * private val layout = Persisted.signal(configDir.resolve("layout.txt"), Layout.Default)(codec.encode, codec.decode)
  * }}}
  *
  * The result is an ordinary `Signal` — read it, write it and subscribe to it like any other. What is added is a
  * subscriber that, on every change, hands the *encoded* current value to [[Async]] and writes it on a worker thread,
  * so the render thread never blocks on disk. Writes coalesce: a burst of changes produces at most one write in flight
  * and one pending, and the pending one always carries the newest value, so the file ends holding the last state and
  * never an intermediate one. Each write goes to a sibling temporary file and is moved into place atomically, so a
  * crash mid-write leaves the previous file intact rather than a truncated one. Missing parent directories are created.
  *
  * Loading is synchronous, on the thread creating the signal — a constructor or `onStart`, before the first frame. A
  * missing file, an unreadable one or text `decode` rejects all yield `initial`: a persisted value is a convenience the
  * app must be able to run without, so the file is never a reason not to start. A write failure is reported the way a
  * failing `Async.run` is — to the render loop that owns the signal, which surfaces it as `RunnerError.QueuedTask` when
  * the app exits — and the signal keeps its in-memory value regardless. A drain reports its first write failure,
  * attempts newer pending values, and releases its gate so later changes can recover; it does not retain an unbounded
  * list of failures during a burst.
  *
  * Ownership and threads: the render-thread rules of `Signal` are unchanged; `set` is a render-thread call. The encoded
  * text is produced on the render thread at change time, so `encode` sees a consistent value, and only the finished
  * `String` crosses to the worker. The file is this signal's: two signals on one path would overwrite each other.
  */
object Persisted:

  /** A signal bound to `path`. `encode` and `decode` are the two halves of the file format — `Codec.encode`/`decode`
    * from `tui-macros` fit, and so does any pair that round-trips through a `String`.
    */
  def signal[A](path: Path, initial: A)(encode: A => String, decode: String => Either[String, A])(using
      SignalEquality[A]
  ): Signal[A] =
    val loaded = load(path).flatMap(text => decode(text).toOption).getOrElse(initial)
    val value  = Signal(loaded)
    val writer = new Writer(path)
    value.subscribe(new Subscriber:
      def markStale(): Unit = writer.schedule(encode(value.peek)))
    value

  private def load(path: Path): Option[String] =
    try if Files.isRegularFile(path) then Some(Files.readString(path, StandardCharsets.UTF_8)) else None
    catch case NonFatal(_) => None

  /** Single-flight, latest-wins file writer. `pending` holds the newest text not yet on disk; `inFlight` is whether a
    * worker is currently draining it. Both are atomics because `schedule` runs on the render thread and `drain` on a
    * worker.
    */
  private final class Writer(path: Path):
    private val pending  = AtomicReference(Option.empty[String])
    private val inFlight = AtomicBoolean(false)

    def schedule(text: String): Unit =
      pending.set(Some(text))
      if inFlight.compareAndSet(false, true) then
        try Async.run(drain())(_ => ())
        catch
          case NonFatal(error) =>
            inFlight.set(false)
            throw error

    private def drain(): Unit =
      var failure  = Option.empty[Throwable]
      var draining = true
      try
        while draining do
          pending.getAndSet(None) match
            case Some(text) =>
              try write(text)
              catch
                case NonFatal(error) =>
                  failure match
                    case None    => failure = Some(error)
                    case Some(_) => () // one reported failure per coalesced drain; error retention is bounded too
            case None       =>
              inFlight.set(false)
              // Close the empty-read/release race without scheduling worker work under the wrong render owner.
              draining = pending.get().isDefined && inFlight.compareAndSet(false, true)
      catch
        case error: Throwable =>
          inFlight.set(false)
          throw error
      // Report the first failure through this Async invocation, after releasing the gate and attempting newer values.
      failure.foreach(error => throw error)

    private def write(text: String): Unit =
      Option(path.toAbsolutePath.getParent).foreach(parent => { val _ = Files.createDirectories(parent) })
      val temp = Files.createTempFile(path.toAbsolutePath.getParent, s".${path.getFileName}", ".tmp")
      try
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        val _ = Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      finally
        val _ = Files.deleteIfExists(temp)

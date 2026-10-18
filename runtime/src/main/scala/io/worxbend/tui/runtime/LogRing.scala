package io.worxbend.tui.runtime

import java.time.Instant
import scala.collection.mutable

/** Severity of a [[LogRecord]], in ascending order so `level.ordinal >= LogLevel.Warning.ordinal` is "warning or
  * worse". Five steps rather than `java.util.logging`'s seven, because a console filter needs a coarse ladder a user
  * can read, not FINER versus FINEST; [[JulBridge]] folds the JUL levels onto these.
  */
enum LogLevel:
  case Trace, Debug, Info, Warning, Error

  /** Whether this level is at least `floor`: what a console filter asks. */
  def atLeast(floor: LogLevel): Boolean = ordinal >= floor.ordinal

/** One captured log line: when it was recorded, how severe it is, which logger (or stream) it came from, the message,
  * and the throwable attached to it, when there was one. An immutable value; the thread that produced it is gone by the
  * time anything reads it.
  */
final case class LogRecord(
    instant: Instant,
    level: LogLevel,
    logger: String,
    message: String,
    throwable: Option[Throwable] = None,
)

/** A thread-safe bounded ring of [[LogRecord]]s: appending past `capacity` drops the oldest record.
  *
  * Owned by the application, which creates it once and hands it to whatever produces records — [[JulBridge.attach]], a
  * `CaptureStdio.Console`, its own [[log]] calls — and to the `logConsole` element that displays them. Every method is
  * safe from any thread; readers get an immutable snapshot and never observe a half-written record. Appending is a
  * mutation the reactive layer cannot see: a console that must repaint the moment a record arrives pairs the append
  * with a `requestRedraw()` on the render thread, or lets the next tick or event pick it up.
  */
final class LogRing(val capacity: Int = 1000):

  require(capacity > 0, s"a log ring needs a positive capacity, got $capacity")

  private val records = mutable.ArrayDeque[LogRecord]()

  /** Appends `record`, dropping the oldest record once the ring is full. */
  def append(record: LogRecord): Unit = records.synchronized {
    records.append(record)
    while records.size > capacity do
      val _ = records.removeHead()
  }

  /** Records a message at `level` under `logger`, stamped now. */
  def log(level: LogLevel, logger: String, message: String, throwable: Option[Throwable] = None): Unit =
    append(LogRecord(Instant.now(), level, logger, message, throwable))

  /** The records currently held, oldest first — an immutable snapshot. */
  def snapshot: Vector[LogRecord] = records.synchronized(records.toVector)

  def size: Int = records.synchronized(records.size)

  def clear(): Unit = records.synchronized(records.clear())

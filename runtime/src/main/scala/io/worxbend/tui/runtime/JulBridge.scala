package io.worxbend.tui.runtime

import java.time.Instant
import java.util.logging.{Handler, Level, Logger}

/** Feeds `java.util.logging` into a [[LogRing]] through a plain [[Handler]] on a logger — the root logger by default.
  *
  * A `Handler` rather than a `System.LoggerFinder`: the finder is discovered through `ServiceLoader`, which needs
  * reflection configuration under native-image, while adding a handler to a logger is an ordinary method call that
  * works the same in a JVM and in a `--no-fallback` binary. SLF4J users route through `jul-to-slf4j` or `slf4j-jdk14`
  * as they already do; there is no SLF4J artifact here.
  *
  * The handler publishes from whatever thread logged, and the ring is thread-safe, so nothing is marshalled. It does
  * not change the logger's own level: a `fine` on a logger left at `INFO` is dropped before it ever reaches the
  * handler, exactly as it would be for a `ConsoleHandler`. Lower the logger's level to see it.
  */
object JulBridge:

  /** Attaches a handler that appends every record `logger` publishes to `ring`. Cancelling the result removes it. */
  def attach(ring: LogRing, logger: Logger = Logger.getLogger("")): Cancelable =
    val handler = RingHandler(ring)
    handler.setLevel(Level.ALL)
    logger.addHandler(handler)
    () => logger.removeHandler(handler)

  private[runtime] def attachedTo(logger: Logger): Boolean = logger.getHandlers.exists {
    case _: RingHandler => true
    case _              => false
  }

  /** JUL's seven levels folded onto the five [[LogLevel]]s: `FINEST`/`FINER` are trace, `FINE`/`CONFIG` debug, and a
    * custom level is placed by its integer value against the standard ones.
    */
  def levelOf(level: Level): LogLevel =
    val value = level.intValue
    if value >= Level.SEVERE.intValue then LogLevel.Error
    else if value >= Level.WARNING.intValue then LogLevel.Warning
    else if value >= Level.INFO.intValue then LogLevel.Info
    else if value >= Level.FINE.intValue then LogLevel.Debug
    else LogLevel.Trace

  private final class RingHandler(ring: LogRing) extends Handler:

    override def publish(record: java.util.logging.LogRecord): Unit =
      if record != null then // scalafix:ok DisableSyntax; java.util.logging passes null to mean "nothing to publish"
        val message = Option(getFormatter).fold(formatMessage(record))(_.formatMessage(record))
        ring.append(
          LogRecord(
            Instant.ofEpochMilli(record.getMillis),
            levelOf(record.getLevel),
            Option(record.getLoggerName).getOrElse(""),
            Option(message).getOrElse(""),
            Option(record.getThrown),
          )
        )

    /** What `java.util.logging.Formatter.formatMessage` does — resource-bundle lookup and `{0}` substitution — without
      * requiring a formatter to be installed on the handler first.
      */
    private def formatMessage(record: java.util.logging.LogRecord): String =
      JulBridge.PlainFormatter.formatMessage(record)

    override def flush(): Unit = ()

    override def close(): Unit = ()

  /** A formatter used only for its `formatMessage`, which is the one part of `Formatter` that is not abstract. */
  private object PlainFormatter extends java.util.logging.Formatter:
    override def format(record: java.util.logging.LogRecord): String = formatMessage(record)

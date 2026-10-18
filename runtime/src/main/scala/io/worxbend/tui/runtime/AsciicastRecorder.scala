package io.worxbend.tui.runtime

import io.worxbend.tui.core.Buffer
import io.worxbend.tui.terminal.{ColorDepth, FrameEncoder}

import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale

/** Writes the frames of one run as an [[https://docs.asciinema.org/manual/asciicast/v2/ asciicast v2]] recording: a
  * JSON header line, then one `[time, "o", data]` event per frame that changed something on screen.
  *
  * `data` is the ANSI a real terminal would have received — `FrameEncoder`'s diff against the previous frame, and a
  * clear-screen plus full repaint for the first frame and after a resize — so `asciinema play` and the web player show
  * exactly what the terminal showed, and a frame that changed nothing writes no event at all. Timestamps are seconds
  * since the first frame, read from `nanoTime`; `epochSeconds` stamps the header the way the format expects.
  *
  * Ownership and threading: the recorder is confined to whichever thread calls [[apply]] — in a run configured with
  * `RunnerConfig.withRecording` that is the render thread, synchronously inside `onFrame`, so a frame is on disk by the
  * time the next one is composed. Each event is one buffered write; an I/O failure propagates out of `apply` and, on
  * the render thread, ends the run as `RunnerError.Handler` rather than silently producing a truncated recording. The
  * caller that opened the recorder closes it; `TerminalRunner` closes the one it opened during teardown. Frames handed
  * in are retained until the next frame arrives and must not be mutated afterwards — the runner's snapshots never are.
  */
final class AsciicastRecorder(out: Writer, nanoTime: () => Long, epochSeconds: () => Long)
    extends (CompletedFrame => Unit)
    with AutoCloseable:

  private val encoder                  = FrameEncoder(ColorDepth.TrueColor)
  private var previous: Option[Buffer] = None
  private var startedAt                = 0L
  private var closed                   = false

  /** Records `frame`: the header on the first call, then the diff that moves the screen to this frame. */
  def apply(frame: CompletedFrame): Unit =
    if !closed then
      val now  = nanoTime()
      if previous.isEmpty then
        startedAt = now
        out.write(
          s"""{"version":2,"width":${frame.area.width},"height":${frame.area.height},"timestamp":${epochSeconds()},""" +
            s""""env":{"TERM":"xterm-256color","SHELL":"/bin/sh"}}\n"""
        )
      val data = previous match
        case Some(shown) if shown.area == frame.area => encoder.encode(shown, frame.buffer)
        case _                                       => AsciicastRecorder.Preamble + encoder.encodeAll(frame.buffer)
      if data.nonEmpty then
        val elapsed = String.format(Locale.ROOT, "%.6f", Double.box((now - startedAt) / 1e9))
        out.write(s"""[$elapsed,"o",${AsciicastRecorder.jsonString(data)}]\n""")
      previous = Some(frame.buffer)

  /** Flushes and closes the writer; later frames are ignored. Idempotent. */
  def close(): Unit =
    if !closed then
      closed = true
      out.close()

object AsciicastRecorder:

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

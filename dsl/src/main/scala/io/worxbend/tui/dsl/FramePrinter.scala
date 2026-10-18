package io.worxbend.tui.dsl

import io.worxbend.tui.core.Buffer
import io.worxbend.tui.runtime.NonInteractivePolicy
import io.worxbend.tui.terminal.{ColorDepth, FrameEncoder}

/** The frame sink of a no-TTY run: turns the frames a [[PrintBackend]] hands over into what the chosen
  * [[NonInteractivePolicy]] writes.
  *
  * One instance per run, owned by `TuiApp.run()`. [[frame]] is called on the render thread for every frame the loop
  * flushes, in order; [[finish]] once, after the loop has ended, on the thread that called `run()`. Nothing here is
  * shared across runs or threads, so there is no synchronisation.
  *
  *   - `PrintEveryFrame` writes each frame as it arrives, encoded for `colors`, newline-terminated.
  *   - `PrintFinalFrame` keeps the latest frame and writes it on [[finish]] — so a run that composed several frames
  *     before it ended (an `onStart` that changed a signal, say) prints only what it ended on.
  *   - `Plain` writes the text rows of each frame with no styling, trailing blank rows dropped, and skips a frame whose
  *     rows are identical to the last ones written — the de-duplication a screen reader or a log needs, where a frame
  *     that changed nothing is a page of noise.
  *   - `Fail` writes nothing; the policy never reaches here, because `run()` takes the TTY path for it.
  */
private[dsl] final class FramePrinter(policy: NonInteractivePolicy, colors: ColorDepth, write: String => Unit):

  private var latest: Option[Buffer]             = None
  private var lastPlainRows: Option[Seq[String]] = None

  /** One flushed frame; `buffer` is a snapshot the caller does not mutate afterwards. */
  def frame(buffer: Buffer): Unit =
    policy match
      case NonInteractivePolicy.Fail            => ()
      case NonInteractivePolicy.PrintFinalFrame => latest = Some(buffer)
      case NonInteractivePolicy.PrintEveryFrame => write(FrameEncoder.encodeLines(buffer, colors) + "\n")
      case NonInteractivePolicy.Plain           =>
        val rows = FrameEncoder.plainLines(buffer).reverse.dropWhile(_.isEmpty).reverse
        if !lastPlainRows.contains(rows) then
          write(rows.mkString("", "\n", "\n"))
          lastPlainRows = Some(rows)

  /** The run is over: writes what the policy held back. */
  def finish(): Unit =
    latest.foreach(buffer => write(FrameEncoder.encodeLines(buffer, colors) + "\n"))
    latest = None

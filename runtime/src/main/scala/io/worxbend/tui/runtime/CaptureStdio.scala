package io.worxbend.tui.runtime

/** What a run does with `System.out` and `System.err` while it owns the terminal.
  *
  * Ordinary `println` writes straight into the screen the runner is repainting: the line lands wherever the cursor
  * happens to be, the next diff paints over part of it, and the user is left with a corrupted frame and no log. While
  * the app runs under [[PrintAbove]] or [[Console]], both streams are swapped for line-buffered streams and the two
  * Scala `Console` dynamic variables are bound throughout the render-thread lifecycle, including `onStop`. Toolkit
  * worker/timer threads forward Scala output to the current process streams across sequential runs. Root JUL
  * `ConsoleHandler`s are temporarily rebound to captured stderr (or suppressed when a root [[JulBridge]] already
  * records the event), then the original handler instances are restored. A library writing to `System.err` is captured
  * from any thread. Existing third-party threads may retain an older Scala `Console` binding: wrap their task bodies
  * with `scala.Console.withOut(System.out)(scala.Console.withErr(System.err)(...))` to opt in. The originals are
  * restored on every exit path, after the terminal itself has been restored, and whatever was still buffered without a
  * trailing newline is flushed to the real streams at that point — so the output of a run that printed `"done"` and
  * quit appears under the restored shell prompt rather than vanishing with the alternate screen.
  *
  * [[RunnerHandle.suspend]] passes the streams straight through to the originals for the duration of its body: the
  * terminal has been handed back, so writing to it is the right thing again, and the buffered partial line is flushed
  * first, after any queued complete lines, and terminated so it is not glued to the first line the body prints.
  * Captured carriage-return progress keeps the last update of each completed line; CRLF is one line ending.
  *
  * The swap is process-wide — `System.setOut` has no narrower scope — so a second runner in the same JVM sees the first
  * one's streams; each run restores exactly the stream it replaced, and a stream a run left installed keeps passing
  * writes through to its original once that run is over.
  */
enum CaptureStdio:

  /** The streams are left alone. The default of a bare [[RunnerConfig]]. */
  case Off

  /** Each completed line is printed into the terminal scrollback above the live UI through [[RunnerHandle.printAbove]],
    * so it is durable and the frame is untouched. Lines are batched: everything printed between two drains of the
    * render thread's work queue — from any thread — goes out in one scrollback trip, so a flood of output costs one
    * trip per frame rather than one per line. What `TuiApp` does unless its `config` says otherwise.
    */
  case PrintAbove

  /** Each completed line becomes a [[LogRecord]] in `ring` — `stdout` at [[LogLevel.Info]], `stderr` at
    * [[LogLevel.Warning]], the logger named after the stream — for a `logConsole` to show when the user asks for it.
    * The ring is caller-owned and thread-safe, so nothing is marshalled onto the render thread.
    */
  case Console(ring: LogRing)

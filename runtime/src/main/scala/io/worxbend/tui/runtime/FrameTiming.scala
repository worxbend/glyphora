package io.worxbend.tui.runtime

/** Elapsed (wall-clock, not CPU-only) nanoseconds for one successfully completed frame.
  *
  * `compositionNanos` includes the backend size query, buffer preparation and the application's render callback.
  * `drawNanos` covers the synchronous [[io.worxbend.tui.terminal.Backend.draw]] call, including diffing, encoding and
  * writes performed by that backend. `observerNanos` includes snapshot creation and the synchronous `onFrame` callback;
  * it is zero when no frame observer is configured. With [[LatestFrameObserver]] it measures snapshot and admission,
  * not the off-thread callback. These phases exclude input, queued work, cursor placement and the timing callback
  * itself; their sum is not the whole loop iteration. Waiting, scheduling and GC can contribute to every phase.
  *
  * `count` is the same composed-frame number as [[Frame.count]]. Delivered only when composition, draw, observation and
  * cursor placement all succeed. Installing timing alone does not capture any frame snapshots.
  *
  * The same frame is also a Flight Recorder `io.worxbend.tui.FrameDrawn` event whose duration spans composition and
  * draw, with the number of cells that changed — see [[JfrEvents]]. That one needs no callback, only a recording.
  */
final case class FrameTiming(count: Long, compositionNanos: Long, drawNanos: Long, observerNanos: Long)

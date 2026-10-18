package io.worxbend.tui.runtime

import io.worxbend.tui.core.DurationText

import scala.concurrent.duration.FiniteDuration

/** `hh:mm:ss` (or `mm:ss` under an hour) for a non-negative duration — the usual readout for [[Stopwatch]]/[[Timer]].
  * The same text [[io.worxbend.tui.core.DurationText.clock]] prints, which is what the progress columns use.
  */
def formatDuration(duration: FiniteDuration): String = DurationText.clock(duration)

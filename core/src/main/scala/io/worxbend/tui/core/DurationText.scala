package io.worxbend.tui.core

import java.util.Locale

import scala.concurrent.duration.FiniteDuration

/** The clock-face readout of a duration, `mm:ss` under an hour and `h:mm:ss` from one up.
  *
  * It lives in `tui-core` so the progress columns in `tui-widgets` and the `Stopwatch`/`Timer` readouts in
  * `tui-runtime` (`runtime.formatDuration`, which delegates here) print the same moment the same way. Pure, and safe on
  * any thread.
  */
object DurationText:

  private val SecondsPerHour: Long   = 3600L
  private val SecondsPerMinute: Long = 60L

  /** `duration` as `mm:ss`, or `h:mm:ss` from an hour up. Negative durations read as zero; sub-second parts are
    * truncated, so a readout never shows a second that has not fully elapsed.
    */
  def clock(duration: FiniteDuration): String =
    val totalSeconds = math.max(0L, duration.toSeconds)
    val hours        = totalSeconds / SecondsPerHour
    val minutes      = totalSeconds % SecondsPerHour / SecondsPerMinute
    val seconds      = totalSeconds % SecondsPerMinute
    // `String.format(Locale.ROOT, ...)`, not the `f` interpolator: `f"%02d"` formats through the default FORMAT
    // locale, which substitutes non-ASCII digits and a non-'0' pad character under some locales — see
    // KeyEvent.keyCodeFor for the same hazard on `toLowerCase`. Output must not depend on the machine it runs on.
    if hours > 0 then String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)

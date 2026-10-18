package io.worxbend.tui.widgets

/** Caller-owned Markdown that is still arriving — an LLM's reply, a log being narrated — rendered stably while it
  * grows.
  *
  * [[append]] chunks as they come; [[markdown]] is the [[Markdown]] widget to draw, built over
  * `MarkdownSanitizer.sanitize(text)` so a half-written `**bold`, link or code fence renders as it will once complete
  * rather than flashing literal markers. Once the source is done, [[finish]] stops sanitizing, and the frame is then
  * exactly `Markdown(text)`'s. Appending to a finished stream reopens it.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame. This is a plain mutable object, invisible
  * to the reactive layer: a background chunk appended straight into it stays off screen until something unrelated
  * happens to repaint. Hop to the render thread (`RenderThread.capture()` before going async) and pair the append with
  * a `Signal` write or `TuiApp.requestRedraw()`.
  */
final class MarkdownStream(initial: String = ""):
  private val source            = StringBuilder(initial)
  private var finished: Boolean = false
  private var changes: Long     = 0L
  private var built             = Option.empty[(Long, MarkdownTheme, MarkdownOptions, Markdown)]

  /** Follow-the-tail bookkeeping for a scroll view drawing this stream: whether the view was showing the last row when
    * it last drew. Owned by whichever element pins the view, render-thread-only like the rest of this class.
    */
  private[tui] var followingTail: Boolean = true

  /** Adds `chunk` to the end of the document and reopens a finished stream. */
  def append(chunk: String): Unit =
    if chunk.nonEmpty || finished then
      source ++= chunk
      finished = false
      changes += 1

  /** The raw text received so far, unsanitized. */
  def text: String = source.result()

  /** Marks the document complete: the sanitizer stops, so the last line renders exactly as written. */
  def finish(): Unit =
    if !finished then
      finished = true
      changes += 1

  def isFinished: Boolean = finished

  /** Empties the document for the next reply. */
  def clear(): Unit =
    source.clear()
    finished = false
    followingTail = true
    changes += 1

  /** Advances on every append, finish and clear — cheap change detection for a caller deciding whether to redraw. */
  def revision: Long = changes

  /** The widget for the text so far, reused until the stream, the theme or the options change. */
  def markdown(theme: MarkdownTheme = MarkdownTheme(), options: MarkdownOptions = MarkdownOptions()): Markdown =
    built match
      case Some((revision, builtTheme, builtOptions, widget))
          if revision == changes && builtTheme == theme && builtOptions == options =>
        widget
      case _ =>
        val shown  = if finished then text else MarkdownSanitizer.sanitize(text)
        val widget = Markdown(shown, theme, options)
        built = Some((changes, theme, options, widget))
        widget

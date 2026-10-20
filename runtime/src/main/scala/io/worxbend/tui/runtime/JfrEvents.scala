package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event}

import jdk.jfr.{Category, Description, Label, Name, StackTrace}

/** JDK Flight Recorder events for the loop's four moments worth profiling: a frame drawn, an event routed, focus
  * changed, async work admitted or rejected. They pair with [[FrameTiming]]: timing is a callback the app installs,
  * these are free until a recording asks for them.
  *
  * {{{
  * java -XX:StartFlightRecording=filename=run.jfr,settings=profile -jar app.jar
  * jfr print --categories glyphora run.jfr
  * }}}
  *
  * Every event is a plain `jdk.jfr.Event` subclass with primitive and string fields and is committed through its own
  * instance — no reflection in this code, so a native image needs no reflect configuration for it. When no recording
  * enables an event, `isEnabled` is false and the emit sites below skip all field work, including the dirty-cell count.
  *
  * '''Threads.''' `FrameDrawn` and `EventRouted` are committed on the render thread, `FocusChanged` on the render
  * thread of the DSL host whose focus moved, and the two async events on the submitting thread. JFR events are
  * thread-safe to commit; nothing here is shared.
  */
object JfrEvents:

  final val FrameDrawnName    = "io.worxbend.tui.FrameDrawn"
  final val EventRoutedName   = "io.worxbend.tui.EventRouted"
  final val FocusChangedName  = "io.worxbend.tui.FocusChanged"
  final val AsyncAdmittedName = "io.worxbend.tui.AsyncAdmitted"
  final val AsyncRejectedName = "io.worxbend.tui.AsyncRejected"

  /** The constructor-free name of an [[Event]] case, as `EventRouted.kind` records it. */
  def kindOf(event: Event): String =
    event match
      case Event.Key(_)                => "Key"
      case Event.Mouse(_)              => "Mouse"
      case Event.Resize(_)             => "Resize"
      case Event.Tick                  => "Tick"
      case Event.Paste(_)              => "Paste"
      case Event.KeyRelease(_)         => "KeyRelease"
      case Event.FocusGained           => "FocusGained"
      case Event.FocusLost             => "FocusLost"
      case Event.ColorSchemeChanged(_) => "ColorSchemeChanged"
      case Event.Interrupt             => "Interrupt"
      case Event.EndOfInput            => "EndOfInput"

  /** Commits a `FocusChanged` when a recording enabled it. Indices are `-1` when nothing was focused; keys are empty
    * for an unkeyed element.
    */
  def focusChanged(fromIndex: Int, fromKey: Option[String], toIndex: Int, toKey: Option[String]): Unit =
    val event = FocusChangedEvent()
    if event.isEnabled then
      event.fromIndex = fromIndex
      event.fromKey = fromKey.getOrElse("")
      event.toIndex = toIndex
      event.toKey = toKey.getOrElse("")
      event.commit()

  private[runtime] def admitted(queue: String, outstanding: Int): Unit =
    val event = AsyncAdmittedEvent()
    if event.isEnabled then
      event.queue = queue
      event.outstanding = outstanding
      event.commit()

  private[runtime] def rejected(queue: String, capacity: Int): Unit =
    val event = AsyncRejectedEvent()
    if event.isEnabled then
      event.queue = queue
      event.capacity = capacity
      event.commit()

  /** Times one dispatch as an `EventRouted`: begun before `dispatch`, committed with whether it owed a repaint. */
  private[runtime] def routed[A](event: Event)(dispatch: => A)(repaints: A => Boolean): A =
    val record = EventRoutedEvent()
    if !record.isEnabled then dispatch
    else
      record.begin()
      val outcome = dispatch
      record.end()
      record.kind = kindOf(event)
      record.repaint = repaints(outcome)
      record.commit()
      outcome

  /** The cells of `next` that differ from `previous`, or from a blank screen of the same area when there is no previous
    * frame of that size — the first frame, or the first after a resize, which the backend paints over a cleared screen.
    * Only computed while a recording has `FrameDrawn` enabled.
    */
  private[runtime] def dirtyCells(previous: Option[Buffer], next: Buffer): Int =
    previous.filter(_.area == next.area).getOrElse(Buffer(next.area)).diff(next).size

/** One composed and flushed frame. Its duration spans composition and the backend draw — the two phases [[FrameTiming]]
  * reports as `compositionNanos` and `drawNanos`.
  */
@Name(JfrEvents.FrameDrawnName)
@Label("Frame Drawn")
@Category(Array("glyphora"))
@Description("A frame composed and flushed by a glyphora runner")
@StackTrace(false)
final private[runtime] class FrameDrawnEvent extends jdk.jfr.Event:
  @Label("Frame")
  var frame: Long = 0L

  @Label("Dirty cells")
  @Description("Cells that differ from the previous flushed frame")
  var dirtyCells: Int = 0

/** One event handed to the app's dispatcher; its duration is the dispatch itself. */
@Name(JfrEvents.EventRoutedName)
@Label("Event Routed")
@Category(Array("glyphora"))
@StackTrace(false)
final private[runtime] class EventRoutedEvent extends jdk.jfr.Event:
  @Label("Kind")
  var kind: String = ""

  @Label("Repaint")
  @Description("Whether the dispatch owed a frame")
  var repaint: Boolean = false

/** Focus moved between two frames of a DSL host. */
@Name(JfrEvents.FocusChangedName)
@Label("Focus Changed")
@Category(Array("glyphora"))
@StackTrace(false)
final private[runtime] class FocusChangedEvent extends jdk.jfr.Event:
  @Label("From index")
  var fromIndex: Int = -1

  @Label("From key")
  var fromKey: String = ""

  @Label("To index")
  var toIndex: Int = -1

  @Label("To key")
  var toKey: String = ""

/** A callback slot or worker submission accepted by `Async`/`TaskScope` admission. */
@Name(JfrEvents.AsyncAdmittedName)
@Label("Async Admitted")
@Category(Array("glyphora"))
@StackTrace(false)
final private[runtime] class AsyncAdmittedEvent extends jdk.jfr.Event:
  @Label("Queue")
  @Description("worker for the background pool, owner for a render loop's callback slots")
  var queue: String = ""

  @Label("Outstanding")
  var outstanding: Int = 0

/** A submission refused at capacity — the moment `RejectedExecutionException` is thrown to the submitter. */
@Name(JfrEvents.AsyncRejectedName)
@Label("Async Rejected")
@Category(Array("glyphora"))
@StackTrace(true)
final private[runtime] class AsyncRejectedEvent extends jdk.jfr.Event:
  @Label("Queue")
  var queue: String = ""

  @Label("Capacity")
  var capacity: Int = 0

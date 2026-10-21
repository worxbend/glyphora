package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Event, Position, Size, Widget}

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.concurrent.duration.Duration

/** An in-memory [[Backend]] for headless end-to-end testing: renders into a retained snapshot instead of a TTY and
  * reads synthetic events posted by the test driver.
  *
  * Thread contract: the runner calls `readEvent`/`draw` on the render thread while a test thread posts events and
  * inspects `lastDrawn` — hence the blocking queue and volatile snapshot. [[wake]] interrupts a parked `readEvent`
  * exactly as the JLine backend's does, so a `Pilot` works with any `tickRate`.
  *
  * Every operation succeeds unless a test says otherwise through [[failNext]], which is the seam that makes the
  * `Either[BackendError, A]` half of [[Backend]] reachable from above this module.
  */
final class HeadlessBackend(initialSize: Size) extends Backend:

  import HeadlessBackend.{Op, PollSliceMillis}

  /** Posted events (`Some`) and wake tokens (`None`), in the order they were asked for. A token is how [[wake]] ends a
    * parked read without the app ever seeing an event it did not receive; see [[woken]] for why there is at most one.
    */
  private val events = LinkedBlockingQueue[Option[Event]]()

  /** How many posted events are waiting to be read. Kept apart from `events.size()` so wake tokens never inflate
    * [[pendingEvents]], which `Pilot.waitForIdle` requires to reach zero.
    */
  private val postedCounter = AtomicInteger(0)

  /** Makes "count an idle read" and "post an event" mutually exclusive.
    *
    * An idle read is counted only when nothing is posted, and `Pilot.waitForIdle` trusts a count that rose *after* it
    * posted. As two separate atomics, a reader could see an empty count, lose the CPU, and add its idle read after the
    * test had posted a key and taken its snapshot. That stale read then satisfied the wait as soon as the key was
    * dequeued, before it was handled or drawn: a test asserting on the frame saw the one before the key, but only under
    * load. Under this lock the reader either counts before the post, so the snapshot includes it, or sees the post and
    * does not count.
    */
  private val accounting = Object()

  /** Whether a wake token is already queued and unread. [[wake]] offers a token only on a `false → true` transition and
    * [[readEvent]] clears it when it consumes the token, so a burst of wakes with nobody reading costs one spurious
    * loop iteration rather than one per wake — the same coalescing `EventPump` does for the JLine backend.
    */
  private val woken                                            = AtomicBoolean(false)
  @volatile private var terminalSize                           = initialSize
  // no device to ask, so nothing is known about pixels until a test says otherwise — see `pixelsTo`
  @volatile private var pixelSize: Option[Size]                = None
  @volatile private var lastFrame: Option[Buffer]              = None
  @volatile private var rawMode                                = false
  @volatile private var alternateScreen                        = false
  @volatile private var mouseCapture: Option[MouseCaptureMode] = None
  @volatile private var cursorVisible                          = true
  // blinking is the terminal's own default, so that is where a freshly built backend starts
  @volatile private var cursorBlinking                         = true

  @volatile private var cursorShape: CursorShape       = CursorShape.Default
  // what a test said this terminal supports; nothing, until it says — see `capabilitiesTo`
  @volatile private var reported: TerminalCapabilities = TerminalCapabilities.unknown
  @volatile private var pointerShape: PointerShape     = PointerShape.Default
  @volatile private var taskbarShown: TaskbarState     = TaskbarState.Cleared
  private val notifications                            = scala.collection.mutable.ArrayBuffer.empty[DesktopNotification]
  private val taskbarRequests                          = scala.collection.mutable.ArrayBuffer.empty[TaskbarState]
  @volatile private var lastClipboard: Option[String]  = None
  @volatile private var lastTitle: Option[String]      = None
  @volatile private var caret: Option[Position]        = None
  @volatile private var inlineRows                     = 0
  @volatile private var keptRows: Option[Int]          = None
  private val caretMoveCounter                         = AtomicLong(0)
  private val appendedLineCounter                      = AtomicLong(0)
  private val drawCounter                              = AtomicLong(0)
  private val idleReadCounter                          = AtomicLong(0)
  private val suspendCounter                           = AtomicLong(0)
  private val wakeCounter                              = AtomicLong(0)
  private val fullRedrawCounter                        = AtomicLong(0)
  private val emergencyRestoreCounter                  = AtomicLong(0)
  private val printedLines                             = scala.collection.mutable.ArrayBuffer.empty[String]
  private var printAboveTrips                          = 0 // guarded by printedLines
  private val rawWrites                                = scala.collection.mutable.ArrayBuffer.empty[String]
  private val graphics                                 = GraphicsLayer()
  private val clears                                   = scala.collection.mutable.ArrayBuffer.empty[ClearType]
  private val insertedBlocks                           = scala.collection.mutable.ArrayBuffer.empty[Buffer]
  private val sizeRequests                             = scala.collection.mutable.ArrayBuffer.empty[Size]
  private val scrolls                                  =
    scala.collection.mutable.ArrayBuffer.empty[(RowRange, Int, ScrollDirection)]

  /** The failures armed by [[failNext]], at most one per operation and removed as each is consumed. A concurrent map
    * rather than a guarded `var`, because a test arms a failure from its own thread while the render thread is the one
    * that performs — and so disarms — the operation.
    */
  private val armedFailures = ConcurrentHashMap[Op, BackendError]()

  /** Performs `effect` and reports success, unless [[failNext]] armed `op`: the effect is then skipped entirely and the
    * armed error is returned, disarming it. The one place the injection is consulted, so an operation that forgets to
    * route through here is an operation no test can make fail.
    */
  private def attempt[A](op: Op)(effect: => A): Either[BackendError, A] =
    Option(armedFailures.remove(op)).toLeft(effect)

  def size: Either[BackendError, Size] = attempt(Op.Size)(terminalSize)

  /** What [[capabilitiesTo]] last said, or [[TerminalCapabilities.unknown]] — there is no device here to ask. */
  override def capabilities: TerminalCapabilities = reported

  /** The cell size, plus whatever [[pixelsTo]] was last told. `pixels` is empty until a test sets one, which is what a
    * real terminal that does not answer `CSI 14 t` also reports.
    */
  override def windowSize: Either[BackendError, WindowSize] = Right(WindowSize(terminalSize, pixelSize))

  def draw(buffer: Buffer): Either[BackendError, Unit] =
    attempt(Op.Draw) {
      // the same picture bookkeeping the JLine backend runs, so an app's images can be tested against either answer
      val pictures = graphics.compose(buffer, ImageProtocol.select(reported), repaintAll = false)
      if !pictures.isEmpty then rawWrites.synchronized { val _ = rawWrites += pictures.before + pictures.after }
      pictures.commit()
      lastFrame = Some(buffer.snapshot)
      val _        = drawCounter.incrementAndGet()
    }

  def enableRawMode(): Either[BackendError, Unit] =
    attempt(Op.EnableRawMode) { rawMode = true }

  def disableRawMode(): Either[BackendError, Unit] =
    if !rawMode then Left(BackendError.NotInRawMode)
    else
      rawMode = false
      Right(())

  def enterAlternateScreen(): Either[BackendError, Unit] =
    attempt(Op.EnterAlternateScreen) { alternateScreen = true }

  def leaveAlternateScreen(): Either[BackendError, Unit] =
    alternateScreen = false
    Right(())

  def enableMouseCapture(): Either[BackendError, Unit] = enableMouseCapture(MouseCaptureMode.Buttons)

  override def enableMouseCapture(mode: MouseCaptureMode): Either[BackendError, Unit] =
    mode match
      case MouseCaptureMode.Off => disableMouseCapture()
      case _                    => attempt(Op.EnableMouseCapture) { mouseCapture = Some(mode) }

  def disableMouseCapture(): Either[BackendError, Unit] =
    mouseCapture = None
    Right(())

  def hideCursor(): Either[BackendError, Unit] =
    attempt(Op.HideCursor) { cursorVisible = false }

  def showCursor(): Either[BackendError, Unit] =
    cursorVisible = true
    Right(())

  /** Records the requested blink state. A real terminal may ignore DECSET 12 entirely, and this one deliberately does
    * not model that: the question a test asks is what the app *requested*, since that is the only half the app
    * controls.
    */
  override def setCursorBlink(blinking: Boolean): Either[BackendError, Unit] =
    cursorBlinking = blinking
    Right(())

  /** Records the requested shape instead of emitting DECSCUSR — there is no device here to obey it. A test asserts on
    * [[currentCursorShape]], which is the whole point of this backend.
    */
  override def setCursorShape(shape: CursorShape): Either[BackendError, Unit] =
    cursorShape = shape
    Right(())

  override def reserveInlineRows(rows: Int): Either[BackendError, Unit] =
    attempt(Op.ReserveInlineRows) { inlineRows = math.max(0, rows) }

  override def keepInlineRows(rows: Int): Either[BackendError, Unit] =
    keptRows = Some(math.max(0, rows))
    Right(())

  /** Records the requested pointer shape, unless the capabilities a test installed deny pointer shapes — the same gate
    * the JLine backend applies before writing OSC 22, so a test can see that a denied terminal is never asked.
    */
  override def setPointerShape(shape: PointerShape): Either[BackendError, Unit] =
    if reported.pointerShapes.usable then pointerShape = shape
    Right(())

  /** Records the notification, unless the installed capabilities deny desktop notifications. */
  override def notifyDesktop(title: String, body: String): Either[BackendError, Unit] =
    if reported.desktopNotifications.usable then
      notifications.synchronized { val _ = notifications += DesktopNotification(title, body) }
    Right(())

  /** Records the progress request, unless the installed capabilities deny taskbar progress. */
  override def setTaskbarProgress(progress: Option[Double]): Either[BackendError, Unit] =
    setTaskbarState(TaskbarState.fromProgress(progress))

  /** Records the state request, unless the installed capabilities deny taskbar progress. */
  override def setTaskbarState(state: TaskbarState): Either[BackendError, Unit] =
    if reported.taskbarProgress.usable then
      taskbarRequests.synchronized { val _ = taskbarRequests += state }
      taskbarShown = state
    Right(())

  /** Records where the hardware caret was asked to go, so a test can assert on it exactly as it asserts on a drawn
    * cell. Nothing is validated here: the real terminal clamps an out-of-range position and a test that expects
    * clamping should be asserting against the position the caller computed, not one this class invented.
    */
  override def setCursorPosition(position: Position): Either[BackendError, Unit] =
    caret = Some(position)
    val _ = caretMoveCounter.incrementAndGet()
    Right(())

  def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
    // outside `attempt`: a non-positive timeout is a defect in the caller, not an injected device failure, and it is
    // still a defect when a test has armed this read to fail
    Backend.requirePositiveTimeout(timeout)
    attempt(Op.ReadEvent) {
      // A finite wait is sliced to at most `PollSliceMillis`, so an idle app with a long tick rate still produces the
      // idle reads `Pilot.waitForIdle` waits for. An early `Right(None)` is what `Backend.readEvent` already permits,
      // and the runner's tick schedule is clock-driven, so the extra slices manufacture no ticks. The infinite wait
      // has no slice to fall back on: only a posted event or a wake token ends it.
      val taken: Option[Option[Event]] =
        if timeout.isFinite then Option(events.poll(math.min(timeout.toMillis, PollSliceMillis), TimeUnit.MILLISECONDS))
        else Some(events.take())
      taken match
        case Some(Some(event)) =>
          val _ = postedCounter.decrementAndGet()
          Some(event)
        case Some(None)        =>
          woken.set(false) // the token is consumed, so the next wake may queue another
          countIdleRead()
          None
        case None              =>
          countIdleRead()
          None
    }

  /** Counts a read that produced no event while nothing was posted — a timed-out slice or a wake token that found the
    * queue empty. Counting only timeouts would freeze [[idleReads]] in any app whose wakes arrive faster than a slice
    * (a 60 fps ticker, a repeating `Async`), and every `Pilot.waitForIdle` would run to its deadline.
    */
  private def countIdleRead(): Unit =
    accounting.synchronized {
      if postedCounter.get() == 0 then
        val _ = idleReadCounter.incrementAndGet()
    }

  /** Cuts an in-flight [[readEvent]] short, as `JLine3Backend.wake` interrupts its blocking read, and records the wake
    * so tests can assert that queued render-thread work asked for one.
    *
    * The wake is a token on the event queue — `None`, where a posted event is `Some` — so the parked read returns
    * `Right(None)` at once without the app seeing an event it never received. At most one token is queued at a time
    * (see [[woken]]). Honouring the wake is what lets queued render-thread work, `Pilot.close()` and
    * `Pilot.waitForIdle()` proceed promptly under any `tickRate` instead of waiting out the whole poll.
    */
  override def wake(): Unit =
    val _ = wakeCounter.incrementAndGet()
    if woken.compareAndSet(false, true) then events.put(None)

  /** Counts the request rather than acting on it: this backend keeps whole frames instead of diffing against a
    * baseline, so there is nothing here to invalidate — but a test above `terminal` still needs to see that the app
    * asked.
    */
  override def requestFullRedraw(): Unit =
    val _ = fullRedrawCounter.incrementAndGet()

  /** Records the request and, unlike a real emulator, honours it.
    *
    * A real terminal is free to refuse, so an app must never depend on a resize arriving. This backend grants every
    * request instead, for two reasons: there is no window manager here to model a refusal faithfully, and a test that
    * wants to see the app's reaction to a resize already has [[resizeTo]] to force one. Delegating to `resizeTo` means
    * the app sees a genuine `Event.Resize` through its normal path rather than a size that changed behind its back.
    *
    * [[requestedSizes]] keeps what was asked for, which is the half a test asserting on the *app* wants: it can check
    * the app asked for the right shape without also depending on the grant.
    */
  override def requestSize(size: Size): Either[BackendError, Unit] =
    Backend.requirePositiveSize(size)
    sizeRequests.synchronized { val _ = sizeRequests += size }
    resizeTo(size)
    Right(())

  /** Records the scroll and applies it to the retained frame, so a test sees the rows where the terminal would have put
    * them.
    *
    * The trait's default is a failure — "this backend has no scroll region" — so that a caller knows to repaint the
    * rows itself. This backend answers `Right` instead, because it *can* model the operation exactly: there is no
    * device to refuse, and a test driving an app that scrolls this way needs to see the app's own frames rather than
    * its fallback path. A test that wants the fallback exercised should drive a backend whose default stands.
    */
  override def scrollRegionUp(region: RowRange, lines: Int): Either[BackendError, Unit] =
    recordScroll(region, lines, ScrollDirection.Up)

  override def scrollRegionDown(region: RowRange, lines: Int): Either[BackendError, Unit] =
    recordScroll(region, lines, ScrollDirection.Down)

  private def recordScroll(region: RowRange, lines: Int, direction: ScrollDirection): Either[BackendError, Unit] =
    if lines <= 0 then Right(())
    else
      scrolls.synchronized { val _ = scrolls += ((region, lines, direction)) }
      lastFrame = lastFrame.map(frame => ScrollDirection.shifted(frame, region, lines, direction))
      Right(())

  override def copyToClipboard(text: String): Either[BackendError, Unit] =
    lastClipboard = Some(text)
    Right(())

  override def setTitle(title: String): Either[BackendError, Unit] =
    lastTitle = Some(title)
    Right(())

  override def suspend[A](body: => A): Either[BackendError, A] =
    val _      = suspendCounter.incrementAndGet()
    val wasAlt = alternateScreen
    val wasRaw = rawMode
    alternateScreen = false // observable to `body` so tests can assert the terminal was handed back
    rawMode = false
    try Right(body)
    finally
      alternateScreen = wasAlt
      rawMode = wasRaw

  override def clearRegion(kind: ClearType): Either[BackendError, Unit] =
    clears.synchronized { val _ = clears += kind }
    Right(())

  override def writeRaw(sequence: String): Either[BackendError, Unit] =
    rawWrites.synchronized { val _ = rawWrites += sequence }
    Right(())

  override def printAbove(lines: Seq[String]): Either[BackendError, Unit] =
    printedLines.synchronized {
      printedLines ++= lines
      printAboveTrips += 1
    }
    Right(())

  /** Records the rendered block itself, not only its text.
    *
    * The inherited implementation would render the widget and then flatten it to plain strings for [[printAbove]],
    * which throws away the styling that is the whole reason this method exists — a test could then never tell a bold
    * red `ERROR` prefix from the word "ERROR". The block is kept as a [[Buffer]] for [[insertedAbove]] to hand out, and
    * its text is *also* appended to [[printedAbove]], so a test written against the plain-text view still sees
    * everything the app emitted above the UI, in the order it emitted it.
    */
  override def insertBefore(height: Int, widget: Widget): Either[BackendError, Unit] =
    if height <= 0 then Right(())
    else
      val buffer = Backend.renderBlock(terminalSize.width, height, widget)
      insertedBlocks.synchronized { val _ = insertedBlocks += buffer }
      printedLines.synchronized { printedLines ++= Backend.plainRows(buffer) }
      Right(())

  /** Adds `n` to the running total a test asserts on. There is no simulated scrollback to move rows into, so the count
    * is the whole observable effect — which is exactly the question an inline-viewport test asks: how much room did the
    * app ask the shell for?
    */
  override def appendLines(n: Int): Either[BackendError, Unit] =
    if n <= 0 then Right(())
    else
      val _ = appendedLineCounter.addAndGet(n.toLong)
      Right(())

  /** Releases the simulated terminal, so a test can assert the runner tore everything down on its way out. There is no
    * device to fail, so this succeeds unless [[failNext]] armed it — the one case that models the worst failure this
    * library has, a shell handed back in raw mode on the alternate screen.
    */
  def close(): Either[BackendError, Unit] =
    attempt(Op.Close) {
      mouseCapture = None
      cursorVisible = true
      cursorBlinking = true

      cursorShape = CursorShape.Default
      pointerShape = PointerShape.Default
      taskbarShown = TaskbarState.Cleared
      alternateScreen = false
      rawMode = false
      caret = None
    }

  /** Counts the emergency restore rather than performing one.
    *
    * There is no device here to wrestle back, and every field this class models is already reset by [[close]], which
    * the runner calls first on both of the paths that reach here. What is left to observe is *that it happened* — and
    * that is precisely the half a test above `terminal` needs, since the contract for a failed setup and for the JVM
    * shutdown hook is that this call follows `close` rather than replacing it. See [[emergencyRestoreCount]].
    */
  override def emergencyRestore(): Unit =
    val _ = emergencyRestoreCounter.incrementAndGet()

  // ---- test-driver surface ----

  /** Arms `op` to fail once with `error`, instead of doing what it normally does.
    *
    * The seam that makes [[Backend]]'s central claim testable from above this module: failures are values because
    * callers can meaningfully degrade, and until something can fail there is nothing to degrade from. The failure paths
    * worth reaching this way are the ones with no other route to them — a `setup()` that dies half-way and hands the
    * shell back in raw mode on the alternate screen, a `draw` that cannot reach the terminal, an input stream that
    * closes under the event loop.
    *
    * One-shot: the next call to `op` consumes the arming, and every call after it behaves exactly as it always did. A
    * backend nothing has armed is byte-for-byte the backend it always was, so this costs an existing test nothing.
    *
    * The armed operation does not happen at all — a failed `draw` retains no frame, a failed `enableRawMode` leaves
    * [[isRawMode]] `false`. That is stricter than a real terminal, whose half-finished write may well have taken
    * effect, and the strictness is the point: "the caller was told it failed" and "nothing changed" become one
    * assertion each rather than one judgement call.
    *
    * Arming an op that is already armed replaces the pending error rather than queueing a second one. Safe from any
    * thread, like [[postEvent]].
    */
  def failNext(op: Op, error: BackendError): Unit =
    val _ = armedFailures.put(op, error)

  /** Queues a synthetic event for the runner to read. Safe from any thread. */
  def postEvent(event: Event): Unit =
    accounting.synchronized {
      val _ = postedCounter.incrementAndGet()
      events.put(Some(event))
    }

  /** Feeds raw terminal input through the *production* [[InputDecoder]] and queues whatever it decodes.
    *
    * [[postEvent]] hands the runner an [[Event]] a test built by hand, which skips the decoder entirely. That is the
    * right thing for most tests and the wrong thing for one: an application binding spelled `ctrl+s` and a decoder that
    * turns byte `0x13` into some other key would both look correct on their own, and the app would still be dead in a
    * real terminal. Posting the bytes joins the two halves — the key spec the app is written with, and the sequence a
    * terminal actually sends.
    *
    * `codeUnits` are UTF-16 code units as a terminal reader hands them back (that is what `JLine3Backend` reads), not
    * UTF-8 bytes. A sequence the decoder deliberately drops — a device-attributes reply, say — queues nothing at all,
    * which is exactly what a test about such a reply wants to assert.
    *
    * Decoding happens on the calling thread and is finished before this returns, so the decoder's one-reader rule
    * holds: the app thread only ever takes completed events off the queue. A trailing lone `ESC` costs the decoder's
    * escape timeout (50 ms) before it reports, the same as it would at a real terminal.
    */
  def postInput(codeUnits: Seq[Int]): Unit =
    val remaining = codeUnits.iterator
    // The read function hands over the next code unit, then reports that nothing arrived. It must answer
    // `ReadExpired`, not `EndOfStream`: end of file is a permanent condition the decoder reports as
    // `Event.EndOfInput` on every later call, so a script that ended with it would decode into an unbounded run of
    // those events rather than stopping — the loop below would never see the `None` that ends it.
    val decoder   = InputDecoder(_ => if remaining.hasNext then remaining.next() else InputDecoder.ReadExpired)
    var draining  = codeUnits.nonEmpty
    while draining do
      decoder.decode(0L) match
        case Some(event) => postEvent(event)
        case None        => ()
      // Re-checked after every decode, event or not. A decoded event does not end the drain — the decoder may still
      // hold a pending character — and an undecodable sequence yields no event but may have consumed only part of
      // the input. `ESC [ ESC` ends that way: the torn CSI hands the trailing `ESC` back, and only the next decode
      // turns it into the Escape keypress a real terminal would have delivered. The test has to run after a decoded
      // event too, or a decoder that keeps answering once the script has run dry would spin forever. The read function
      // above never throws `InterruptedIOException`, so none of the decoder's wake-resumable states is reachable here.
      draining = remaining.hasNext || decoder.hasPending

  /** Installs what this terminal claims to support, which [[capabilities]] then reports.
    *
    * The seam for everything that depends on a probe: a test states that the terminal reported a light background, or
    * denied pointer shapes, and the layers above react exactly as they would to a real terminal that said so. Posts no
    * event — a real probe runs inside `enableRawMode`, before the app's first frame, so install it before the app
    * starts. Safe from any thread; the value is read on the render thread through a volatile field.
    */
  def capabilitiesTo(capabilities: TerminalCapabilities): Unit = reported = capabilities

  /** The pointer shape last asked for through [[setPointerShape]] and not refused by the installed capabilities;
    * [[PointerShape.Default]] until something asks, and again after [[close]].
    */
  def currentPointerShape: PointerShape = pointerShape

  /** Every desktop notification raised through [[notifyDesktop]] and not refused by the installed capabilities, in
    * order.
    */
  def desktopNotifications: Seq[DesktopNotification] = notifications.synchronized(notifications.toSeq)

  /** Every progress value handed to [[setTaskbarProgress]] or [[setTaskbarState]] and not refused, in order, by its
    * fraction — `None` entries are clears (and indeterminate bars; [[taskbarStateRequests]] tells them apart).
    */
  def taskbarProgressRequests: Seq[Option[Double]] = taskbarStateRequests.map(_.progress)

  /** The progress currently shown on the simulated taskbar, by its fraction: `None` once cleared or closed. */
  def currentTaskbarProgress: Option[Double] = taskbarShown.progress

  /** Every taskbar state requested and not refused, in order — a fraction-only request recorded as its state. */
  def taskbarStateRequests: Seq[TaskbarState] = taskbarRequests.synchronized(taskbarRequests.toSeq)

  /** The state currently shown on the simulated taskbar: the last request, or `Cleared` once cleared or closed. */
  def currentTaskbarState: TaskbarState = taskbarShown

  /** Sets the pixel size [[windowSize]] reports, or clears it with `None`.
    *
    * The seam that makes the pixel half of [[WindowSize]] testable at all: no headless backend has a device to ask, so
    * a test states the answer a terminal would have given. Posts no event — pixel geometry is not a resize, and a test
    * that wants both calls [[resizeTo]] as well.
    */
  def pixelsTo(pixels: Option[Size]): Unit = pixelSize = pixels

  /** Changes the reported terminal size and posts the matching resize event. */
  def resizeTo(size: Size): Unit =
    terminalSize = size
    postEvent(Event.Resize(size))

  /** Snapshot of the most recently drawn frame. */
  def lastDrawn: Option[Buffer] = lastFrame

  /** How many frames have been flushed. */
  def drawCount: Long = drawCounter.get()

  /** How many reads produced no event while nothing was posted — a timed-out poll slice, or a wake that found the queue
    * empty. Each one means the runner looked at an empty queue, which is what `Pilot.waitForIdle` waits for.
    */
  def idleReads: Long = idleReadCounter.get()

  /** How many posted events the runner has not read yet. Wake tokens are not events and are never counted here. */
  def pendingEvents: Int = postedCounter.get()

  /** How many times the app suspended the terminal (see [[suspend]]). */
  def suspendCount: Long = suspendCounter.get()

  /** How many times [[wake]] was called — i.e. how often background work asked the render thread to look again. */
  def wakeCount: Long = wakeCounter.get()

  /** How many times a full repaint was requested via [[requestFullRedraw]]. */
  def fullRedrawCount: Long = fullRedrawCounter.get()

  /** How many times [[emergencyRestore]] was called — the last-resort teardown a runner owes after a failed setup and
    * from its JVM shutdown hook. `0` for a run that ended cleanly, because the tidy [[close]] path is then the whole
    * story.
    */
  def emergencyRestoreCount: Long = emergencyRestoreCounter.get()

  /** Every region scroll asked for via [[scrollRegionUp]] or [[scrollRegionDown]], in order, as
    * `(rows, lines, direction)`.
    *
    * The interesting assertion is usually the *count*: an app that scrolls a list one row and repaints the whole list
    * anyway is a correct app doing the expensive thing, and this is what tells the two apart.
    */
  def regionScrolls: Seq[(RowRange, Int, ScrollDirection)] = scrolls.synchronized(scrolls.toSeq)

  /** The sizes asked for via [[requestSize]], in order — what the app wanted, as opposed to what it got. */
  def requestedSizes: Seq[Size] = sizeRequests.synchronized(sizeRequests.toSeq)

  /** The erases requested via [[clearRegion]], in order. */
  def clearedRegions: Seq[ClearType] = clears.synchronized(clears.toSeq)

  /** The styled blocks emitted above the app via [[insertBefore]], in order, each as the [[Buffer]] the widget was
    * rendered into. Their text also appears in [[printedAbove]]; this is the view that still carries the styling.
    */
  def insertedAbove: Seq[Buffer] = insertedBlocks.synchronized(insertedBlocks.toSeq)

  /** The lines emitted above the app via [[printAbove]] or [[insertBefore]], in order. */
  def printedAbove: Seq[String] = printedLines.synchronized(printedLines.toSeq)

  /** How many times [[printAbove]] was called — the scrollback trips a real terminal would have made. */
  def printAboveCalls: Int = printedLines.synchronized(printAboveTrips)

  /** Every sequence handed to [[writeRaw]], in order — the out-of-band payloads (an image protocol's escape sequence,
    * say) an application sent past the frame diff.
    *
    * A [[draw]] whose frame places pictures ([[io.worxbend.tui.core.Buffer.placeGraphic]]) appends one entry too, when
    * the installed capabilities select an [[ImageProtocol]] and the pictures on screen had to change: the deletes and
    * draws the JLine backend would have put around that frame's cells, concatenated. An unchanged frame appends
    * nothing.
    */
  def rawSequences: Seq[String] = rawWrites.synchronized(rawWrites.toSeq)

  /** Total rows scrolled off the top via [[appendLines]] — how much room an inline viewport asked the shell for. */
  def appendedLineCount: Long = appendedLineCounter.get()

  /** Where the hardware caret was last parked by [[setCursorPosition]], or `None` if it never was (or the backend has
    * been closed).
    */
  def cursorPosition: Option[Position] = caret

  /** How many times the caret was moved. A frame that repaints nothing should move it at most once, so this is what
    * catches a render loop emitting a needless cursor move on every tick.
    */
  def cursorMoveCount: Long = caretMoveCounter.get()

  def isRawMode: Boolean         = rawMode
  def isAlternateScreen: Boolean = alternateScreen
  def isMouseCaptured: Boolean   = mouseCapture.isDefined

  /** Which capture mode was last requested, or `None` while capture is off — so a test can assert that an app asking
    * for hover really did ask for all-motion tracking, not merely for "the mouse".
    */
  def mouseCaptureMode: Option[MouseCaptureMode] = mouseCapture
  def isCursorVisible: Boolean                   = cursorVisible

  /** Whether the app last asked for a blinking caret (see [[setCursorBlink]]). `true` for a backend nothing has asked,
    * because blinking is the terminal's own default — so a test asserting `false` is asserting the app really did ask.
    */
  def isCursorBlinking: Boolean = cursorBlinking

  /** The shape last asked for through [[setCursorShape]]; [[CursorShape.Default]] until something asks, and again after
    * [[close]] — so a test can assert an app handed the user's own cursor shape back on its way out.
    */
  def currentCursorShape: CursorShape = cursorShape

  /** How many rows an inline run reserved on the primary screen; `0` for a full-screen run. */
  def reservedInlineRows: Int = inlineRows

  /** How many rows of its inline strip the run asked a close to keep ([[keepInlineRows]]), or `None` if it never asked.
    */
  def keptInlineRows: Option[Int] = keptRows

  /** The text most recently sent to the clipboard via [[copyToClipboard]], if any. */
  def clipboardContents: Option[String] = lastClipboard

  /** The window title most recently requested via [[setTitle]], if any. */
  def titleContents: Option[String] = lastTitle

object HeadlessBackend:

  /** The longest one [[HeadlessBackend.readEvent]] blocks before answering `Right(None)` on an empty queue, however
    * long the caller's timeout. Short enough that `Pilot.waitForIdle` sees an idle read well inside its deadline on an
    * app with a multi-second tick rate; long enough that an idle runner is not spinning.
    */
  private val PollSliceMillis: Long = 25L

  /** Which [[HeadlessBackend]] operation [[HeadlessBackend.failNext]] arms to fail.
    *
    * Deliberately not one case per [[Backend]] method. These are the operations whose failure a caller above `terminal`
    * has to *do* something about — dress the terminal, read input, flush a frame, hand the terminal back — and listing
    * the rest would fill the enum with cases no test has a reason to reach for while suggesting the coverage is
    * complete. Both `enableMouseCapture` overloads share [[EnableMouseCapture]], because the no-argument one delegates
    * to the other.
    */
  enum Op:

    /** `size`, which a frame asks for before it composes: fails the frame before the app's render function runs. */
    case Size

    /** `draw`: the frame was composed and could not be flushed. */
    case Draw

    /** `readEvent`: the input stream broke underneath the event loop. */
    case ReadEvent

    /** `enableRawMode`, the first step of a runner's setup — so nothing after it is even attempted. */
    case EnableRawMode

    /** `enterAlternateScreen`: a full-screen setup dies half-way, with raw mode already on. */
    case EnterAlternateScreen

    /** `reserveInlineRows`: the inline viewport's counterpart to [[EnterAlternateScreen]]. */
    case ReserveInlineRows

    /** `hideCursor`, the last step of a setup every run performs. */
    case HideCursor

    /** `enableMouseCapture`, either overload — the first setup step a run performs only when configured to. */
    case EnableMouseCapture

    /** `close`: the terminal could not be handed back, which is the failure the user notices most. */
    case Close

/** One desktop notification a [[HeadlessBackend]] recorded — see [[HeadlessBackend.desktopNotifications]]. */
final case class DesktopNotification(title: String, body: String)

package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect
import io.worxbend.tui.runtime.{Async, Cancelable, TaskScope}

/** The value of a [[useTask]]: still running, finished with a value, or failed.
  *
  * `Loading` is also the answer on a host with no render loop to deliver a result to — a `Console.print` or a
  * `Snapshot.render` — because there the task is never started.
  */
enum Loadable[+A]:
  case Loading
  case Ready(value: A)
  case Failed(error: Throwable)

  /** The value when ready, `None` while loading or after a failure. */
  def toOption: Option[A] =
    this match
      case Ready(value) => Some(value)
      case _            => None

  def isLoading: Boolean =
    this match
      case Loading => true
      case _       => false

/** Runs `start` after the frame that reached this call has been painted, and again — cancelling the previous run first
  * — whenever `deps` differ (`==`, element by element) from the ones it last started with. The [[Cancelable]] it
  * returns is cancelled when the call stops being reached, when the host is reset, and when the app stops.
  *
  * {{{
  * def clock(using ReactiveScope, Theme): Element =
  *   val now = useSignal(LocalTime.now())
  *   useEffect()(Async.every(1.second)(now.set(LocalTime.now())))
  *   text(now.get.toString)
  * }}}
  *
  * A panel that polls while it is on screen stops polling the frame it disappears, including when a full-screen page
  * covers it. Uncovering starts it again; ordinary eager `useSignal`/`useState` values in covered entries survive. No
  * deps means "start once". `start` runs on the render thread, after the paint, so it may write signals (the write
  * schedules a frame as usual) and read where things landed. A start that has nothing to release returns `() => ()`.
  *
  * Same ordering rule as every hook — see [[ViewState]] — and, like them, only callable while a view is evaluated: an
  * event handler that wants a timer starts it itself and owns its `Cancelable`.
  */
def useEffect(deps: Any*)(start: => Cancelable): Unit =
  val store   = ViewState.current
  var created = false
  val cell    = store.slot { () =>
    created = true
    EffectCell(deps)
  }
  if created || cell.deps != deps then
    cell.deps = deps
    store.onAfterPaint(() => cell.restart(() => start))

/** `compute`, evaluated on the first frame that reaches this call and again only when `deps` change — the value-level
  * counterpart of `memo`, for a derived value (a sorted copy, a parsed document) that is expensive to rebuild every
  * frame. Unlike `memo` it is a hook: it insists on a view being evaluated, and follows the ordering rule on
  * [[ViewState]].
  */
def useMemo[A](deps: Any*)(compute: => A): A =
  val cell = ViewState.current.slot(() => ViewState.MemoCell(deps, compute))
  if cell.deps != deps then
    cell.deps = deps
    cell.value = compute
  cell.value

/** Runs `work` on a background worker and answers its progress: [[Loadable.Loading]] until the result arrives on the
  * render thread, then [[Loadable.Ready]] or [[Loadable.Failed]]; the arrival schedules the frame that shows it.
  *
  * {{{
  * def details(id: Long)(using ReactiveScope, Theme): Element =
  *   useTask(id)(api.fetch(id)) match
  *     case Loadable.Loading       => spinner()
  *     case Loadable.Ready(record) => text(record.title)
  *     case Loadable.Failed(error) => text(error.getMessage)
  * }}}
  *
  * The work starts on the first frame that reaches the call and again when `deps` change; a change cancels the run in
  * flight, so only the newest start is ever delivered. The task belongs to a [[TaskScope]] the call site owns, closed
  * when the call stops being reached — a result that arrives after its node has gone is never delivered. Cancellation
  * follows `TaskScope`'s contract: best effort, the work itself is not interrupted.
  *
  * On a host without a render loop (print mode, `Snapshot.render`) nothing is started and the answer stays `Loading`.
  */
def useTask[A](deps: Any*)(work: => A): Loadable[A] =
  val store   = ViewState.current
  var created = false
  val cell    = store.slot { () =>
    created = true
    TaskCell[A](deps)
  }
  if store.startResources && (created || cell.deps != deps) then
    cell.deps = deps
    cell.launch(() => work, store.requestFrame)
  cell.value

/** Where the node this call belongs to was painted on the last frame — `None` on the first frame, and whenever the node
  * was not on screen.
  *
  * "This node" is the one carrying the key of the innermost `each` row or `keyed` scope around the call: `each` gives
  * the row's element that key by itself; inside `keyed(k)`, give the element `.key(k)`. Outside any keyed scope there
  * is no node to answer for, and the answer is `None`. The rect is the one `boundsOf(key)` reports — translated out of
  * scroll views, not clipped.
  *
  * Asking switches the host's bounds recording on, and a frame whose node landed somewhere other than what the call
  * answered asks for one more frame, so a view that sizes its content by its own area settles one frame after a move.
  */
def useArea(): Option[Rect] =
  val store = ViewState.current
  val cell  = store.slot(() => ViewState.AreaCell(store.innermostKey))
  cell.seen = cell.key.flatMap(store.areaOf)
  store.reachedArea(cell)
  cell.seen

/** The slot behind [[useEffect]]: the deps it last started with and the run in flight. Render-thread-only. */
private[dsl] final class EffectCell(var deps: Seq[Any]) extends ViewState.HookResource:
  private var running: Option[Cancelable] = None
  private var alive: Boolean              = true

  def restart(start: () => Cancelable): Unit =
    if alive then
      stop()
      running = Some(start())

  def dispose(): Unit =
    alive = false
    stop()

  private def stop(): Unit =
    val previous = running
    running = None
    previous.foreach(_.cancel())

/** The slot behind [[useTask]]: the current answer, the scope its runs belong to, and a stamp so only the newest launch
  * is delivered. Render-thread-only; results come back through the scope's owner queue, on the same thread.
  */
private[dsl] final class TaskCell[A](var deps: Seq[Any]) extends ViewState.HookResource:
  var value: Loadable[A]                  = Loadable.Loading
  private var scope: Option[TaskScope]    = None
  private var running: Option[Cancelable] = None
  private var launches: Long              = 0L
  private var alive: Boolean              = true

  def launch(work: () => A, requestFrame: () => Unit): Unit =
    running.foreach(_.cancel())
    value = Loadable.Loading
    launches += 1
    val stamp = launches
    if scope.isEmpty then scope = Async.scopeIfRunning()
    running = scope.map(_.runCatching(work()) { result =>
      if alive && stamp == launches then
        value = result.fold(Loadable.Failed(_), Loadable.Ready(_))
        requestFrame()
    })

  def dispose(): Unit =
    alive = false
    running = None
    scope.foreach(_.close())
    scope = None

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Position, Rect, Size, Versioned}
import io.worxbend.tui.runtime.{ReactiveScope, Signal}

/** One host's component state and frame composition. The caller owns scheduling and reactive subscriptions.
  *
  * The session also owns the one signal every resolved view reads, `changes`: hooks bump it when something they own
  * moved between frames (a task's result, a node's area), and [[pollStates]] bumps it when a [[Versioned]] widget state
  * painted on the last frame has been mutated since. Because the view read it through the caller's `ReactiveScope`, the
  * bump invalidates that view the way any signal write does — so a background write into a `TextInputState` schedules
  * its frame through the same path a `Signal` write would.
  *
  * Render-thread-only, like the host that owns it.
  */
private[dsl] final class ViewSession(areaOf: String => Option[Rect] = _ => None):
  private val state   = ViewState()
  private val changes = Signal(0L)

  /** Every versioned state the last painted tree held, with the version it had once the paint finished. */
  private var painted: Vector[(Versioned, Long)] = Vector.empty

  state.requestFrame = () => changes.update(_ + 1)
  state.areaOf = areaOf

  /** Evaluates `view` and runs the frame's resolution pass over it, which also collects the frame's analysis. */
  def resolve(view: View, size: Size, theme: Theme)(using scope: ReactiveScope): ResolvedFrame =
    state.beginGeneration()
    val frame = ViewState.during(state)(ResponsivePass.resolveFrame(view(using scope, theme), size, dryPaint(size)))
    state.sweep()
    val _     = changes.get
    frame

  /** After the paint of `frame` has been published: remember the versions of the states it painted — after the paint,
    * because a widget's render may itself adjust its state's scroll offset — then run the hooks' after-paint work.
    */
  def afterPaint(frame: ResolvedFrame): Unit =
    painted = StateWatch.versions(frame.analysis.states)
    state.afterPaint()

  /** Whether a versioned state painted on the last frame has been mutated since; when one has, the view is invalidated
    * through `changes` so the frame that shows the mutation is scheduled.
    */
  def pollStates(): Boolean =
    val moved = painted.exists((subject, seen) => subject.version != seen)
    if moved then
      painted = Vector.empty
      changes.update(_ + 1)
    moved

  /** Disposes every hook this session holds — effects cancelled, tasks' scopes closed. */
  def dispose(): Unit = state.disposeAll()

  /** The container-query pre-pass's paint: the whole tree into a scratch buffer of the frame's size, thrown away. */
  private def dryPaint(size: Size)(tree: Element): Unit =
    val area = Rect(0, 0, size.width, size.height)
    paint(tree, area, Buffer(area))

  /** A host boundary clips both paint and input, including portals, without changing layout origins. */
  def paint(tree: Element, area: Rect, buffer: Buffer): Unit =
    val boundary = area.intersection(buffer.area)
    val local    = if boundary == buffer.area then buffer else Buffer(boundary)
    if local ne buffer then local.blit(buffer, Position(boundary.x, boundary.y), boundary)
    FrameCoordinates.root(boundary) {
      PortalQueue.during {
        tree.widget.render(area, local)
        var round  = 0
        var queued = PortalQueue.drain()
        while queued.nonEmpty && round < 8 do
          queued.foreach { (target, content) =>
            if !target.intersection(boundary).isEmpty then content.widget.render(target, local)
          }
          queued = PortalQueue.drain()
          round += 1
      }
    }
    if local ne buffer then buffer.blit(local, Position(boundary.x, boundary.y), boundary)

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size}
import io.worxbend.tui.runtime.ReactiveScope

/** Composes one frame of a [[View]] into a [[Buffer]], with no runner, no terminal and no event loop.
  *
  * A live frame is composed by `TuiApp`, which needs a `Runner` and a `Backend` underneath it. That is the right shape
  * for an application and the wrong shape for the two jobs this exists for: rendering a view to text from a plain unit
  * test, and letting an application export what a view *would* look like at a given size — a screen dump for a bug
  * report, a rendered README, a golden-frame fixture.
  *
  * It runs the same two passes a live frame does, in the same order — the responsive resolve, which lets a `responsive`
  * node pick its branch for the size, then the focus decoration — so an exported screen matches what an app would paint
  * at that size rather than approximating it. `SnapshotSpec` asserts that against a live `Pilot` run, which is what
  * keeps the two from drifting.
  *
  * What it deliberately is not: interactive or animated. There is no clock, so an animated element renders at whatever
  * position it computes for the elapsed time it is handed; reactive reads subscribe nothing, so nothing here will ever
  * ask for a redraw; and there is no focus *tracking*, only the one element named by `focusedKey` drawn as focused.
  *
  * Callable from any thread: it registers no render loop and writes no signal.
  */
object Snapshot:

  /** Renders `view` into a fresh `size`-shaped buffer and hands the buffer back.
    *
    * `focusedKey` names the element drawn as focused, matching the `key` given to a focusable node. `None` focuses the
    * first focusable element in the tree, which is where a freshly started app puts focus; a key that matches nothing
    * falls back to the same place, rather than failing, because a snapshot's job is to render.
    */
  def render(size: Size, theme: Theme = Theme.Dark, focusedKey: Option[String] = None)(view: View): Buffer =
    val buffer = Buffer(Rect(0, 0, size.width, size.height))
    renderInto(buffer, buffer.area, theme, focusedKey)(view)
    buffer

  /** Renders `view` into `area` of a buffer the caller already owns — for composing a snapshot into part of a larger
    * frame. `area` is where the view is drawn *and* the size it branches on, so a responsive view sees the room it was
    * actually given.
    */
  def renderInto(buffer: Buffer, area: Rect, theme: Theme, focusedKey: Option[String])(view: View): Unit =
    // Untracked: a snapshot is evaluated once and thrown away, so a subscription taken here would outlive everything
    // that could ever act on it.
    given scope: ReactiveScope = ReactiveScope.untracked
    val resolved               = ResponsivePass.resolve(view(using scope, theme), Size(area.width, area.height))
    val tracker                = FocusTracker()
    tracker.focusedKey = focusedKey
    tracker.reconcile(FocusPass.focusKeys(resolved), FocusPass.autofocusRequest(resolved))
    // no bounds recorder: a snapshot is a picture, and nothing is left behind to ask where a node landed in it
    FocusPass.decorate(resolved, tracker, theme.focus, bounds = None).widget.render(area, buffer)

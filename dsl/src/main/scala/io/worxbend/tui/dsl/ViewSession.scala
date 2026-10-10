package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Position, Rect, Size}
import io.worxbend.tui.runtime.ReactiveScope

/** One host's component state and frame composition. The caller owns scheduling and reactive subscriptions. */
private[dsl] final class ViewSession:
  private val state = ViewState()

  def resolve(view: View, size: Size, theme: Theme)(using scope: ReactiveScope): Element =
    state.beginGeneration()
    val tree = ViewState.during(state)(ResponsivePass.resolve(view(using scope, theme), size))
    state.sweep()
    tree

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

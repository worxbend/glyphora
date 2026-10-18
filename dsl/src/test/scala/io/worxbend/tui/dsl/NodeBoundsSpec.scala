package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.runtime.ReactiveScope

import org.scalatest.funsuite.AnyFunSuite

/** The per-node bounds recorder behind `boundsOf`, driven through a bare `ElementHost` the way an embedding loop would.
  *
  * The one geometry worth pinning is the scroll view: content is painted into an offscreen buffer in content
  * coordinates, so a recorded rect that was not mapped through the viewport would answer where the row *would* be in
  * the document, not where it is on the frame.
  */
final class NodeBoundsSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  private def buffer(width: Int, height: Int): Buffer = Buffer(Rect(0, 0, width, height))

  /** Ten one-row keyed leaves in a four-row viewport placed at (2, 1): with an overflow there is a scrollbar column, so
    * the content is nine cells wide.
    */
  private def scrolled(state: ScrollViewState): View =
    positioned(2, 1, 10, 4)(
      scrollView(column((0 until 10).map(i => text(s"row $i").key(s"r$i"))*), 10, state)
    )

  test("boundsOf answers the frame rect of a keyed leaf inside a scroll view scrolled by three rows"):
    val host  = ElementHost()
    host.recordBounds = true
    val state = ScrollViewState()
    val view  = scrolled(state)
    host.render(Rect(0, 0, 20, 7), buffer(20, 7), Theme.Dark, view)
    assert(host.boundsOf("r0").contains(Rect(2, 1, 9, 1)))

    state.scrollDown(3)
    host.render(Rect(0, 0, 20, 7), buffer(20, 7), Theme.Dark, view)
    // content row 5 sits two rows below the viewport's top once three rows have scrolled past
    assert(host.boundsOf("r5").contains(Rect(2, 3, 9, 1)))

  test("a keyed leaf scrolled out of view, and a key nobody declared, have no bounds"):
    val host  = ElementHost()
    host.recordBounds = true
    val state = ScrollViewState()
    val view  = scrolled(state)
    host.render(Rect(0, 0, 20, 7), buffer(20, 7), Theme.Dark, view)
    state.scrollDown(3)
    host.render(Rect(0, 0, 20, 7), buffer(20, 7), Theme.Dark, view)
    assert(host.boundsOf("r0").isEmpty)
    assert(host.boundsOf("r9").isEmpty)
    assert(host.boundsOf("nobody").isEmpty)

  test("with recording off nothing is recorded, and asking turns it on for the next frame"):
    val host = ElementHost()
    assert(!host.recordBounds)
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, button("save")(()).key("save"))
    assert(host.boundsOf("save").isEmpty)
    assert(host.recordBounds, "asking for bounds is the opt-in")
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, button("save")(()).key("save"))
    // the root node is handed the whole render area, whatever it would claim inside a container
    assert(host.boundsOf("save").contains(Rect(0, 0, 20, 3)))

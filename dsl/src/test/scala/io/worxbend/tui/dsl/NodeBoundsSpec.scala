package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Style}
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
    host.enableBoundsRecording()
    assert(host.recordingBounds)
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
    assert(!host.recordingBounds)
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, button("save")(()).key("save"))
    assert(host.boundsOf("save").isEmpty)
    assert(host.recordingBounds, "asking for bounds is the opt-in")
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, button("save")(()).key("save"))
    // the root node is handed the whole render area, whatever it would claim inside a container
    assert(host.boundsOf("save").contains(Rect(0, 0, 20, 3)))

  /** How many nodes of a decorated tree carry a bounds wrapper — directly, or under their input wrapper. */
  private def boundsWrappers(node: Element): Int =
    val own = node match
      case _: BoundsElement        => 1
      case tracked: TrackedElement =>
        tracked.inner match
          case _: BoundsElement => 1
          case _                => 0
      case pointer: PointerElement =>
        pointer.inner match
          case _: BoundsElement => 1
          case _                => 0
      case _                       => 0
    own + node.children.map(boundsWrappers).sum

  test("with recording off the decorated tree carries no bounds wrapper, so a frame allocates nothing for it"):
    def view: Element = column(text("a"), button("b")(()), scrollView(text("c"), 1, ScrollViewState()))
    assert(boundsWrappers(FocusPass.decorate(view, FocusTracker(), Style.Default.reverse)) == 0)
    assert(boundsWrappers(FocusPass.decorate(view, FocusTracker(), Style.Default.reverse, Some(NodeBounds()))) == 5)

  test("the input targets hit-testing, MouseContext and spatial navigation read live in the one NodeBounds"):
    val host = ElementHost()
    host.enableBoundsRecording()
    val view = positioned(2, 1, 10, 3)(button("ok")(()).key("ok"))
    host.render(Rect(0, 0, 20, 7), buffer(20, 7), Theme.Dark, view)
    assert(host.tracker.bounds eq host.bounds, "the focus tracker records into the host's recorder, not one of its own")
    val node = host.boundsOf("ok")
    assert(node.isDefined)
    // one recorder, one translation: the input target and the node it wraps cannot disagree about where it landed
    assert(host.bounds.targetBounds(InputTarget.Focus(0)).map(_.layoutArea) == node)
    assert(host.tracker.areaOf(0) == node)

  test("narrow recording keeps keyed nodes, containers and input targets, and leaves unkeyed leaves out"):
    def view: Element =
      column(text("plain"), text("named").key("named"), button("b")(()), scrollView(text("c"), 1, ScrollViewState()))
    // column, the keyed text, the button and the scroll view; the two unkeyed texts are not wrapped
    assert(
      boundsWrappers(
        FocusPass.decorate(view, FocusTracker(), Style.Default.reverse, Some(NodeBounds()), fullBounds = false)
      ) == 4
    )
    val host          = ElementHost()
    host.enableBoundsRecording()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.boundsOf("named").isDefined)
    assert(host.bounds.current.entries.size == 4)
    host.enableFullBoundsRecording()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.bounds.current.entries.size == 6)

  test("narrow recording still records every node under a click-outside overlay, so a press on its text stays inside"):
    var outside       = 0
    def view: Element =
      dismissibleOverlay(centered(10, 2)(column(text("Really?"), text("press esc"))))(() => outside += 1)
    val host          = ElementHost()
    host.enableBoundsRecording()
    host.render(Rect(0, 0, 20, 6), buffer(20, 6), Theme.Dark, view)
    host.render(Rect(0, 0, 20, 6), buffer(20, 6), Theme.Dark, view)
    val _             = host.dispatchMouse(
      io.worxbend.tui.core
        .MouseEvent(io.worxbend.tui.core.Position(6, 2), io.worxbend.tui.core.MouseEventKind.Down, KeyModifiers.None)
    )
    assert(outside == 0, "a press on the dialog's text is inside it")
    val _             = host.dispatchMouse(
      io.worxbend.tui.core
        .MouseEvent(io.worxbend.tui.core.Position(0, 0), io.worxbend.tui.core.MouseEventKind.Down, KeyModifiers.None)
    )
    assert(outside == 1)

  test("with selection on and nothing selected, unfocusable text leaves carry no wrapper; a press still selects"):
    import io.worxbend.tui.core.{MouseEvent, MouseEventKind, Position}
    val host          = ElementHost()
    host.textSelection = true
    def view: Element = column(text("alpha beta"), text("gamma"))
    host.render(Rect(0, 0, 12, 2), buffer(12, 2), Theme.Dark, view)
    assert(host.decoratedTree.map(boundsWrappers).contains(0))
    assert(host.dispatchMouse(MouseEvent(Position(0, 0), MouseEventKind.Down, KeyModifiers.None)))
    assert(!host.dispatchMouse(MouseEvent(Position(4, 0), MouseEventKind.Drag, KeyModifiers.None)))
    val _             = host.dispatchMouse(MouseEvent(Position(4, 0), MouseEventKind.Up, KeyModifiers.None))
    // the press resolves against the next frame, which records the leaves again
    host.render(Rect(0, 0, 12, 2), buffer(12, 2), Theme.Dark, view)
    assert(host.pollStates(), "resolving the press owes the frame that shows the highlight")
    host.render(Rect(0, 0, 12, 2), buffer(12, 2), Theme.Dark, view)
    assert(host.selectedText.contains("alpha"))
    assert(host.decoratedTree.map(boundsWrappers).exists(_ > 0))

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, KeyEvent, KeyModifiers, Rect, Style}
import io.worxbend.tui.runtime.ReactiveScope

import org.scalatest.funsuite.AnyFunSuite

/** The fused frame pass: one walk resolves the view and collects what the focus pass and the version watch need. Its
  * answers must be exactly the ones the separate walks it replaced give for the same resolved tree — those walks stay
  * as the reference they are checked against here.
  */
final class FramePassSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  private val picks  = ListState()
  private val scroll = ScrollViewState()
  private val lazily = LazyListState()

  /** A tree with every shape the walks treat specially: inert and disabled subtrees, focus groups, traps, autofocus,
    * responsive nodes inside a covered layer, a lazy list, a scroll view, and one state painted twice.
    */
  private def tangled: Element =
    layers(
      FocusPass.suppressFocus(
        column(
          input(TextInputState()).key("covered"),
          responsive(_ => row(button("hidden")(()).key("hidden"), list("a" :: Nil, picks))),
        )
      ),
      column(
        focusGroup(button("one")(()).key("g1"), button("two")(()).autofocus.key("g2")),
        column(input(TextInputState()).key("trap-in"), checkbox("c", Signal(false)).key("trap-cb")).trapFocus,
        column(button("off")(()).key("disabled")).disabled(),
        list(Seq("x", "y"), picks),
        scrollView(column((0 until 5).map(i => button(s"b$i")(()).key(s"s$i"))*), 5, scroll),
        lazyList(1000, 1, lazily)(i => button(s"row $i")(()).key(s"l$i")),
        responsive(size => if size.width > 40 then input(TextInputState()).autofocus.key("wide") else text("narrow")),
        responsive.local(size => text(s"local ${size.width}")).length(1),
      ),
    )

  private def framed(view: Element, size: Size): ResolvedFrame =
    ResponsivePass.resolveFrame(
      view,
      size,
      tree => tree.widget.render(Rect(0, 0, size.width, size.height), Buffer(Rect(0, 0, size.width, size.height))),
    )

  test("the fused walk collects the tab order, autofocus, focus scopes and watched states the separate walks do"):
    Seq(Size(80, 24), Size(30, 10)).foreach { size =>
      val frame = framed(tangled, size)
      val tree  = frame.tree
      assert(frame.analysis.focusKeys == FocusPass.focusKeys(tree), s"at $size")
      assert(frame.analysis.autofocus == FocusPass.autofocusRequest(tree), s"at $size")
      assert(frame.analysis.scopes == FocusScope.of(tree), s"at $size")
      assert(frame.analysis.states.map(identity) == StateWatch.collect(tree).map(_._1), s"at $size")
    }

  test("the analysis of an unresolved tree matches the separate walks over the same tree"):
    val tree     = tangled
    val analysis = FrameAnalysis.of(tree)
    assert(analysis.focusKeys == FocusPass.focusKeys(tree))
    assert(analysis.autofocus == FocusPass.autofocusRequest(tree))
    assert(analysis.scopes == FocusScope.of(tree))

  test("keys reach the focused element through the path retained by decoration, across wrappers and scroll views"):
    val pressed    = scala.collection.mutable.ArrayBuffer.empty[String]
    val view: View =
      column(
        text("head"),
        scrollView(
          column((0 until 4).map(i => button(s"b$i")(pressed += s"b$i").key(s"b$i"))*),
          4,
          ScrollViewState(),
        ),
        lazyList(100, 1, LazyListState())(i => button(s"l$i")(pressed += s"l$i").key(s"l$i")),
      ).onKeyEvent {
        case KeyEvent(KeyCode.Char('z'), _) =>
          pressed += "outer"
          true
        case _                              => false
      }
    val host       = ElementHost()
    host.recordBounds = true
    val buffer     = Buffer(Rect(0, 0, 20, 12))
    Seq("b2", "l3").foreach { key =>
      host.render(buffer.area, buffer, Theme.Dark, view)
      assert(host.focusToKey(key))
      host.render(buffer.area, buffer, Theme.Dark, view)
      assert(host.dispatchKey(KeyEvent(KeyCode.Enter, KeyModifiers.None)))
      assert(host.dispatchKey(KeyEvent(KeyCode.Char('z'), KeyModifiers.None)))
    }
    assert(pressed.toSeq == Seq("b2", "outer", "l3", "outer"))

  test("the path decoration retains leads through the decorated tree to the one focused element"):
    val frame   = framed(tangled, Size(80, 24))
    val tracker = FocusTracker()
    tracker.reconcile(frame.analysis.focusKeys, frame.analysis.autofocus, frame.analysis.scopes)
    Seq("g2", "trap-cb", "s3", "l1").foreach { key =>
      assert(tracker.focusToKey(key), s"$key not in ${frame.analysis.focusKeys}")
      tracker.reconcile(frame.analysis.focusKeys, frame.analysis.autofocus, frame.analysis.scopes)
      var retained = Option.empty[Vector[Int]]
      val tree     = FocusPass.decorate(frame.tree, tracker, Style.Default, onFocusPath = path => retained = path)
      val leaf     = retained.get.foldLeft(tree)((node, position) => node.children(position))
      assert(leaf.props.focused && leaf.props.focusKey.contains(key), key)
    }

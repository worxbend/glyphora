package io.worxbend.tui.dsl

import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger

/** A scroll view resolves where to scroll *before* it paints, so a reveal costs one paint and every recorded rect is
  * the one on screen; and every scroll view around a target scrolls, not just the innermost.
  */
final class ScrollRevealSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  private def buttons(prefix: String, count: Int): Seq[Element] =
    (0 until count).map(n => button(f"$prefix $n%02d")(()).key(s"$prefix$n"))

  private def screen(buffer: Buffer): String =
    (buffer.area.y until buffer.area.bottom)
      .map(y => (buffer.area.x until buffer.area.right).map(x => buffer.get(x, y).symbol).mkString)
      .mkString("\n")

  test("focusing a child below the window scrolls on that frame, paints the content once, and records it on screen"):
    val host   = ElementHost()
    host.enableBoundsRecording()
    val state  = ScrollViewState()
    val paints = AtomicInteger()
    val probe  = widget((_, _) => { val _ = paints.incrementAndGet() })
    val view   =
      (scope: ReactiveScope, theme: Theme) ?=> scrollView(column((probe.length(1) +: buttons("item", 30))*), state)
    val buffer = Buffer(Rect(0, 0, 20, 10))
    host.render(buffer.area, buffer, Theme.Dark, view)
    assert(host.focusToKey("item20"))
    paints.set(0)
    host.render(buffer.area, buffer, Theme.Dark, view)
    assert(paints.get == 1, "the content is painted once on the frame that reveals")
    assert(state.offset == 12, "row 21 (the probe is row 0) is the last of rows 12..21")
    assert(screen(buffer).contains("item 20"))
    assert(host.boundsOf("item20").map(_.y).contains(21 - 12), "the recorded rect is where it is on screen")

  test("a target inside nested scroll views is revealed by every one of them"):
    val host   = ElementHost()
    host.enableBoundsRecording()
    val outer  = ScrollViewState()
    val inner  = ScrollViewState()
    val above  = (0 until 10).map(n => text(s"above $n").length(1))
    val below  = (0 until 10).map(n => text(s"below $n").length(1))
    val view   = (scope: ReactiveScope, theme: Theme) ?=>
      scrollView(
        column((above :+ scrollView(column(buttons("item", 30)*), inner).length(5)) ++ below*),
        outer,
      )
    val buffer = Buffer(Rect(0, 0, 20, 8))
    host.render(buffer.area, buffer, Theme.Dark, view)
    assert(host.focusToKey("item25"))
    (1 to 3).foreach(_ => host.render(buffer.area, buffer, Theme.Dark, view))
    assert(screen(buffer).contains("item 25"), screen(buffer))
    assert(inner.offset > 0 && outer.offset > 0)
    assert(host.boundsOf("item25").exists(rect => rect.y >= 0 && rect.y < 8))

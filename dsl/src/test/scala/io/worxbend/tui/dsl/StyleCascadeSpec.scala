package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Color, Modifiers, Rect, Style, Widget}
import io.worxbend.tui.runtime.ReactiveScope

import org.scalatest.funsuite.AnyFunSuite

/** A leaf that counts every time something rebuilds it, so a test can tell a lazy cascade from an eager rewrite. */
private final case class CountingLeaf(counter: Array[Int], props: ElementProps = ElementProps()) extends Element:
  type Self = CountingLeaf
  override def debugName: String = "countingLeaf"
  def widget: Widget             = (area, buffer) => buffer.setString(area.x, area.y, "x", props.style)
  private[dsl] def withProps(props: ElementProps): CountingLeaf =
    counter(0) += 1
    copy(props = props)

/** `withStyle` as a paint-time cascade: the subtree is wrapped, not rewritten, and the style reaches it when painted.
  */
final class StyleCascadeSpec extends AnyFunSuite:

  private def painted(root: Element, width: Int = 4, height: Int = 2): Buffer =
    val buffer          = Buffer(Rect(0, 0, width, height))
    val host            = ElementHost()
    given ReactiveScope = ReactiveScope.untracked
    host.render(buffer.area, buffer, Theme.Dark, root)
    buffer

  test("withStyle over a 1000-node subtree builds no new tree"):
    val counter = Array(0)
    val tree    = column((1 to 1000).map(_ => CountingLeaf(counter))*)
    val styled  = withStyle(_.bold)(tree)
    assert(counter(0) == 0, "no node of the subtree was rebuilt")
    assert(styled.children.head eq tree)

  test("the inherited style reaches every descendant when the frame is painted"):
    val buffer = painted(withStyle(_.withFg(Color.Red))(row(text("a").fg(Color.Green), text("b"))))
    assert(buffer.get(0, 0).style.fg.contains(Color.Green)) // a child's own style wins
    val b = (0 until 4).find(x => buffer.get(x, 0).symbol == "b").getOrElse(fail("no b painted"))
    assert(buffer.get(b, 0).style.fg.contains(Color.Red)) // inherited default

  test("modifiers union down the tree, and a child opts out with the matching not-builder"):
    val buffer = painted(withStyle(_.bold)(column(text("ab"), text("cd").styled(_.notBold))))
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(!buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Bold))

  test("nested scopes layer, the inner one over the outer"):
    val buffer = painted(withStyle(_.withFg(Color.Red).bold)(withStyle(_.withFg(Color.Blue))(text("ab"))))
    assert(buffer.get(0, 0).style == Style.Default.withFg(Color.Blue).bold)

  test("an undecorated scope still applies its style when painted directly"):
    val buffer = Buffer(Rect(0, 0, 2, 1))
    withStyle(_.italic)(text("ab")).widget.render(buffer.area, buffer)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Italic))

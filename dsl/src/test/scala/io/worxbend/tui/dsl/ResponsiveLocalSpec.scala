package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, KeyEvent, KeyModifiers, Rect}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.BufferAssertions.trimmedLines

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** `responsive.local` — a container query: the builder sees the node's own area, found by a layout pre-pass, while
  * plain `responsive` keeps answering with the terminal's size.
  */
final class ResponsiveLocalSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  private def draw(host: ElementHost, buffer: Buffer, view: View): Unit =
    buffer.reset()
    host.render(buffer.area, buffer, Theme.Dark, view)

  private def sidebar(seen: mutable.ArrayBuffer[Size]): Element =
    responsive.local { size =>
      seen += size
      if size.width < 40 then text("narrow") else text("wide")
    }

  test("in a 30-wide panel it picks the narrow branch while the terminal is 120 wide"):
    val seen       = mutable.ArrayBuffer.empty[Size]
    val media      = mutable.ArrayBuffer.empty[Size]
    val view: View =
      row(
        panel("Side")(sidebar(seen)).length(30),
        responsive { size =>
          media += size
          text(if size.width < 40 then "media narrow" else "media wide")
        }.fill,
      )
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 120, 10))
    draw(host, buffer, view)
    val screen     = trimmedLines(buffer).mkString("\n")
    assert(screen.contains("narrow"))
    assert("wide".r.findAllIn(screen).size == 1, "only the media query sees the terminal's width")
    assert(screen.contains("media wide"))
    // the panel's border takes a column either side and a row above and below
    assert(seen.last == Size(28, 8))
    assert(media.forall(_ == Size(120, 10)))

  test("the branch it picks is part of the tree on the very first frame: tab stops, keys, focus by key"):
    val field      = TextInputState("")
    val view: View =
      row(
        panel("Side")(responsive.local(size => if size.width < 40 then input(field).key("q") else text("wide")))
          .length(30),
        text("main").fill,
      )
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 120, 6))
    draw(host, buffer, view)
    assert(host.focusToKey("q"))
    draw(host, buffer, view)
    assert(host.dispatchKey(KeyEvent(KeyCode.Char('x'), KeyModifiers.None)))
    assert(field.value == "x")

  test("a local query nested in a local branch sees its own, smaller area"):
    val inner      = mutable.ArrayBuffer.empty[Size]
    val view: View =
      responsive.local { outer =>
        row(
          text(s"outer ${outer.width}").length(20),
          responsive.local { size =>
            inner += size
            text(s"inner ${size.width}")
          }.fill,
        )
      }
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 50, 3))
    draw(host, buffer, view)
    assert(trimmedLines(buffer).head.startsWith("outer 50"))
    assert(trimmedLines(buffer).head.contains("inner 30"))
    assert(inner.last == Size(30, 3))

  test("its own constraint, not its branch, decides its size"):
    val seen       = mutable.ArrayBuffer.empty[Size]
    val view: View = column(sidebar(seen).length(2), text("below").fill)
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 6))
    draw(host, buffer, view)
    assert(seen.last == Size(20, 2))
    assert(trimmedLines(buffer)(2) == "below")

  test("hooks inside the branch keep their identity across frames"):
    val handed     = mutable.ArrayBuffer.empty[TextInputState]
    val view: View =
      row(
        responsive
          .local { _ =>
            val state = useState(TextInputState("kept"))
            handed += state
            input(state)
          }
          .length(30),
        text("main").fill,
      )
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 80, 3))
    (1 to 3).foreach(_ => draw(host, buffer, view))
    assert(handed.distinct.size == 1)

  test("Snapshot resolves local queries the way a live frame does"):
    val view: View =
      row(panel("Side")(responsive.local(s => text(if s.width < 40 then "narrow" else "wide"))).length(30), text("x"))
    val snapshot   = Snapshot.render(Size(120, 5))(view)
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 120, 5))
    draw(host, buffer, view)
    assert(trimmedLines(snapshot) == trimmedLines(buffer))
    assert(trimmedLines(snapshot).mkString.contains("narrow"))

  test("drawn without a host it builds against the area it is handed"):
    val element = responsive.local(size => text(s"w=${size.width}"))
    val buffer  = Buffer(Rect(0, 0, 12, 1))
    element.widget.render(buffer.area, buffer)
    assert(trimmedLines(buffer) == Seq("w=12"))

  test("the pre-pass paints only the way to the local query: a sibling's render runs once per frame"):
    var renders                               = 0
    val counting: io.worxbend.tui.core.Widget = (area, buffer) =>
      renders += 1
      buffer.setString(area.x, area.y, "counted", io.worxbend.tui.core.Style.Default)
    val seen                                  = mutable.ArrayBuffer.empty[Size]
    val view: View                            = row(widget(counting).length(10), panel("Side")(sidebar(seen)).fill)
    val host                                  = ElementHost()
    val buffer                                = Buffer(Rect(0, 0, 60, 5))
    draw(host, buffer, view)
    assert(seen.nonEmpty, "the local query was measured")
    assert(renders == 1, s"a widget that mutates caller state on render would have done so $renders times")
    assert(trimmedLines(buffer).head.startsWith("counted"))

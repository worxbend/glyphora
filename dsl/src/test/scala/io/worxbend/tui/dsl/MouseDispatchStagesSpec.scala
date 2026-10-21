package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect}
import io.worxbend.tui.runtime.ReactiveScope

import org.scalatest.funsuite.AnyFunSuite

final class MouseDispatchStagesSpec extends AnyFunSuite:
  private given ReactiveScope                            = ReactiveScope.untracked
  private val area                                       = Rect(0, 0, 12, 2)
  private def paint(host: ElementHost, view: View): Unit = host.render(area, Buffer(area), Theme.Dark, view)
  private def mouse(kind: MouseEventKind): MouseEvent    = MouseEvent(Position(1, 1), kind, KeyModifiers.None)

  test("every press observer runs topmost first even when consumed, before focus and target delivery"):
    val host                            = ElementHost()
    var trace                           = Vector.empty[String]
    def observer(name: String): Element =
      DropdownScrimElement(DropdownState(), passThrough = true).onMouseEvent { event =>
        trace :+= s"$name:${event.clickCount}:${host.tracker.focusedIndex}"
        true
      }
    val target                          = button("second")(()).key("second").onMouseEvent { event =>
      trace :+= s"target:${event.kind}:${event.clickCount}:${host.tracker.focusedIndex}"
      false
    }
    val view: View                      = layers(
      column(button("first")(()).length(1), target.length(1)),
      observer("lower"),
      observer("upper"),
    )
    paint(host, view)
    assert(host.dispatchMouse(mouse(MouseEventKind.Down)))
    assert(trace == Vector("upper:1:0", "lower:1:0", "target:Down:1:1"))
    trace = Vector.empty
    val _                               = host.dispatchMouse(mouse(MouseEventKind.Up))
    assert(trace == Vector("target:Up:1:1"), "release does not revisit press observers")

  test("consumed observers do not prevent post-delivery selection or intercepting its drag"):
    val host       = ElementHost()
    host.textSelection = true
    var trace      = Vector.empty[String]
    val observer   = DropdownScrimElement(DropdownState(), passThrough = true).onMouseEvent { _ =>
      trace :+= "observer"
      true
    }
    val target     = text("selectable").onMouseEvent { _ => trace :+= "target"; false }
    val view: View = layers(target, observer)
    paint(host, view)
    assert(host.dispatchMouse(mouse(MouseEventKind.Down)))
    assert(trace == Vector("observer", "target"))
    assert(host.gestures.selection.isPending)
    trace = Vector.empty
    val _          = host.dispatchMouse(mouse(MouseEventKind.Drag))
    assert(trace.isEmpty, "a gesture in progress bypasses both observers and target")

  test("a throwing observer stops later observers, focus transfer and the target"):
    val host       = ElementHost()
    var trace      = Vector.empty[String]
    val failure    = new IllegalArgumentException("observer")
    val lower      = DropdownScrimElement(DropdownState(), passThrough = true).onMouseEvent { _ =>
      trace :+= "lower"
      true
    }
    val upper      = DropdownScrimElement(DropdownState(), passThrough = true).onMouseEvent { _ =>
      trace :+= "upper"
      throw failure
    }
    val view: View = layers(
      column(button("first")(()).length(1), button("second") { trace :+= "target" }.length(1)),
      lower,
      upper,
    )
    paint(host, view)
    assert(intercept[IllegalArgumentException](host.dispatchMouse(mouse(MouseEventKind.Down))) eq failure)
    assert(trace == Vector("upper"))
    assert(host.tracker.focusedIndex == 0)

  test("a throwing target sees transferred focus but does not install pressed state"):
    val host       = ElementHost()
    var trace      = Vector.empty[Int]
    val failure    = new IllegalArgumentException("target")
    val view: View = column(
      button("first")(()).length(1),
      button("second")(()).length(1).onMouseEvent { _ =>
        trace :+= host.tracker.focusedIndex
        throw failure
      },
    )
    paint(host, view)
    assert(intercept[IllegalArgumentException](host.dispatchMouse(mouse(MouseEventKind.Down))) eq failure)
    assert(trace == Vector(1))
    assert(host.tracker.focusedIndex == 1)
    assert(host.tracker.pressedTarget.isEmpty)

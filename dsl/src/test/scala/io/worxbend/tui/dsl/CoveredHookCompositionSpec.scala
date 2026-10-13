package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

final class CoveredHookCompositionSpec extends AnyFunSuite with PilotFixture:
  test("covered entry hooks preserve eager identity but rebuild nested deferred state from current data"):
    val app     = new TuiApp:
      private val data                              = Signal("old")
      private val page                              = Screen.full {
        val eager = keyed("eager")(useSignal(0))
        column(
          text(s"eager=${eager.get};data=${data.get}"),
          responsive { _ =>
            responsive { _ =>
              val deferred = keyed("deferred")(useSignal(0))
              text(s"deferred=${deferred.get}").focusable.onKeyEvent {
                case KeyEvent(KeyCode.Char('y'), _) =>
                  deferred.update(_ + 1)
                  true
                case _                              => false
              }
            }
          },
        ).onKeyEvent {
          case KeyEvent(KeyCode.Char('x'), _) =>
            eager.update(_ + 1)
            true
          case _                              => false
        }
      }
      private val cover                             = Screen.full(text("cover"))
      private val modal                             = Screen(positioned(0, 2, 10, 1)(text("modal")))
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "page")(pushScreen(page)),
        binding("c", "cover")(pushScreen(cover)),
        binding("m", "modal")(pushScreen(modal)),
        binding("u", "update data")(data.set("new")),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element = text("base")
    val backend = HeadlessBackend(Size(30, 4))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("p").waitForIdle()
    pilot.press("x").waitForIdle()
    pilot.press("y").waitForIdle()
    assert(pilot.screenLines.take(2).map(_.trim) == Seq("eager=1;data=old", "deferred=1"))
    pilot.press("m").waitForIdle()
    pilot.press("c").waitForIdle()
    pilot.press("m").waitForIdle()
    pilot.press("u").waitForIdle()
    assert(pilot.screenLines.head.trim == "cover")
    pilot.press("b").waitForIdle()
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.take(2).map(_.trim) == Seq("eager=1;data=new", "deferred=0"))
    assert(pilot.screenLines(2).trim == "modal")
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("pushing the same full-screen value twice retains hooks by navigation entry"):
    val app     = new TuiApp:
      private val page                              = Screen.full {
        val count = keyed("counter")(useSignal(0))
        text(s"count=${count.get}").onKeyEvent {
          case KeyEvent(KeyCode.Char('+'), _) =>
            count.update(_ + 1)
            true
          case _                              => false
        }
      }
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "page")(pushScreen(page)),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element = text("base")
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("p").waitForIdle()
    pilot.press("+").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("p").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=0")
    pilot.press("+").waitForIdle()
    pilot.press("+").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=2")
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("p").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=0")
    pilot.interrupt()
    assert(pilot.awaitTermination())

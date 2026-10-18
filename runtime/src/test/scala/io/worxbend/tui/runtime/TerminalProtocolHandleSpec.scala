package io.worxbend.tui.runtime

import io.worxbend.tui.core.{ColorScheme, Event, KeyCode, KeyEvent, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, PointerShape}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

/** The three out-of-band terminal operations the modern OSC set added, reached from an event handler through
  * [[RunnerHandle]] the way the clipboard already is — and the colour-scheme notification arriving as an ordinary
  * event.
  */
final class TerminalProtocolHandleSpec extends AnyFunSuite:

  private def render(frame: Frame): Unit = frame.renderWidget((_, _) => (), frame.area)

  test("pointer shape, desktop notification and taskbar progress reach the backend through the handle"):
    val backend = HeadlessBackend(Size(20, 4))
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        (event, handle) =>
          event match
            case Event.Key(KeyEvent(KeyCode.Char('p'), _)) =>
              handle.setPointerShape(PointerShape.Pointer)
              EventOutcome.Ignored
            case Event.Key(KeyEvent(KeyCode.Char('n'), _)) =>
              handle.notifyDesktop("build", "done")
              EventOutcome.Ignored
            case Event.Key(KeyEvent(KeyCode.Char('t'), _)) =>
              handle.setTaskbarProgress(Some(0.75))
              EventOutcome.Ignored
            case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
              handle.quit()
              EventOutcome.Ignored
            case _                                         => EventOutcome.Ignored
        ,
        render,
      )
    }
    try
      pilot.waitForIdle()
      pilot.pressKey(KeyCode.Char('p')).pressKey(KeyCode.Char('n')).pressKey(KeyCode.Char('t')).waitForIdle()
      assert(backend.currentPointerShape == PointerShape.Pointer)
      assert(backend.desktopNotifications == Seq(("build", "done")))
      assert(backend.taskbarProgressRequests == Seq(Some(0.75)))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
      // the simulated terminal is handed back with the user's own pointer and no progress showing
      assert(backend.currentPointerShape == PointerShape.Default)
      assert(backend.currentTaskbarProgress.isEmpty)
    finally pilot.close()

  test("a colour-scheme change is delivered to the handler like any other event"):
    val backend = HeadlessBackend(Size(20, 4))
    val seen    = AtomicReference[Option[ColorScheme]](None)
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        (event, handle) =>
          event match
            case Event.ColorSchemeChanged(scheme)          =>
              seen.set(Some(scheme))
              EventOutcome.Redraw
            case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
              handle.quit()
              EventOutcome.Ignored
            case _                                         => EventOutcome.Ignored
        ,
        render,
      )
    }
    try
      pilot.waitForIdle()
      val drawn = backend.drawCount
      // the bytes a mode 2031 terminal sends, through the production decoder
      pilot.sendBytes("\u001b[?997;2n".map(_.toInt)*).waitUntil("the scheme reached the handler")(seen.get().nonEmpty)
      assert(seen.get().contains(ColorScheme.Light))
      pilot.waitUntil("the handler's redraw was honoured")(backend.drawCount > drawn)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

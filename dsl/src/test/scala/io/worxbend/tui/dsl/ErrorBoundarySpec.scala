package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Rect, Size, Style}
import io.worxbend.tui.runtime.{JulBridge, LogLevel, LogRing}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

import java.util.logging.Logger

/** `errorBoundary(fallback)(subtree)`: a subtree that throws while being built or painted is replaced by the fallback
  * in its own area, and the next frame tries again.
  */
final class ErrorBoundarySpec extends AnyFunSuite with PilotFixture:

  test("a child that throws on frame 1 paints the fallback; fixed by frame 2, the normal content returns"):
    val backend = HeadlessBackend(Size(30, 3))
    val app     = new TuiApp:
      val broken                                    = Signal(true)
      override def bindings: KeyBindings            = KeyBindings(
        binding("f", "fix")(broken.set(false)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        column(
          text("header"),
          errorBoundary(error => text(s"fallback: ${error.getMessage}")) {
            if broken.get then throw IllegalStateException("boom") else text("healthy")
          },
        )
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    assert(pilot.screenLines.head == "header")
    assert(pilot.screenLines(1) == "fallback: boom")
    pilot.typeText("f").waitForIdle()
    assert(pilot.screenLines(1) == "healthy")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a widget that throws while painting is replaced by the fallback in exactly its area"):
    val exploding = widget((area: Rect, buffer) =>
      buffer.setString(area.x, area.y, "half drawn", Style.Default)
      throw IllegalStateException("paint failed")
    )
    val tree      = column(text("above"), errorBoundary(_ => text("could not draw"))(exploding), text("below"))
    val frame     = BufferAssertions.rendered(tree.widget, 20, 3)
    val rows      = BufferAssertions.text(frame).linesIterator.map(_.trim).toSeq
    assert(rows == Seq("above", "could not draw", "below"))

  test("the failure is logged through java.util.logging, so a ring attached by JulBridge sees it"):
    val ring   = LogRing(10)
    val logger = Logger.getLogger("io.worxbend.tui.dsl")
    logger.setUseParentHandlers(false)
    val bridge = JulBridge.attach(ring, logger)
    try
      def explode: Element = throw IllegalStateException("boom")
      val tree             = errorBoundary(_ => text("fallback"))(explode)
      val _                = BufferAssertions.rendered(tree.widget, 20, 1)
      val logged           = ring.snapshot
      assert(logged.sizeIs == 1)
      assert(logged.head.level == LogLevel.Error)
      assert(logged.head.throwable.exists(_.getMessage == "boom"))
    finally
      bridge.cancel()
      logger.setUseParentHandlers(true)

  test("a healthy subtree is unchanged by the boundary: it renders, measures and focuses as itself"):
    val inner = text("fine").wrapped
    val tree  = errorBoundary(_ => text("fallback"))(inner)
    assert(BufferAssertions.text(BufferAssertions.rendered(tree.widget, 10, 1)).trim == "fine")
    assert(tree.intrinsicHeight(2) == inner.intrinsicHeight(2))
    assert(tree.children == Seq(inner))

  test("the same failure, rebuilt frame after frame, is logged at SEVERE once and then only at FINE"):
    val ring   = LogRing(10)
    val logger = Logger.getLogger("io.worxbend.tui.dsl")
    logger.setUseParentHandlers(false)
    val bridge = JulBridge.attach(ring, logger)
    try
      def explode: Element = throw IllegalArgumentException("always broken")
      (1 to 3).foreach { _ =>
        val tree = errorBoundary(_ => text("fallback"))(explode)
        val _    = BufferAssertions.rendered(tree.widget, 20, 1)
      }
      assert(ring.snapshot.count(_.level == LogLevel.Error) == 1, ring.snapshot)
    finally
      bridge.cancel()
      logger.setUseParentHandlers(true)

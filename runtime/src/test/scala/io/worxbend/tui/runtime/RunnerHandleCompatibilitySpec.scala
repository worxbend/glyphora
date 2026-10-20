package io.worxbend.tui.runtime

import io.worxbend.tui.core.Widget
import io.worxbend.tui.terminal.{MouseCaptureMode, PointerShape, TaskbarState}

import org.scalatest.funsuite.AnyFunSuite

final class RunnerHandleCompatibilitySpec extends AnyFunSuite:
  test("optional handle additions preserve the documented legacy fallback without hiding required operations"):
    var progress = Option.empty[Double]
    val legacy   = new RunnerHandle:
      def quit(): Unit                                     = ()
      def runOnRenderThread(body: => Unit): Unit           = body
      def requestRedraw(): Unit                            = ()
      def copyToClipboard(text: String): Unit              = { val _ = text }
      def suspend(body: => Unit): Unit                     = body
      def printAbove(lines: Seq[String]): Unit             = { val _ = lines }
      def insertBefore(height: Int, widget: Widget): Unit  = { val _ = (height, widget) }
      def setPointerShape(shape: PointerShape): Unit       = { val _ = shape }
      def notifyDesktop(title: String, body: String): Unit = { val _ = (title, body) }
      def setTaskbarProgress(value: Option[Double]): Unit  = progress = value
    legacy.setTaskbarState(TaskbarState.Error(0.4))
    assert(progress.contains(0.4))
    legacy.setTaskbarState(TaskbarState.Cleared)
    assert(progress.isEmpty)
    legacy.setMouseCaptureMode(MouseCaptureMode.AllMotion) // deliberately unsupported on a legacy handle

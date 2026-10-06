package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyEvent, Size}
import io.worxbend.tui.dsl.*
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.util.Success
import scala.util.Try

final class TuiAppLifecycleSpec extends AnyFunSuite:
  private final class RecordingBackend(closeFailure: Option[Throwable] = None) extends io.worxbend.tui.terminal.Backend:
    private val inner                                                        = HeadlessBackend(Size(12, 3))
    var closes: Int                                                          = 0
    var setups: Int                                                          = 0
    def enableRawMode(): Either[io.worxbend.tui.terminal.BackendError, Unit] =
      setups += 1
      inner.enableRawMode()
    export inner.{
      size,
      draw,
      disableRawMode,
      enterAlternateScreen,
      leaveAlternateScreen,
      enableMouseCapture,
      disableMouseCapture,
      hideCursor,
      showCursor,
      readEvent,
    }
    def close(): Either[io.worxbend.tui.terminal.BackendError, Unit]         =
      closes += 1
      closeFailure match
        case Some(error) => Left(io.worxbend.tui.terminal.BackendError.Io(error))
        case None        => inner.close()

  for viaRun <- List(false, true); member <- List("config", "splash") do
    test(
      s"throwing $member closes the owned backend and stops exactly once via ${if viaRun then "run" else "runWith"}"
    ):
      val primary                                       = IllegalStateException(member)
      val backend                                       = RecordingBackend()
      var stops                                         = 0
      var cleanupOwner: Option[RenderThread.RenderLoop] = None
      val app                                           = new TuiApp:
        def view(using ReactiveScope, Theme): Element = text("unused")
        override def config: RunnerConfig             = if member == "config" then throw primary else RunnerConfig()
        override def splash: Option[SplashScreen]     = if member == "splash" then throw primary else None
        override protected def createBackend()
            : Either[io.worxbend.tui.terminal.BackendError, io.worxbend.tui.terminal.Backend] =
          Right(backend)
        override def onStop(): Unit                                                           =
          cleanupOwner = Some(RenderThread.capture())
          stops += 1
      val thrown                                        = intercept[IllegalStateException] {
        if viaRun then app.run() else app.runWith(backend)
      }
      assert(thrown eq primary)
      assert(backend.closes == 1)
      assert(backend.setups == 0)
      assert(stops == 1)
      assert(cleanupOwner.exists(loop => !loop.execute(())), "pre-run cleanup must retire its registered owner")

  test("pre-run cleanup preserves the preparation error through callback and backend failures"):
    val primary = IllegalStateException("config")
    val stop    = IllegalStateException("stop")
    val close   = IllegalStateException("close")
    val backend = RecordingBackend(Some(close))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = text("unused")
      override def config: RunnerConfig             = throw primary
      override def onStop(): Unit                   = throw stop
    assert(intercept[IllegalStateException](app.runWith(backend)) eq primary)
    assert(backend.closes == 1)
    assert(primary.getSuppressed.toList == List(stop, close))

  test("teardown attempts every screen and app callback preserving the run's primary failure"):
    val calls                                          = ListBuffer.empty[String]
    val retained                                       = Signal(0)
    val primary                                        = IllegalStateException("view")
    val innerFailure                                   = IllegalStateException("inner")
    val outerFailure                                   = IllegalStateException("outer")
    val stopFailure                                    = IllegalStateException("stop")
    def screen(name: String, error: Throwable): Screen = new Screen:
      def view(using ReactiveScope, Theme): Element = text(name)
      override def onLeave(): Unit                  =
        calls += name
        throw error
    val app                                            = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        val _ = retained.get
        throw primary
      override def onStart(): Unit                  =
        pushScreen(screen("outer", outerFailure))
        pushScreen(screen("inner", innerFailure))
      override def onStop(): Unit                   =
        calls += "stop"
        throw stopFailure
    val backend                                        = HeadlessBackend(Size(12, 3))
    assert(Try(app.runWith(backend)) == Success(Left(RunnerError.Handler(primary))))
    assert(calls.toList == List("inner", "outer", "stop"))
    assert(primary.getSuppressed.toList == List(innerFailure, outerFailure, stopFailure))
    assert(retained.subscriberCount == 0)
    assert(!backend.isRawMode)
    assert(!backend.isAlternateScreen)

  test("root scope is disposed before unregister even while another runner exists"):
    val retained = Signal(0)
    val ready    = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val other    = new Thread(
      () =>
        val loop = RenderThread.register(Thread.currentThread())
        ready.countDown()
        try
          val _ = release.await(10, TimeUnit.SECONDS)
        finally
          loop.close()
          RenderThread.unregister()
      ,
      "lifecycle-other-owner",
    )
    other.start()
    try
      assert(ready.await(10, TimeUnit.SECONDS))
      (1 to 3).foreach { _ =>
        val app     = new TuiApp:
          def view(using ReactiveScope, Theme): Element = text(retained.get.toString)
          override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
          override def onStop(): Unit                   = retained.update(_ + 1)
        val backend = HeadlessBackend(Size(12, 3))
        backend.postEvent(Event.Key(KeyEvent.parse("q").toOption.get))
        assert(app.runWith(backend) == Right(()))
        assert(retained.subscriberCount == 0)
      }
    finally
      release.countDown()
      other.join(10000)
      assert(!other.isAlive)

  test("reset attempts every unwound screen and leaves the stack empty on callback failure"):
    val calls  = ListBuffer.empty[String]
    val first  = IllegalStateException("first")
    val second = IllegalStateException("second")
    val app    = new TuiApp:
      def view(using ReactiveScope, Theme): Element = text("done")
      override def onStart(): Unit                  =
        pushScreen(new Screen:
          def view(using ReactiveScope, Theme): Element = text("outer")
          override def onLeave(): Unit                  =
            calls += "outer"
            throw second)
        pushScreen(new Screen:
          def view(using ReactiveScope, Theme): Element = text("inner")
          override def onLeave(): Unit                  =
            calls += "inner"
            throw first)
        resetScreens()
      override def onStop(): Unit                   = assert(screenDepthNow == 0)
    val result = app.runWith(HeadlessBackend(Size(12, 3)))
    assert(result == Left(RunnerError.Handler(first)))
    assert(calls.toList == List("inner", "outer"))
    assert(first.getSuppressed.toList == List(second))

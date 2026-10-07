package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.runtime.RunnerError
import io.worxbend.tui.terminal.{BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

final class RetainedScreenLifecycleSpec extends AnyFunSuite:

  private def backend(): HeadlessBackend =
    val result = HeadlessBackend(Size(30, 5))
    result.postEvent(Event.EndOfInput)
    result

  private final class TracedApp extends TuiApp:
    val trace: ListBuffer[String]                                                                = ListBuffer.empty
    var prepare: () => Unit                                                                      = () => ()
    var starting: () => Unit                                                                     = () => ()
    var stopping: () => Unit                                                                     = () => ()
    override def config: RunnerConfig                                                            =
      prepare()
      RunnerConfig()
    override def onStart(): Unit                                                                 =
      trace += "start"
      starting()
      trace += "started"
    override def onStop(): Unit                                                                  =
      trace += "stop"
      stopping()
    def view(using ReactiveScope, Theme): Element                                                = text("base")
    def open(screen: Screen): Unit                                                               = pushScreen(screen)
    def back(): Unit                                                                             = popScreen()
    def home(): Unit                                                                             = resetScreens()
    def swap(screen: Screen): Unit                                                               = replaceScreen(screen)
    def depth: Int                                                                               = screenDepthNow
    def screen(name: String, enter: () => Unit = () => (), leave: () => Unit = () => ()): Screen =
      Screen.full(
        text(name),
        onEnter = () => { trace += s"enter $name"; enter() },
        onLeave = () => { trace += s"leave $name"; leave() },
      )

  test("the same app re-enters retained screens after onStart and before rendering its next run"):
    val trace = ListBuffer.empty[String]
    final class App extends TuiApp:
      private var active                            = false
      private val page: Screen                      = Screen.full(
        text(s"resource-active:$active"),
        onEnter = () => { trace += "enter"; active = true },
        onLeave = () => { trace += "leave"; active = false },
      )
      override def onStart(): Unit                  =
        trace += "start"
        if screenDepthNow == 0 then pushScreen(page)
        trace += "started"
      override def onStop(): Unit                   = trace += "stop"
      def view(using ReactiveScope, Theme): Element = text("base")
      def depth: Int                                = screenDepthNow
    val app = App()
    (1 to 2).foreach { _ =>
      val terminal = backend()
      assert(app.runWith(terminal) == Right(()))
      val frame    = terminal.lastDrawn.get
      val line     = (0 until frame.area.width).map(x => frame.get(x, 0).symbol).mkString.trim
      assert(line == "resource-active:true")
      assert(app.depth == 1, "cleanup must retain navigation state")
      assert(!terminal.isRawMode)
    }
    assert(
      trace.toList == List("start", "enter", "started", "leave", "stop", "start", "started", "enter", "leave", "stop")
    )

  test("reactivation skips retained entries removed by an earlier onEnter"):
    val trace = ListBuffer.empty[String]
    final class App extends TuiApp:
      private var starts                            = 0
      private val outer: Screen                     = Screen.full(
        text("outer"),
        onEnter = () =>
          trace += "enter outer"
          if starts == 2 then resetScreens()
        ,
        onLeave = () => trace += "leave outer",
      )
      private val inner: Screen                     = Screen.full(
        text("inner"),
        onEnter = () => trace += "enter inner",
        onLeave = () => trace += "leave inner",
      )
      override def onStart(): Unit                  =
        starts += 1
        if starts == 1 then
          pushScreen(outer)
          pushScreen(inner)
      def view(using ReactiveScope, Theme): Element = text("base")
      def depth: Int                                = screenDepthNow
    val app = App()
    assert(app.runWith(backend()) == Right(()))
    trace.clear()
    assert(app.runWith(backend()) == Right(()))
    assert(app.depth == 0)
    assert(trace.toList == List("enter outer", "leave outer"))

  test("repeated screen identities remain independent entries and startup pushes enter only once"):
    val app    = TracedApp()
    val shared = app.screen("shared")
    app.starting = () => { app.open(shared); app.open(shared) }
    assert(app.runWith(backend()) == Right(()))
    app.trace.clear()
    app.starting = () => app.open(app.screen("new"))
    assert(app.runWith(backend()) == Right(()))
    assert(app.depth == 3)
    assert(
      app.trace.toList == List(
        "start",
        "enter new",
        "started",
        "enter shared",
        "enter shared",
        "leave new",
        "leave shared",
        "leave shared",
        "stop",
      )
    )

  for operation <- List("pop", "replace", "reset") do
    test(s"startup $operation changes retained navigation without leaving inactive entries again"):
      val app      = TracedApp()
      app.starting = () => { app.open(app.screen("outer")); app.open(app.screen("inner")) }
      assert(app.runWith(backend()) == Right(()))
      app.trace.clear()
      app.starting = () =>
        operation match
          case "pop"     => app.back()
          case "replace" => app.swap(app.screen("new"))
          case _         => app.home()
      assert(app.runWith(backend()) == Right(()))
      val expected = operation match
        case "pop"     => List("start", "started", "enter outer", "leave outer", "stop")
        case "replace" => List("start", "enter new", "started", "enter outer", "leave new", "leave outer", "stop")
        case _         => List("start", "started", "stop")
      assert(app.trace.toList == expected)

  test("cleanup navigation cannot double-leave an entry or activate screens for a stopped run"):
    val app       = TracedApp()
    val fromLeave = app.screen("from leave")
    val fromStop  = app.screen("from stop")
    val inner     = app.screen("inner", leave = () => { app.home(); app.open(fromLeave) })
    app.starting = () => { app.open(app.screen("outer")); app.open(inner) }
    app.stopping = () => { app.back(); app.open(fromStop) }
    assert(app.runWith(backend()) == Right(()))
    assert(
      app.trace.toList == List("start", "enter outer", "enter inner", "started", "leave inner", "leave outer", "stop")
    )
    assert(app.depth == 1)
    app.trace.clear()
    app.starting = () => ()
    app.stopping = () => ()
    assert(app.runWith(backend()) == Right(()))
    assert(app.trace.toList == List("start", "started", "enter from stop", "leave from stop", "stop"))

  test("cleanup failures retire all entries and preparation failure does not leave them again"):
    val app           = TracedApp()
    val innerFailure  = IllegalStateException("inner cleanup")
    val outerFailure  = IllegalStateException("outer cleanup")
    val stopFailure   = IllegalStateException("app cleanup")
    val preparation   = IllegalStateException("preparation")
    val closeFailure  = IllegalStateException("backend cleanup")
    var failCleanup   = true
    app.starting = () =>
      app.open(app.screen("outer", leave = () => if failCleanup then throw outerFailure))
      app.open(app.screen("inner", leave = () => if failCleanup then throw innerFailure))
    app.stopping = () => if failCleanup then throw stopFailure
    val first         = app.runWith(backend())
    assert(first == Left(RunnerError.Handler(innerFailure)))
    assert(first.left.toOption.get.cleanupFailures == List(outerFailure, stopFailure))
    assert(innerFailure.getSuppressed.toList == List(outerFailure, stopFailure))
    assert(
      app.trace.toList == List("start", "enter outer", "enter inner", "started", "leave inner", "leave outer", "stop")
    )
    app.trace.clear()
    app.prepare = () => throw preparation
    val failedBackend = backend()
    failedBackend.failNext(HeadlessBackend.Op.Close, BackendError.Io(closeFailure))
    assert(intercept[IllegalStateException](app.runWith(failedBackend)) eq preparation)
    assert(app.trace.toList == List("stop"))
    assert(preparation.getSuppressed.toList == List(stopFailure, closeFailure))
    assert(failedBackend.drawCount == 0)
    app.trace.clear()
    failCleanup = false
    app.prepare = () => ()
    app.starting = () => ()
    assert(app.runWith(backend()) == Right(()))
    assert(
      app.trace.toList == List("start", "started", "enter outer", "enter inner", "leave inner", "leave outer", "stop")
    )

  test("preparation cleanup may retain navigation without entering it before onStart"):
    val app         = TracedApp()
    val preparation = IllegalStateException("preparation")
    app.prepare = () => throw preparation
    app.stopping = () => app.open(app.screen("saved"))
    assert(intercept[IllegalStateException](app.runWith(backend())) eq preparation)
    assert(app.trace.toList == List("stop"))
    assert(app.depth == 1)
    app.trace.clear()
    app.prepare = () => ()
    app.stopping = () => ()
    assert(app.runWith(backend()) == Right(()))
    assert(app.trace.toList == List("start", "started", "enter saved", "leave saved", "stop"))

  test("failing onStart leaves only screens that it activated and retains the others for a later run"):
    val app     = TracedApp()
    app.starting = () => app.open(app.screen("retained"))
    assert(app.runWith(backend()) == Right(()))
    app.trace.clear()
    val failure = IllegalStateException("startup")
    app.starting = () => { app.open(app.screen("new")); throw failure }
    assert(app.runWith(backend()) == Left(RunnerError.Handler(failure)))
    assert(app.trace.toList == List("start", "enter new", "leave new", "stop"))
    app.trace.clear()
    app.starting = () => ()
    assert(app.runWith(backend()) == Right(()))
    assert(
      app.trace.toList == List("start", "started", "enter retained", "enter new", "leave new", "leave retained", "stop")
    )

  test("failed reactivation pairs attempted entries but does not leave entries it never reached"):
    val app           = TracedApp()
    val failure       = IllegalStateException("enter")
    var failEnter     = false
    val middle        = app.screen("middle", enter = () => if failEnter then throw failure)
    app.starting = () => { app.open(app.screen("outer")); app.open(middle); app.open(app.screen("inner")) }
    assert(app.runWith(backend()) == Right(()))
    app.trace.clear()
    app.starting = () => ()
    failEnter = true
    val failedBackend = backend()
    assert(app.runWith(failedBackend) == Left(RunnerError.Handler(failure)))
    assert(failedBackend.drawCount == 0)
    assert(
      app.trace.toList == List("start", "started", "enter outer", "enter middle", "leave middle", "leave outer", "stop")
    )
    app.trace.clear()
    failEnter = false
    assert(app.runWith(backend()) == Right(()))
    assert(
      app.trace.toList == List(
        "start",
        "started",
        "enter outer",
        "enter middle",
        "enter inner",
        "leave inner",
        "leave middle",
        "leave outer",
        "stop",
      )
    )

  test("a replacement whose outgoing cleanup fails remains inactive until the next run"):
    val app     = TracedApp()
    val failure = IllegalStateException("leave")
    app.starting = () =>
      app.open(app.screen("old", leave = () => throw failure))
      app.swap(app.screen("new"))
    assert(app.runWith(backend()) == Left(RunnerError.Handler(failure)))
    assert(app.trace.toList == List("start", "enter old", "leave old", "stop"))
    assert(app.depth == 1)
    app.trace.clear()
    app.starting = () => ()
    assert(app.runWith(backend()) == Right(()))
    assert(app.trace.toList == List("start", "started", "enter new", "leave new", "stop"))

  test("navigation outside a run stays inactive through terminal setup failure"):
    val app           = TracedApp()
    app.open(app.screen("discarded"))
    app.home()
    app.open(app.screen("saved"))
    assert(app.trace.isEmpty)
    val failure       = BackendError.Io(IllegalStateException("setup"))
    val failedBackend = backend()
    failedBackend.failNext(HeadlessBackend.Op.EnableRawMode, failure)
    assert(app.runWith(failedBackend) == Left(RunnerError.Backend(failure)))
    assert(app.trace.toList == List("stop"))
    assert(app.depth == 1)
    app.trace.clear()
    assert(app.runWith(backend()) == Right(()))
    assert(app.trace.toList == List("start", "started", "enter saved", "leave saved", "stop"))
    app.trace.clear()
    app.back()
    app.open(app.screen("next"))
    assert(app.trace.isEmpty)
    assert(app.runWith(backend()) == Right(()))
    assert(app.trace.toList == List("start", "started", "enter next", "leave next", "stop"))

  test("a replacement removed by the outgoing cleanup never enters"):
    val app = TracedApp()
    app.starting = () =>
      app.open(app.screen("old", leave = () => app.back()))
      app.swap(app.screen("new"))
    assert(app.runWith(backend()) == Right(()))
    assert(app.depth == 0)
    assert(app.trace.toList == List("start", "enter old", "leave old", "started", "stop"))

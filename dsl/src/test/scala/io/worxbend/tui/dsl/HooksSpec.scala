package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Rect, Size}
import io.worxbend.tui.runtime.{Cancelable, Signal}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}

/** The lifetime hooks — `useEffect`, `useMemo`, `useTask`, `useArea` — driven through a real app, because each one is
  * about what happens *between* frames: after a paint, when a dep moves, when the node disappears.
  */
final class HooksSpec extends AnyFunSuite with PilotFixture:

  /** Counters the effect writes; read through the render thread so the test never races the loop. */
  private final class Counters:
    var starts: Int     = 0
    var cancels: Int    = 0
    var seen: List[Int] = Nil

  test("useEffect starts once, re-runs when a dep changes, and cancels when the node disappears"):
    val backend          = HeadlessBackend(Size(20, 3))
    val counters         = Counters()
    val dep              = Signal(1)
    val shown            = Signal(true)
    val other            = Signal(0)
    val app              = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("d", "bump the dep")(dep.update(_ + 1)),
        binding("h", "hide")(shown.set(false)),
        binding("o", "unrelated redraw")(other.update(_ + 1)),
      )
      def view(using ReactiveScope, Theme): Element =
        val _ = other.get
        if shown.get then
          val current = dep.get
          useEffect(current) {
            counters.starts += 1
            counters.seen = current :: counters.seen
            () => counters.cancels += 1
          }
          text(s"dep=$current")
        else text("gone")
    val pilot            = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    def read: (Int, Int) = pilot.readOnRenderThread((counters.starts, counters.cancels))
    assert(read == (1, 0))
    // a frame with the same dep: no restart
    pilot.press("o").waitForIdle()
    assert(read == (1, 0))
    // the dep moved: the old run is cancelled and a new one starts with the new value
    pilot.press("d").waitForIdle()
    assert(read == (2, 1))
    assert(pilot.readOnRenderThread(counters.seen) == List(2, 1))
    // the node is gone: its effect is cancelled and nothing restarts it
    pilot.press("h").waitForIdle()
    assert(pilot.screenLines.head.startsWith("gone"))
    assert(read == (2, 2))
    pilot.interrupt()
    val _                = pilot.awaitTermination()

  test("an effect still running when the app stops is cancelled"):
    val backend  = HeadlessBackend(Size(20, 3))
    val counters = Counters()
    val app      = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        useEffect() {
          counters.starts += 1
          new Cancelable:
            def cancel(): Unit = counters.cancels += 1
        }
        text("running")
    val pilot    = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.interrupt()
    assert(pilot.awaitTermination())
    assert(counters.starts == 1)
    assert(counters.cancels == 1)

  test("useTask answers Loading, then delivers its result on the render thread and repaints"):
    val backend                              = HeadlessBackend(Size(30, 3))
    val release                              = CountDownLatch(1)
    @volatile var viewThread: Option[Thread] = None
    val app                                  = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        useTask("answer") {
          release.await(5, TimeUnit.SECONDS)
          42
        } match
          case Loadable.Loading       => text("loading")
          case Loadable.Ready(value)  =>
            viewThread = Some(Thread.currentThread())
            text(s"ready:$value")
          case Loadable.Failed(error) => text(s"failed:${error.getMessage}")
    val pilot                                = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("loading"))
    release.countDown()
    pilot.waitUntil("the task's result on screen")(pilot.screenLines.head.startsWith("ready:42"))
    assert(viewThread.contains(pilot.readOnRenderThread(Thread.currentThread())))
    pilot.interrupt()
    val _                                    = pilot.awaitTermination()

  test("useTask reports a failure as Failed"):
    val backend = HeadlessBackend(Size(30, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        useTask()(throw IllegalStateException("boom")) match
          case Loadable.Failed(error) => text(s"failed:${error.getMessage}")
          case other                  => text(other.toString)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitUntil("the failure on screen")(pilot.screenLines.headOption.exists(_.startsWith("failed:boom")))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a useTask whose node disappears before it completes is never delivered"):
    val backend             = HeadlessBackend(Size(30, 3))
    val release             = CountDownLatch(1)
    val finished            = CountDownLatch(1)
    val shown               = Signal(true)
    @volatile var delivered = false
    val app                 = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("h", "hide")(shown.set(false)))
      def view(using ReactiveScope, Theme): Element =
        if shown.get then
          val answer = useTask() {
            try
              release.await(5, TimeUnit.SECONDS)
              7
            finally finished.countDown()
          }
          if answer.toOption.isDefined then delivered = true
          text(answer.toString)
        else text("gone")
    val pilot               = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("h").waitForIdle()
    val draws               = backend.drawCount
    release.countDown()
    assert(finished.await(5, TimeUnit.SECONDS))
    pilot.waitForIdle()
    assert(!delivered)
    assert(backend.drawCount == draws, "a result for a node that is gone asked for a frame")
    assert(pilot.screenLines.head.startsWith("gone"))
    pilot.interrupt()
    val _                   = pilot.awaitTermination()

  test("useArea answers what boundsOf answers for the same node, and settles without input"):
    val backend = HeadlessBackend(Size(30, 6))
    val areas   = scala.collection.concurrent.TrieMap.empty[String, Option[Rect]]
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          text("header"),
          column(each(Seq("a", "b"))(identity) { id =>
            val area = useArea()
            areas(id) = area
            text(s"row $id ${area.fold("?")(rect => s"${rect.width}x${rect.height}")}").length(1)
          }*),
          keyed("k") {
            areas("k") = useArea()
            text("keyed").key("k")
          },
        )
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitUntil("every useArea answered")(Seq("a", "b", "k").forall(id => areas.get(id).exists(_.isDefined)))
    pilot.waitForIdle()
    Seq("a", "b", "k").foreach { id =>
      assert(areas(id) == app.boundsOf(id), s"useArea for $id")
    }
    // the second frame, asked for by the area moving from None, already shows the size
    assert(pilot.screenLines.exists(_.startsWith("row b 30x1")))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("useArea outside any keyed scope answers None"):
    val backend                                  = HeadlessBackend(Size(20, 3))
    @volatile var answered: Option[Option[Rect]] = None
    val app                                      = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        answered = Some(useArea())
        text("root")
    val pilot                                    = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(answered.contains(None))
    pilot.interrupt()
    val _                                        = pilot.awaitTermination()

  test("useMemo recomputes only when its deps change"):
    val store                   = ViewState()
    var builds                  = 0
    def frame(dep: Int): String =
      store.beginGeneration()
      val value = ViewState.during(store)(useMemo(dep) { builds += 1; s"v$dep" })
      store.sweep()
      value
    assert(frame(1) == "v1")
    assert(frame(1) == "v1")
    assert(builds == 1)
    assert(frame(2) == "v2")
    assert(builds == 2)

  test("a TextInputState written on the render thread outside any event shows on the next frame, no signal write"):
    val backend = HeadlessBackend(Size(30, 3))
    val state   = TextInputState()
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = input(state)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    // what `Async.run(fetch())(result => state.insert(result))` does: a render-thread callback, not an event
    pilot.readOnRenderThread(state.insert("from the background"))
    pilot.waitUntil("the background text on screen")(pilot.screenText.contains("from the background"))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a state the widget adjusts while painting does not keep scheduling frames"):
    val backend = HeadlessBackend(Size(20, 3))
    // a selection past the viewport: every paint moves the offset to keep it visible, through the versioned setter
    val state   = ListState(selected = Some(8))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = list((1 to 10).map(n => s"item $n"), state)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val draws   = backend.drawCount
    Thread.sleep(200) // proving an absence: nothing may draw while idle
    pilot.waitForIdle()
    assert(backend.drawCount == draws)
    pilot.readOnRenderThread(state.selected = Some(0))
    pilot.waitUntil("the moved selection on screen")(pilot.screenLines.head.contains("item 1"))
    pilot.interrupt()
    val _ = pilot.awaitTermination()

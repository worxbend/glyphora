package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** `Screen.onEnter` / `Screen.onLeave`: the per-screen counterpart of `TuiApp.onStart` / `onStop`.
  *
  * Every test records the hooks in call order into one buffer, because the ordering is most of the contract — a
  * screen's cleanup has to run before the app's, and a replace has to release before it claims.
  */
final class ScreenLifecycleSpec extends AnyFunSuite with PilotFixture:

  private final class TracedApp extends TuiApp:
    val trace: mutable.Buffer[String] = mutable.Buffer.empty

    /** A screen that records its own hooks under `name`. A fresh one per push, so a screen pushed twice is genuinely
      * two screens and the pairing of the hooks is visible.
      */
    def traced(name: String): Screen =
      Screen.full(
        text(s"screen $name"),
        onEnter = () => trace += s"enter $name",
        onLeave = () => trace += s"leave $name",
      )

    /** One value, pushed twice by the `s` binding below. */
    private val shared: Screen = traced("shared")

    override def onStop(): Unit                   = trace += "app stop"
    override def bindings: KeyBindings            = KeyBindings(
      binding("s", "push the same screen twice") {
        pushScreen(shared)
        pushScreen(shared)
      },
      binding("1", "push a")(pushScreen(traced("a"))),
      binding("2", "push b")(pushScreen(traced("b"))),
      binding("r", "replace with c")(replaceScreen(traced("c"))),
      binding("b", "pop")(popScreen()),
      binding("h", "home")(resetScreens()),
      binding("ctrl+q", "quit")(quit()),
    )
    def view(using ReactiveScope, Theme): Element = text("base")

  private def start(app: TracedApp): Pilot =
    val backend = HeadlessBackend(Size(30, 5))
    startPilot(backend) { app.runWith(backend) }.waitForIdle()

  private def press(pilot: Pilot, c: Char): Unit =
    val _ = pilot.pressKey(KeyCode.Char(c)).waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a pushed screen enters, and leaves again when it is popped"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, '1')
    assert(app.trace.toList == List("enter a"))
    press(pilot, 'b')
    assert(app.trace.toList == List("enter a", "leave a"))
    quitApp(pilot)

  test("popping an empty stack runs nothing"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, 'b')
    assert(app.trace.isEmpty)
    quitApp(pilot)

  /** Release before claim: a resource the outgoing and incoming screens share must be given up before it is taken. */
  test("replaceScreen runs the outgoing screen's leave before the incoming screen's enter"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, '1')
    press(pilot, 'r')
    assert(app.trace.toList == List("enter a", "leave a", "enter c"))
    quitApp(pilot)

  test("resetScreens leaves every screen, innermost first"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, '1')
    press(pilot, '2')
    press(pilot, 'h')
    assert(app.trace.toList == List("enter a", "enter b", "leave b", "leave a"))
    quitApp(pilot)

  /** The exit path is the one that is easy to get wrong: a screen left on the stack when the app quits still owes an
    * `onLeave`, and it has to run before the app's own `onStop` so the screen releases what it holds while the app's
    * resources are still there.
    */
  test("a screen still on the stack when the run ends leaves before the app stops"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, '1')
    press(pilot, '2')
    quitApp(pilot)
    assert(app.trace.toList == List("enter a", "enter b", "leave b", "leave a", "app stop"))

  /** The same screen value pushed twice is two entries on the stack, so it owes two of each hook — which is why the
    * hooks are per push rather than per screen instance.
    */
  test("one screen pushed twice enters twice and leaves twice"):
    val app   = TracedApp()
    val pilot = start(app)
    press(pilot, '1')
    app.trace.clear()
    press(pilot, 's') // pushes the one `shared` value twice, so both entries are the same object
    assert(app.trace.toList == List("enter shared", "enter shared"))
    app.trace.clear()
    press(pilot, 'h')
    assert(app.trace.toList == List("leave shared", "leave shared", "leave a"))
    quitApp(pilot)

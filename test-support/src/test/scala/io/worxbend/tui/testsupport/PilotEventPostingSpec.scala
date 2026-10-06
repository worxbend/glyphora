package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, KeyModifiers, MouseButton, MouseEvent, MouseEventKind, Position, Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, Frame, TerminalRunner}
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Pins the posting verbs that no other suite exercises.
  *
  * The non-key/mouse/resize events used to be undrivable: paste handling, terminal-focus reporting, ticks and
  * interrupts could only be posted by reaching past the harness into `pilot.backend.postEvent(...)` — the raw seam the
  * harness exists to hide — until the verbs existed. The mouse gestures were the opposite problem: `drag` and
  * `mouseMove` are public, documented API that nothing in this repository called, so their Down/Drag/Up sequencing and
  * the Moved-with-no-button convention had no guard at all.
  *
  * Each test below posts through the verb and asserts on what the *application* saw, because a verb that posted the
  * wrong event would still look like it worked from the pilot's side.
  */
final class PilotEventPostingSpec extends AnyFunSuite:

  /** What the app under test observed, in the order it observed it. Written by the app thread, read by the test thread
    * after a `waitForIdle`, which is why it is an atomic and not a plain `var`.
    */
  private final class Observed:
    val ticks: AtomicInteger                   = AtomicInteger(0)
    val interrupts: AtomicInteger              = AtomicInteger(0)
    val pastes: AtomicReference[Seq[String]]   = AtomicReference(Seq.empty)
    val focus: AtomicReference[Seq[Boolean]]   = AtomicReference(Seq.empty)
    val mice: AtomicReference[Seq[MouseEvent]] = AtomicReference(Seq.empty)

    def record[A](holder: AtomicReference[Seq[A]], value: A): Unit =
      val _ = holder.updateAndGet(_ :+ value)

  /** Starts a runner that records what it is sent and paints the tick count, so both the recorded state and the frame
    * can be asserted on. `consumeInterrupt` picks which of the two documented interrupt behaviours the app has: an app
    * answering `Redraw` has consumed the interrupt and stays up, one answering `Ignored` quits through teardown.
    */
  private def start(observed: Observed, consumeInterrupt: Boolean): Pilot =
    Pilot.start(Size(24, 2)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (event, _) => handle(observed, consumeInterrupt, event),
        frame => render(observed, frame),
      )
    }

  private def handle(observed: Observed, consumeInterrupt: Boolean, event: Event): EventOutcome =
    event match
      case Event.Tick         =>
        val _ = observed.ticks.incrementAndGet()
        EventOutcome.Redraw
      case Event.Paste(text)  =>
        observed.record(observed.pastes, text)
        EventOutcome.Redraw
      case Event.FocusGained  =>
        observed.record(observed.focus, true)
        EventOutcome.Redraw
      case Event.FocusLost    =>
        observed.record(observed.focus, false)
        EventOutcome.Redraw
      case Event.Interrupt    =>
        val _ = observed.interrupts.incrementAndGet()
        if consumeInterrupt then EventOutcome.Redraw else EventOutcome.Ignored
      case Event.Mouse(mouse) =>
        observed.record(observed.mice, mouse)
        EventOutcome.Redraw
      case _                  => EventOutcome.Ignored

  private def render(observed: Observed, frame: Frame): Unit =
    frame.renderWidget(
      (area, buffer) => buffer.setString(area.x, area.y, s"ticks ${observed.ticks.get()}", Style.Default),
      frame.area,
    )

  test("paste posts the pasted text as one event, not one key event per character"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.paste("hello world").waitForIdle()
      assert(observed.pastes.get() == Seq("hello world"))
    finally pilot.close()

  test("paste keeps a multi-line, non-ASCII payload whole"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      // a tab and a newline are not key specs and an emoji is two UTF-16 code units: the value of a paste is that none
      // of that is the harness's business, because the terminal hands the string over in one piece
      pilot.paste("a\tb\n日本👍").waitForIdle()
      assert(observed.pastes.get() == Seq("a\tb\n日本👍"))
    finally pilot.close()

  test("tick posts exactly as many ticks as asked for, with no tick rate on the app"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.tick(3).waitForIdle()
      assert(observed.ticks.get() == 3)
      assert(pilot.screenText.contains("ticks 3"))
    finally pilot.close()

  test("tick(0) posts nothing"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.tick(0).waitForIdle()
      assert(observed.ticks.get() == 0)
    finally pilot.close()

  test("focusLost and focusGained arrive as the two halves of the focus report"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.focusLost().focusGained().focusLost().waitForIdle()
      assert(observed.focus.get() == Seq(false, true, false))
    finally pilot.close()

  test("an interrupt an app ignores ends the run"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.waitForIdle().interrupt()
      assert(pilot.awaitTermination())
      assert(observed.interrupts.get() == 1)
    finally pilot.close()

  test("an interrupt an app consumes leaves it running"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = true)
    try
      pilot.interrupt().waitForIdle()
      assert(observed.interrupts.get() == 1)
      assert(pilot.isRunning)
    finally pilot.close()

  test("the new verbs chain like the existing ones"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = true)
    try
      pilot.focusLost().tick(2).paste("x").focusGained().waitForIdle()
      assert(observed.ticks.get() == 2)
      assert(observed.pastes.get() == Seq("x"))
      assert(observed.focus.get() == Seq(false, true))
    finally pilot.close()

  // ------------------------------------------------------------------ the mouse gestures

  /** `drag` and `mouseMove` are public, documented API that nothing in this repository exercised — the drag helpers
    * were the only posting verbs with zero callers — so nothing guarded their conventions: that a drag is exactly Down,
    * Drag, Up in that order, that it runs from the start point to the end point, and that a motion report names no
    * button. Each test drives the verb through the same event-recording app as everything above and asserts on the
    * events the *application* saw.
    */
  test("drag posts Down, Drag and Up in order, from the start point to the end point"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.drag(1, 2, 3, 4).waitForIdle()
      assert(
        observed.mice.get() == Seq(
          MouseEvent(Position(1, 2), MouseEventKind.Down, KeyModifiers.None, MouseButton.Left),
          MouseEvent(Position(3, 4), MouseEventKind.Drag, KeyModifiers.None, MouseButton.Left),
          MouseEvent(Position(3, 4), MouseEventKind.Up, KeyModifiers.None, MouseButton.Left),
        )
      )
    finally pilot.close()

  test("drag names the modifiers and the button it is given"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.drag(Position(0, 0), Position(2, 2), KeyModifiers.Shift, MouseButton.Right).waitForIdle()
      assert(
        observed.mice.get() == Seq(
          MouseEvent(Position(0, 0), MouseEventKind.Down, KeyModifiers.Shift, MouseButton.Right),
          MouseEvent(Position(2, 2), MouseEventKind.Drag, KeyModifiers.Shift, MouseButton.Right),
          MouseEvent(Position(2, 2), MouseEventKind.Up, KeyModifiers.Shift, MouseButton.Right),
        )
      )
    finally pilot.close()

  test("mouseMove posts a Moved with no button, the way a motion report reads"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.mouseMove(5, 6).waitForIdle()
      assert(
        observed.mice.get() ==
          Seq(MouseEvent(Position(5, 6), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown))
      )
    finally pilot.close()

  test("drag and mouseMove chain like the key verbs"):
    val observed = Observed()
    val pilot    = start(observed, consumeInterrupt = false)
    try
      pilot.mouseMove(0, 0).drag(1, 1, 2, 2).mouseMove(3, 3).waitForIdle()
      assert(
        observed.mice.get() == Seq(
          MouseEvent(Position(0, 0), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown),
          MouseEvent(Position(1, 1), MouseEventKind.Down, KeyModifiers.None, MouseButton.Left),
          MouseEvent(Position(2, 2), MouseEventKind.Drag, KeyModifiers.None, MouseButton.Left),
          MouseEvent(Position(2, 2), MouseEventKind.Up, KeyModifiers.None, MouseButton.Left),
          MouseEvent(Position(3, 3), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown),
        )
      )
    finally pilot.close()

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, Support, TerminalCapabilities}

import org.scalatest.funsuite.AnyFunSuite

/** `pixelImage` in a running app on a terminal that speaks the kitty graphics protocol: the picture goes out once, an
  * idle app sends nothing more, and a dialog over it takes it down until the dialog closes.
  */
final class PixelImageElementSpec extends AnyFunSuite with PilotFixture:

  private val Esc     = "\u001b"
  private val picture = RgbaImage.tabulate(2, 2)((x, _) => if x == 0 then 0xffff0000 else 0xff0000ff)

  private def kittyDraws(sequences: Seq[String]): Int   = sequences.count(_.contains(s"${Esc}_Ga=T,f=32,s=2,v=2,"))
  private def kittyDeletes(sequences: Seq[String]): Int = sequences.count(_.contains(s"${Esc}_Ga=d,d=I,"))

  test("drawn once, silent while idle, taken down under a dialog and put back after it"):
    val backend = HeadlessBackend(Size(40, 10))
    backend.capabilitiesTo(TerminalCapabilities(kittyGraphics = Support.Yes))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+d", "ask")(pushScreen(Screen.confirm("Quit", "Sure?")(popScreen(), popScreen()))),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = pixelImage(picture)
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()

    assert(kittyDraws(backend.rawSequences) == 1)
    pilot.tick(3).waitForIdle()
    assert(kittyDraws(backend.rawSequences) == 1 && kittyDeletes(backend.rawSequences) == 0)

    pilot.pressKey(KeyCode.Char('d'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Sure?"))
    assert(kittyDeletes(backend.rawSequences) == 1)

    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!pilot.screenText.contains("Sure?"))
    assert(kittyDraws(backend.rawSequences) == 2)

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("on a terminal with no picture protocol the half-block fallback is what the screen shows"):
    val backend = HeadlessBackend(Size(20, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = pixelImage(picture)
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    assert(backend.rawSequences.isEmpty)
    assert(pilot.screenText.contains("▀"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

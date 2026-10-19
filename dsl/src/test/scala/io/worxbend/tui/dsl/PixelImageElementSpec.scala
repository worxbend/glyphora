package io.worxbend.tui.dsl

import io.worxbend.tui.core.{DiffDirective, KeyCode, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, Support, TerminalCapabilities}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

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

  /** Swaps the encoder for one that waits for `gate` and answers `payload`, recording the thread it ran on. */
  private def gatedEncoder(gate: CountDownLatch, payload: String, ranOn: AtomicReference[Option[Thread]])(
      body: => Unit
  ): Unit =
    val original = PictureEncodings.encode
    PictureEncodings.encode = (_, _) =>
      ranOn.set(Some(Thread.currentThread()))
      val _ = gate.await(10, TimeUnit.SECONDS)
      payload
    try body
    finally PictureEncodings.encode = original

  test("the picture is encoded off the render thread: the half-block fallback shows until the payload is ready"):
    val backend = HeadlessBackend(Size(20, 6))
    backend.capabilitiesTo(TerminalCapabilities(kittyGraphics = Support.Yes))
    val gate    = CountDownLatch(1)
    val ranOn   = AtomicReference(Option.empty[Thread])
    val painter = AtomicReference(Option.empty[Thread])
    gatedEncoder(gate, "UFJF", ranOn) {
      val app   = new TuiApp:
        override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
        def view(using ReactiveScope, Theme): Element =
          painter.set(Some(Thread.currentThread()))
          pixelImage(picture)
      val pilot = startPilot(backend) { app.runWith(backend) }.waitForIdle()
      pilot.waitUntil("the encoder to start")(ranOn.get.isDefined)
      assert(kittyDraws(backend.rawSequences) == 0, "nothing is placed while the payload is being encoded")
      assert(pilot.screenText.contains("▀"), "the fallback is on screen meanwhile")
      assert(ranOn.get != painter.get, "the encode ran on the render thread")
      gate.countDown()
      pilot.waitUntil("the encoded picture to be drawn")(kittyDraws(backend.rawSequences) == 1)
      assert(backend.rawSequences.exists(_.contains(s"${Esc}_Ga=T,f=32,s=2,v=2,i=1,")))
      assert(backend.rawSequences.exists(_.contains(s";UFJF$Esc\\")), "the payload the encoder made is what is sent")
      pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
      assert(pilot.awaitTermination())
    }

  test("a picture the scroll view's edge cuts is drawn cropped to the visible rows, not as the fallback"):
    val backend = HeadlessBackend(Size(5, 2))
    backend.capabilitiesTo(TerminalCapabilities(kittyGraphics = Support.Yes))
    val tall    = RgbaImage.tabulate(4, 6)((_, _) => 0xff00ff00)
    val scroll  = ScrollViewState()
    val gate    = CountDownLatch(0)
    gatedEncoder(gate, "VEFMTA==", AtomicReference(None)) {
      val app      = new TuiApp:
        override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
        def view(using ReactiveScope, Theme): Element =
          scrollView(column(text("top"), pixelImage(tall, PixelImageOptions(fit = ImageFit.Stretch)).length(3)), scroll)
      val pilot    = startPilot(backend) { app.runWith(backend) }.waitForIdle()
      // box 4x3 cells over 4x6 pixels; the window shows its first row, so the first two pixel rows
      val expected =
        s"${Esc}[2;1H${Esc}_Ga=T,f=32,s=4,v=6,x=0,y=0,w=4,h=2,i=1,c=4,r=1,C=1,q=2;VEFMTA==$Esc\\"
      pilot.waitUntil("the cropped picture to be drawn")(backend.rawSequences.exists(_.contains(expected)))
      assert(backend.rawSequences.size == 1)
      assert(
        backend.lastDrawn.exists(frame => (0 until 4).forall(x => frame.diffDirective(x, 1) == DiffDirective.Skip)),
        "the picture's cells are reserved for it rather than flushed as half-blocks",
      )
      pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
      assert(pilot.awaitTermination())
    }

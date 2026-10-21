package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, DiffDirective, GraphicPayload, Position, Rect, RgbaImage, Size, Style}

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, IOException, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8

/** Pictures across frames: drawn once when they appear, left alone while nothing changes, taken down while something
  * covers them and put back when it goes — captured on the headless backend, which runs the same graphics layer the
  * JLine backend does, and checked once on the wire.
  */
final class GraphicsLayerSpec extends AnyFunSuite:

  private val Esc   = "\u001b"
  private val image = RgbaImage.tabulate(2, 2)((_, _) => 0xff102030)
  private val area  = Rect(1, 1, 3, 2)

  /** The opening of the kitty command that draws `image` as image `id` into `area` — written out by hand. */
  private def kittyDisplay(id: Int) = s"${Esc}_Ga=T,f=32,s=2,v=2,i=$id,c=3,r=2,C=1,q=2;"

  private def frame(covered: Boolean = false, placed: Boolean = true): Buffer =
    val buffer = Buffer(Rect(0, 0, 10, 4))
    buffer.setString(area.x, area.y, "fal", Style.Default)
    if placed then buffer.placeGraphic(area, image)
    if covered then buffer.setString(2, 1, "popup", Style.Default)
    buffer

  private def kittyBackend(): HeadlessBackend =
    val backend = HeadlessBackend(Size(10, 4))
    backend.capabilitiesTo(TerminalCapabilities(kittyGraphics = Support.Yes))
    backend

  test("frame 1 draws the picture once, at its box, and frame 2 unchanged writes nothing"):
    val backend = kittyBackend()
    assert(backend.draw(frame()).isRight)
    val first   = backend.rawSequences
    assert(first.size == 1)
    assert(first.head.contains(AnsiSequences.moveTo(1, 1) + kittyDisplay(1)))
    assert(first.head.startsWith(AnsiSequences.SaveCursor) && first.head.endsWith(AnsiSequences.RestoreCursor))
    assert(backend.draw(frame()).isRight)
    assert(backend.rawSequences == first)

  test("a popup covering the picture deletes it, and closing the popup draws it again"):
    val backend = kittyBackend()
    assert(backend.draw(frame()).isRight)
    assert(backend.draw(frame(covered = true)).isRight)
    assert(backend.rawSequences.last == s"${Esc}_Ga=d,d=I,i=1,q=2$Esc\\")
    // while covered, the cells flush as text so the popup shows
    assert(backend.lastDrawn.exists(_.diffDirective(2, 1) == DiffDirective.Default))
    assert(backend.draw(frame()).isRight)
    assert(backend.rawSequences.size == 3)
    assert(backend.rawSequences.last.contains(kittyDisplay(2)))

  test("a picture that stops being placed is deleted"):
    val backend = kittyBackend()
    assert(backend.draw(frame()).isRight)
    assert(backend.draw(frame(placed = false)).isRight)
    assert(backend.rawSequences.last == s"${Esc}_Ga=d,d=I,i=1,q=2$Esc\\")

  test("with no protocol nothing is written and the fallback cells flush as text"):
    val backend = HeadlessBackend(Size(10, 4))
    assert(backend.draw(frame()).isRight)
    assert(backend.rawSequences.isEmpty)
    assert(backend.lastDrawn.exists(_.diffDirective(1, 1) == DiffDirective.Default))

  test("iTerm2 has no delete: a covered picture is erased by handing its cells back to the diff"):
    val backend = HeadlessBackend(Size(10, 4))
    backend.capabilitiesTo(TerminalCapabilities(inlineImages = Support.Yes))
    assert(backend.draw(frame()).isRight)
    assert(backend.rawSequences.single.contains(s"$Esc]1337;File=inline=1;"))
    assert(backend.draw(frame(covered = true)).isRight)
    assert(backend.rawSequences.size == 1)
    assert(backend.lastDrawn.exists(_.diffDirective(1, 1) == DiffDirective.Default))
    assert(backend.draw(frame()).isRight)
    assert(backend.rawSequences.size == 2)

  test("on the wire the picture follows the frame body inside the same write"):
    val sink     = ByteArrayOutputStream()
    val terminal = LineDisciplineTerminal("graphics", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, Map("TERM" -> "xterm-256color"))
    try
      backend.capabilitiesOverride(TerminalCapabilities(kittyGraphics = Support.Yes))
      sink.reset()
      assert(backend.draw(frame()).isRight)
      val written = sink.toString(UTF_8)
      assert(written.contains(kittyDisplay(1)))
      // the reserved cells are not flushed as text over the picture
      assert(!written.contains("fal"))
      sink.reset()
      assert(backend.draw(frame()).isRight)
      assert(sink.toString(UTF_8).isEmpty)
    finally
      val _ = backend.close()

  /** Throws before handing a batch to the real writer, without setting PrintWriter's permanent error latch. */
  private final class RecoverableWriter(sink: ByteArrayOutputStream) extends PrintWriter(sink, false, UTF_8):
    var failing                                = false
    var failAfterWrite                         = false
    override def write(sequence: String): Unit =
      if failing then throw new IOException("recoverable batch failure")
      super.write(sequence)
      if failAfterWrite then
        super.flush()
        throw new IOException("batch delivered but acknowledgement failed")

  private def recovering(body: (JLine3Backend, RecoverableWriter, ByteArrayOutputStream) => Unit): Unit =
    val sink        = ByteArrayOutputStream()
    val batchWriter = RecoverableWriter(sink)
    val terminal    =
      new LineDisciplineTerminal("graphics-retry", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN):
        override def writer(): PrintWriter = batchWriter
    val backend     = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, Map.empty)
    try
      backend.capabilitiesOverride(TerminalCapabilities(kittyGraphics = Support.Yes))
      body(backend, batchWriter, sink)
    finally
      batchWriter.failing = false
      val _ = backend.close()

  test("ordinary graphics update retries both the old delete and new image after a recoverable write failure"):
    recovering { (backend, writer, sink) =>
      assert(backend.draw(frame()).isRight)
      val changed     = frame(placed = false)
      val replacement = RgbaImage.tabulate(2, 2)((_, _) => 0xffabcdef)
      changed.placeGraphic(area, replacement)
      sink.reset()
      writer.failing = true
      assert(backend.draw(changed).isLeft)
      assert(sink.toString(UTF_8).isEmpty)
      writer.failing = false
      assert(!writer.checkError(), "this seam recovers; a real PrintWriter device error does not")
      assert(backend.draw(changed).isRight)
      val retry       = sink.toString(UTF_8)
      assert(retry.contains(KittyGraphics.delete(1)), "the old image still needs deleting")
      assert(retry.contains(KittyGraphics.payload(replacement)), "the failed new image must be transmitted again")
      assert(retry.indexOf(KittyGraphics.delete(1)) < retry.indexOf(KittyGraphics.payload(replacement)))
      sink.reset()
      assert(backend.draw(changed).isRight)
      assert(sink.toString(UTF_8).isEmpty)
    }

  test("a failed image removal retries its delete after an established baseline"):
    recovering { (backend, writer, sink) =>
      assert(backend.draw(frame()).isRight)
      val removed = frame(placed = false)
      writer.failing = true
      assert(backend.draw(removed).isLeft)
      writer.failing = false
      sink.reset()
      assert(backend.draw(removed).isRight)
      assert(sink.toString(UTF_8).contains(KittyGraphics.delete(1)))
    }

  test("failed forgetAll before hardware scroll retains the deletion obligation"):
    recovering { (backend, writer, sink) =>
      assert(backend.draw(frame()).isRight)
      writer.failing = true
      assert(backend.scrollRegionUp(RowRange(0, 3), 1).isLeft)
      writer.failing = false
      sink.reset()
      assert(backend.scrollRegionUp(RowRange(0, 3), 1).isRight)
      assert(sink.toString(UTF_8).startsWith(KittyGraphics.delete(1)))
      sink.reset()
      assert(backend.draw(frame()).isRight)
      assert(sink.toString(UTF_8).contains(KittyGraphics.payload(image)))
    }

  test("an unacknowledged delivered image is deleted even when the next frame removes it"):
    recovering { (backend, writer, sink) =>
      assert(backend.draw(frame()).isRight)
      val changed     = frame(placed = false)
      val replacement = RgbaImage.tabulate(2, 2)((_, _) => 0xffabcdef)
      changed.placeGraphic(area, replacement)
      writer.failAfterWrite = true
      assert(backend.draw(changed).isLeft)
      writer.failAfterWrite = false
      sink.reset()
      assert(backend.draw(frame(placed = false)).isRight)
      val retry       = sink.toString(UTF_8)
      assert(retry.contains(KittyGraphics.delete(1)))
      assert(retry.contains(KittyGraphics.delete(2)), "a failed flush can have delivered the newly allocated image")
    }

  test("failed repaint after clear resize or suspend retries the image and all deletion obligations"):
    for disturbance <- Seq("clear", "resize", "suspend") do
      recovering { (backend, writer, sink) =>
        assert(backend.draw(frame()).isRight)
        val next  = if disturbance == "resize" then
          val widened = Buffer(Rect(0, 0, 12, 4))
          widened.placeGraphic(area, image)
          widened
        else frame()
        if disturbance == "clear" then assert(backend.clearRegion(ClearType.All).isRight)
        if disturbance == "suspend" then assert(backend.suspend(()).isRight)
        writer.failing = true
        assert(backend.draw(next).isLeft)
        writer.failing = false
        sink.reset()
        assert(backend.draw(next).isRight)
        val retry = sink.toString(UTF_8)
        assert(retry.contains(KittyGraphics.delete(1)), disturbance)
        assert(retry.contains(KittyGraphics.payload(image)), disturbance)
        sink.reset()
        assert(backend.draw(next).isRight)
        assert(sink.toString(UTF_8).isEmpty, disturbance)
      }

  test("a failed clear that may have reached the terminal invalidates the graphics baseline"):
    recovering { (backend, writer, sink) =>
      assert(backend.draw(frame()).isRight)
      writer.failAfterWrite = true
      assert(backend.clearRegion(ClearType.All).isLeft)
      writer.failAfterWrite = false
      sink.reset()
      assert(backend.draw(frame()).isRight)
      assert(sink.toString(UTF_8).contains(KittyGraphics.payload(image)))
    }

  // ------------------------------------------------------------------ cropped pictures

  /** A 4x6-pixel picture whose every pixel is distinct, so a crop can be checked pixel for pixel. */
  private def tall(x: Int, y: Int): Int = 0xff000000 | (x * 40) << 16 | (y * 40) << 8 | 0x7f
  private val tallImage                 = RgbaImage.tabulate(4, 6)(tall)

  /** What a scroll view does: the picture's 2x3-cell box sits in content rows 0..2 and the window copies rows 1..3, so
    * the top row of the box is cut and the frame holds the bottom two thirds of it at screen row 0.
    */
  private def scrolledFrame(payload: Option[GraphicPayload] = None): Buffer =
    val content = Buffer(Rect(0, 0, 10, 6))
    content.setString(1, 0, "ab", Style.Default)
    content.setString(1, 1, "cd", Style.Default)
    content.setString(1, 2, "ef", Style.Default)
    content.placeGraphic(Rect(1, 0, 2, 3), tallImage, payload)
    val screen  = Buffer(Rect(0, 0, 10, 4))
    screen.blit(content, Position(0, 0), Rect(0, 1, 10, 4))
    screen

  test("kitty draws a clipped picture as a source rectangle of the whole image, not as the fallback"):
    val backend  = kittyBackend()
    assert(backend.draw(scrolledFrame()).isRight)
    val expected =
      s"${AnsiSequences.moveTo(1, 0)}${Esc}_Ga=T,f=32,s=4,v=6,x=0,y=2,w=4,h=4,i=1,c=2,r=2,C=1,q=2;" +
        KittyGraphics.payload(tallImage) + s"$Esc\\"
    assert(backend.rawSequences.single.contains(expected))
    assert(backend.lastDrawn.exists(_.diffDirective(1, 0) == DiffDirective.Skip))

  test("iTerm2 draws a clipped picture by re-encoding just the visible region"):
    val backend = HeadlessBackend(Size(10, 4))
    backend.capabilitiesTo(TerminalCapabilities(inlineImages = Support.Yes))
    assert(backend.draw(scrolledFrame()).isRight)
    val visible = RgbaImage.tabulate(4, 4)((x, y) => tall(x, y + 2))
    assert(backend.rawSequences.single.contains(AnsiSequences.moveTo(1, 0) + ITerm2Images.display(visible, 2, 2)))

  test("a placement that brings its own payload for the protocol is sent as given, not re-encoded"):
    val backend = kittyBackend()
    val frame   = Buffer(Rect(0, 0, 10, 4))
    frame.placeGraphic(area, image, Some(GraphicPayload(ImageProtocol.Kitty.tag, "UFJF")))
    assert(backend.draw(frame).isRight)
    assert(backend.rawSequences.single.contains(kittyDisplay(1) + s"UFJF$Esc\\"))
    val other   = Buffer(Rect(0, 0, 10, 4))
    other.placeGraphic(area, image, Some(GraphicPayload(ImageProtocol.ITerm2.tag, "UFJF")))
    val again   = kittyBackend()
    assert(again.draw(other).isRight)
    assert(again.rawSequences.single.contains(kittyDisplay(1) + KittyGraphics.payload(image)))

  extension (sequences: Seq[String])
    private def single: String =
      assert(sequences.size == 1, sequences)
      sequences.head

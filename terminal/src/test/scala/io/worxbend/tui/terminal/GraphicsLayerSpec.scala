package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, DiffDirective, Rect, RgbaImage, Size, Style}

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
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

  extension (sequences: Seq[String])
    private def single: String =
      assert(sequences.size == 1, sequences)
      sequences.head

package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Rect, Style}

import org.jline.terminal.{Attributes, Terminal}
import org.jline.terminal.impl.LineDisciplineTerminal

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Exercise IOException below JLine's real PrintWriter, which swallows exceptions and latches checkError instead. */
final class JLineOutputFailureSpec extends AnyFunSuite:

  private final class Device extends OutputStream:
    private val sink  = ByteArrayOutputStream()
    var failing       = false
    var failures      = 0
    var failOnNewline = false

    override def write(value: Int): Unit =
      if failing || (failOnNewline && value == '\n') then
        failures += 1
        throw new IOException("device disconnected")
      else sink.write(value)

    def written: String = sink.toString(UTF_8)
    def forget(): Unit  = sink.reset()

  test("real device failure reports teardown error without clearing cursor ownership or skipping cooked mode"):
    val device             = Device()
    var closing            = false
    var attributesRestored = false
    val terminal           = new LineDisciplineTerminal(
      "glyphora-device-failure",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    ):
      override def setAttributes(attributes: Attributes): Unit =
        super.setAttributes(attributes)
        if closing && attributes.getLocalFlag(Attributes.LocalFlag.ICANON) then attributesRestored = true
    val backend            = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.enterAlternateScreen().isRight)
      assert(backend.hideCursor().isRight)
      assert(backend.enableMouseCapture().isRight)
      assert(backend.setCursorShape(CursorShape.SteadyBar).isRight)
      assert(backend.setCursorBlink(false).isRight)
      assert(backend.setTitle("owned").isRight)
      device.forget()
      closing = true
      device.failing = true
      val result = backend.close()
      assert(result match
        case Left(BackendError.Io(_: IOException)) => true
        case _                                     => false)
      assert(device.written.isEmpty)
      assert(backend.cursorShape == CursorShape.SteadyBar)
      assert(backend.cursorBlinkSuppressed)
      assert(backend.cursorHidden)
      assert(backend.mouseCaptureActive.nonEmpty)
      assert(backend.alternateScreenActive)
      assert(attributesRestored, "ANSI failure must not skip restoring the shell's line discipline")
      intercept[IllegalStateException](terminal.writer()) // all undress failures must still close the JLine handle
      assert(device.failures > 1, "later releases must still attempt device output")
    finally terminal.close()

  test("real device failure rejects a shape request without acquiring restoration ownership"):
    val device   = Device()
    val terminal = LineDisciplineTerminal(
      "glyphora-shape-device-failure",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    )
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      device.failing = true
      assert(backend.setCursorShape(CursorShape.SteadyBar).isLeft)
      assert(backend.cursorShape == CursorShape.Default)
      assert(backend.setCursorBlink(false).isLeft)
      assert(!backend.cursorBlinkSuppressed)
    finally terminal.close()

  test("real device failure is surfaced by frame writes rather than committing a false baseline"):
    val device   = Device()
    val terminal = LineDisciplineTerminal(
      "glyphora-frame-device-failure",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    )
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    val buffer   = Buffer(Rect(0, 0, 2, 1))
    buffer.setString(0, 0, "ok", Style.Default)
    try
      device.failing = true
      assert(backend.draw(buffer).isLeft)
      assert(backend.draw(buffer).isLeft, "a failed first draw must not make the identical retry a no-op")
    finally terminal.close()

  test("successful inline close parks once and remains harmless when repeated"):
    val device   = Device()
    val terminal = LineDisciplineTerminal(
      "glyphora-inline-repeat-close",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    )
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.reserveInlineRows(1).isRight)
      device.forget()
      assert(backend.suspend(()) == Right(()))
      val parked = device.written
      assert(parked.endsWith("\r\n"))
      device.forget()
      assert(backend.close().isRight)
      assert(device.written == parked)
      device.forget()
      assert(backend.close().isRight)
      assert(device.written.isEmpty)
    finally terminal.close()

  test("an inline parking write failure is reported even when no terminal mode is owned"):
    val device   = Device()
    val terminal = LineDisciplineTerminal(
      "glyphora-inline-device-failure",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    )
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.reserveInlineRows(1).isRight)
      device.failing = true
      assert(backend.close().isLeft)
    finally terminal.close()

  test("inline parking failure after mode restoration still closes the handle and retains the parking obligation"):
    val device   = Device()
    val terminal = LineDisciplineTerminal(
      "glyphora-inline-final-parking-failure",
      "xterm-256color",
      device,
      UTF_8,
      Terminal.SignalHandler.SIG_IGN,
    )
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor)
    try
      assert(backend.enableRawMode().isRight)
      assert(backend.reserveInlineRows(2).isRight)
      assert(backend.hideCursor().isRight)
      device.forget()
      device.failOnNewline = true
      assert(backend.close() match
        case Left(BackendError.Io(_: IOException)) => true
        case _                                     => false)
      assert(device.written.contains(AnsiSequences.RestoreCursor))
      assert(!backend.isRawMode, "parking failure must occur after cooked-mode restoration")
      assert(!backend.cursorHidden)
      assert(backend.inlineRows == 2, "failed parking must not be accounted as successful")
      intercept[IllegalStateException](terminal.writer())
    finally terminal.close()

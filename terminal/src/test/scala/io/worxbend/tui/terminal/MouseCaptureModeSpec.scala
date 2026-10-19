package io.worxbend.tui.terminal

import io.worxbend.tui.core.{
  Event,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  MouseButton,
  MouseEvent,
  MouseEventKind,
  Position,
  Size,
}

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.terminal.ScriptedInput.{csi, decoder}

/** Hover — pointer motion with no button held — end to end: the request the backend sends, and the report the decoder
  * turns back into a [[MouseEventKind.Moved]].
  *
  * `Moved` was in the event vocabulary from the start but nothing could produce it, because the backend only ever asked
  * for button-event tracking (DEC modes 1000 and 1002), under which a terminal reports motion solely while a button is
  * down. Both halves of that are covered here: that `MouseCaptureMode.AllMotion` really does add mode 1003, and that a
  * motion report naming no button really does decode to `Moved` rather than to a phantom `Drag`.
  */
final class MouseCaptureModeSpec extends AnyFunSuite:

  private val Esc = ''

  private def decoded(chars: Int*): Event =
    decoder(chars*).decode(10).getOrElse(fail("expected an event"))

  test("only all-motion capture asks the terminal for mode 1003"):
    assert(!AnsiSequences.enableMouseCapture(MouseCaptureMode.Buttons).contains(s"$Esc[?1003h"))
    assert(AnsiSequences.enableMouseCapture(MouseCaptureMode.AllMotion).contains(s"$Esc[?1003h"))
    // buttons-only is exactly what the mode-less constant has always been, so nothing changes for existing callers
    assert(AnsiSequences.enableMouseCapture(MouseCaptureMode.Buttons) == AnsiSequences.EnableMouseCapture)

  test("disabling capture resets mode 1003 even when it was never set"):
    // a DEC private-mode reset is a no-op for a mode that is already off, and sending it unconditionally means no
    // teardown path can leave all-motion tracking flooding the user's shell after the app exits
    assert(AnsiSequences.DisableMouseCapture.contains(s"$Esc[?1003l"))

  test("the headless backend remembers which mode was requested"):
    val backend = HeadlessBackend(Size(10, 3))
    assert(backend.mouseCaptureMode.isEmpty)
    assert(!backend.isMouseCaptured)

    assert(backend.enableMouseCapture(MouseCaptureMode.AllMotion).isRight)
    assert(backend.mouseCaptureMode.contains(MouseCaptureMode.AllMotion))
    assert(backend.isMouseCaptured)

    // the no-argument form is buttons-only, which is what every caller written before this existed meant
    assert(backend.enableMouseCapture().isRight)
    assert(backend.mouseCaptureMode.contains(MouseCaptureMode.Buttons))

    assert(backend.disableMouseCapture().isRight)
    assert(backend.mouseCaptureMode.isEmpty)

    // `Off` through the mode-taking form is a request to stop, the same as disabling
    assert(backend.enableMouseCapture(MouseCaptureMode.Buttons).isRight)
    assert(backend.enableMouseCapture(MouseCaptureMode.Off).isRight)
    assert(!backend.isMouseCaptured)
    assert(AnsiSequences.enableMouseCapture(MouseCaptureMode.Off) == AnsiSequences.DisableMouseCapture)

  test("an SGR motion report with no button held decodes as Moved, not Drag"):
    // 32 is the motion bit; +3 in the low two bits means "no button", which is a hover
    assert(
      decoded(csi("<35;11;6M")*) ==
        Event.Mouse(MouseEvent(Position(10, 5), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown))
    )

  test("an SGR motion report naming a held button is still a Drag"):
    // 32 + 0: motion with the left button down
    assert(
      decoded(csi("<32;11;6M")*) == Event.Mouse(MouseEvent(Position(10, 5), MouseEventKind.Drag, KeyModifiers.None))
    )

  test("modifiers survive a hover report"):
    // 35 + 4 sets the shift bit, which sits two positions above the button bits
    assert(
      decoded(csi("<39;11;6M")*) ==
        Event.Mouse(MouseEvent(Position(10, 5), MouseEventKind.Moved, KeyModifiers.Shift, MouseButton.Unknown))
    )

  test("a legacy X10 hover report decodes as Moved too"):
    // X10 biases every byte by 32: button 35 (motion, no button), column 11, row 6
    val report = csi("M") ++ Seq(32 + 35, 32 + 11, 32 + 6)
    assert(
      decoded(report*) ==
        Event.Mouse(MouseEvent(Position(10, 5), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown))
    )

  test("a hover at the very top-left corner is not clamped away"):
    // the smallest coordinates the protocol can express, one-based 1;1, are the zero-based origin
    assert(
      decoded(csi("<35;1;1M")*) ==
        Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown))
    )

  test("urxvt reporting is requested before SGR and released after it"):
    // both encodings lift X10's column ceiling; SGR is the better one because it names which button was released, so
    // a terminal that understands both must end up in SGR. Enabling 1015 first and disabling it last is what does it
    val enable  = AnsiSequences.EnableMouseCapture
    val disable = AnsiSequences.DisableMouseCapture
    assert(enable.indexOf(s"$Esc[?1015h") < enable.indexOf(s"$Esc[?1006h"))
    assert(disable.indexOf(s"$Esc[?1006l") < disable.indexOf(s"$Esc[?1015l"))
    // all-motion tracking gets the same encoding request, not a different one
    assert(AnsiSequences.EnableMouseAllMotion.contains(s"$Esc[?1015h"))

  test("pixel reporting is deliberately never requested"):
    // mode 1016 reports coordinates in pixels, and every layer above the backend addresses cells
    assert(!AnsiSequences.EnableMouseCapture.contains("1016"))
    assert(!AnsiSequences.EnableMouseAllMotion.contains("1016"))

  test("a urxvt press and release decode to Down and Up"):
    assert(
      decoded(csi("32;120;30M")*) == Event.Mouse(MouseEvent(Position(119, 29), MouseEventKind.Down, KeyModifiers.None))
    )
    // 35 is 32 + 3, and 3 in the button bits means "some button came up" — urxvt has no per-button release code
    assert(
      decoded(csi("35;120;30M")*) ==
        Event.Mouse(MouseEvent(Position(119, 29), MouseEventKind.Up, KeyModifiers.None, MouseButton.Unknown))
    )

  test("a urxvt report reaches columns the legacy encoding cannot express"):
    // this is the entire reason mode 1015 is requested: X10 writes each coordinate as one biased byte and cannot
    // name a column past 223, while this form writes them as decimal text
    assert(
      decoded(csi("32;300;40M")*) == Event.Mouse(MouseEvent(Position(299, 39), MouseEventKind.Down, KeyModifiers.None))
    )

  test("urxvt drag, hover and wheel share the SGR decoding rules"):
    assert(decoded(csi("64;10;5M")*) == Event.Mouse(MouseEvent(Position(9, 4), MouseEventKind.Drag, KeyModifiers.None)))
    assert(
      decoded(csi("67;10;5M")*) ==
        Event.Mouse(MouseEvent(Position(9, 4), MouseEventKind.Moved, KeyModifiers.None, MouseButton.Unknown))
    )
    assert(
      decoded(csi("96;10;5M")*) ==
        Event.Mouse(MouseEvent(Position(9, 4), MouseEventKind.ScrollUp, KeyModifiers.None, MouseButton.Unknown))
    )
    assert(
      decoded(csi("97;10;5M")*) ==
        Event.Mouse(MouseEvent(Position(9, 4), MouseEventKind.ScrollDown, KeyModifiers.None, MouseButton.Unknown))
    )

  test("modifier bits survive a urxvt report"):
    // 32 + 16 sets the ctrl bit, which sits two positions above the button bits
    assert(decoded(csi("48;10;5M")*) == Event.Mouse(MouseEvent(Position(9, 4), MouseEventKind.Down, KeyModifiers.Ctrl)))

  test("a three-parameter sequence that is not a mouse report is not read as a click"):
    // every real report carries the +32 bias, so a smaller first parameter cannot be one — without that guard an
    // unrelated CSI sequence ending in M would arrive as a phantom click somewhere on screen
    val input = decoder((csi("1;2;3M") ++ Seq('q'.toInt))*)
    // the sequence is swallowed whole and produces nothing, and the keystroke behind it still arrives intact —
    // a sequence read as a click would have surfaced as a mouse event here instead
    assert(input.decode(10).isEmpty)
    assert(input.decode(10) == Some(Event.Key(KeyEvent(KeyCode.Char('q'), KeyModifiers.None))))

  test("the SGR form is still decoded as SGR, not stolen by the urxvt branch"):
    assert(
      decoded(csi("<0;120;30M")*) == Event.Mouse(MouseEvent(Position(119, 29), MouseEventKind.Down, KeyModifiers.None))
    )
    assert(
      decoded(csi("<0;120;30m")*) == Event.Mouse(MouseEvent(Position(119, 29), MouseEventKind.Up, KeyModifiers.None))
    )

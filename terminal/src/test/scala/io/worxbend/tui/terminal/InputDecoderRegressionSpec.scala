package io.worxbend.tui.terminal

import io.worxbend.tui.core.{
  Event,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  MediaKey,
  ModifierKey,
  MouseButton,
  MouseEvent,
  MouseEventKind,
  Position,
}

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.terminal.ScriptedInput.{Esc, NothingAvailable, csi, decoder}

import java.io.InterruptedIOException

import scala.collection.mutable.ArrayBuffer

/** Regressions for input-decoding defects found by terminal-level audit.
  *
  * The common thread: a sequence the decoder does not understand must be *dropped*, never reported as a keypress.
  * Synthesizing an `Escape` from unparsed bytes means a capability probe or a mouse click silently closes whatever
  * dialog the user had open.
  */
final class InputDecoderRegressionSpec extends AnyFunSuite:

  private def decoded(chars: Int*): Event =
    decoder(chars*).decode(10).getOrElse(fail("expected an event"))

  private def dropped(chars: Int*): Unit =
    assert(decoder(chars*).decode(10).isEmpty)

  /** What a scripted stream produces over `calls` successive `decode` calls, `None` entries included.
    *
    * A dropped sequence is half of what these regressions assert — "the torn sequence produced nothing *and* the one
    * after it decoded correctly" is only checkable by looking at both results in order.
    */
  private def decodedAll(calls: Int)(chars: Int*): List[Option[Event]] =
    val input = decoder(chars*)
    List.fill(calls)(input.decode(10))

  /** The two vocabularies in one assertion: what an application writes as a key spec, and what the terminal actually
    * sends for that key. `KeyEvent.parse` lives in `tui-core`, so this side of the contract is now checkable from here
    * rather than only describable in a comment.
    */
  private def assertSpecMatches(spec: String, chars: Int*): Unit =
    KeyEvent.parse(spec) match
      case Right(event)  =>
        assert(decoded(chars*) == Event.Key(event), s"spec '$spec' does not name what these bytes send")
      case Left(problem) => fail(s"spec '$spec' did not parse: $problem")

  test("SS3 application-mode cursor keys decode to the arrows"):
    // what a terminal sends once DECCKM is on (e.g. tmux with xterm-keys); previously a stray Escape
    assert(decoded(Esc, 'O', 'A') == Event.Key(KeyEvent.of(KeyCode.Up)))
    assert(decoded(Esc, 'O', 'B') == Event.Key(KeyEvent.of(KeyCode.Down)))
    assert(decoded(Esc, 'O', 'C') == Event.Key(KeyEvent.of(KeyCode.Right)))
    assert(decoded(Esc, 'O', 'D') == Event.Key(KeyEvent.of(KeyCode.Left)))

  test("terminal replies are dropped instead of surfacing as an Escape keypress"):
    dropped(csi("?62;1;4c")*) // primary device attributes
    dropped(csi("?1$p")*)     // DECRPM mode report
    dropped(csi(">0;95;0c")*) // secondary device attributes
    dropped(csi("24;80R")*) // cursor position report

  test("an unknown CSI final byte produces no event"):
    dropped(csi("1;2W")*)

  test("a torn sequence that times out mid-flight produces no event"):
    dropped(Esc, '[')
    dropped(csi("1;5")*)

  /** An `ESC` always starts a sequence, so one arriving mid-sequence aborts whatever was being read. The decoder used
    * to keep consuming instead, which meant the *next* sequence was eaten as the torn one's parameters and its
    * remaining bytes were delivered as ordinary characters — a `q` fires the quit binding, a `3` fires whatever is
    * bound to it.
    */
  test("an ESC arriving mid-sequence aborts it and still decodes the sequence it opened"):
    // rxvt-unicode sends exactly these bytes for Alt+Up; the decoder used to report Escape, then '[', then 'A'
    assert(
      decodedAll(2)(Esc, Esc, '['.toInt, 'A'.toInt) ==
        List(Some(Event.Key(KeyEvent.of(KeyCode.Escape))), Some(Event.Key(KeyEvent.of(KeyCode.Up))))
    )
    // a CSI torn off mid-parameter: the 'A' used to arrive as a literal character
    assert(decodedAll(2)((csi("1") ++ csi("A"))*) == List(None, Some(Event.Key(KeyEvent.of(KeyCode.Up)))))
    // torn after a separator: the '1' and the '~' used to arrive as literal characters
    assert(decodedAll(2)((csi("1;") ++ csi("1~"))*) == List(None, Some(Event.Key(KeyEvent.of(KeyCode.Home)))))

  test("a truncated terminal reply does not leak the sequence that interrupted it as keystrokes"):
    // an OSC with no `ESC \` terminator: the '[' and 'A' used to be dispatched as text into the focused widget
    val bytes = Seq(Esc, ']'.toInt) ++ "11;rgb".map(_.toInt) ++ csi("A")
    assert(decodedAll(2)(bytes*) == List(Some(Event.Key(KeyEvent.of(KeyCode.Up))), None))

  test("a truncated terminal reply that simply stops is still not reported as an Escape"):
    dropped((Seq(Esc, ']'.toInt) ++ "11;rgb".map(_.toInt) :+ Esc)*)

  /** Handing the second `ESC` back is what makes the case above work, and it also means two genuine Escape presses
    * report two Escapes rather than one. That is the intended behaviour; this pins it.
    */
  test("a lone ESC reports one Escape and two ESCs report two"):
    val escape = Some(Event.Key(KeyEvent.of(KeyCode.Escape)))
    assert(decodedAll(2)(Esc) == List(escape, None))
    assert(decodedAll(3)(Esc, Esc) == List(escape, escape, None))

  test("a legacy X10 mouse report decodes as a mouse event, not as keystrokes"):
    // a terminal that ignores the SGR-1006 request keeps sending X10; those bytes used to be injected as text
    val report = Seq(Esc, '['.toInt, 'M'.toInt, 32 + 0, 32 + 33, 32 + 33)
    assert(decoded(report*) == Event.Mouse(MouseEvent(Position(32, 32), MouseEventKind.Down, KeyModifiers.None)))

  test("an X10 button-3 report is a release that cannot name its button"):
    // X10 has one release code for every button, so the identity is genuinely gone rather than guessable
    val report = Seq(Esc, '['.toInt, 'M'.toInt, 32 + 3, 32 + 5, 32 + 5)
    assert(
      decoded(report*) ==
        Event.Mouse(MouseEvent(Position(4, 4), MouseEventKind.Up, KeyModifiers.None, MouseButton.Unknown))
    )

  test("an X10 press still names its button"):
    val rightPress = Seq(Esc, '['.toInt, 'M'.toInt, 32 + 2, 32 + 5, 32 + 5)
    assert(
      decoded(rightPress*) ==
        Event.Mouse(MouseEvent(Position(4, 4), MouseEventKind.Down, KeyModifiers.None, MouseButton.Right))
    )

  test("a truncated X10 report is dropped rather than half-decoded"):
    dropped(Esc, '['.toInt, 'M'.toInt, 32)

  /** The reader UTF-8-decodes the stream, so an X10 coordinate byte at or above 0x80 never survives as a byte: it
    * arrives as U+FFFD (65533). Read as a coordinate that used to place the click at `Position(65500, 9)` — off every
    * widget in the layout, so the click silently hit nothing.
    */
  test("an X10 coordinate that arrived as a replacement character is dropped, not read as a position"):
    dropped(Esc, '['.toInt, 'M'.toInt, 32 + 0, 0xfffd, 32 + 10)
    dropped(Esc, '['.toInt, 'M'.toInt, 32 + 0, 32 + 10, 0xfffd)

  test("a paste containing an escape sequence keeps its payload and still finds the terminator"):
    // the terminator's own ESC-[ used to be eaten by a five-byte lookahead that could not push back
    val payload = "a[Ab"
    val bytes   = csi("200~") ++ payload.map(_.toInt) ++ csi("201~")
    assert(decoded(bytes*) == Event.Paste(payload))

  test("a paste whose text merely mentions the terminator is not truncated"):
    val payload = "see [201~ in the docs"
    val bytes   = csi("200~") ++ payload.map(_.toInt) ++ csi("201~")
    assert(decoded(bytes*) == Event.Paste(payload))

  test("a paste ending exactly at the terminator keeps every byte before it"):
    val payload = "abc"
    val bytes   = csi("200~") ++ payload.map(_.toInt) ++ csi("201~")
    assert(decoded(bytes*) == Event.Paste("abc"))

  test("an astral character arrives as one key carrying its code point"):
    // UTF-16 surrogates must be recombined; two lone halves corrupt any string they land in
    assert(decoded("😀".map(_.toInt)*) == Event.Key(KeyEvent(KeyCode.Char(0x1f600), KeyModifiers.None)))

  test("kitty reports an astral code point directly"):
    assert(decoded(csi("128512u")*) == Event.Key(KeyEvent(KeyCode.Char(0x1f600), KeyModifiers.None)))

  test("kitty functional keys map onto named keys rather than private-use glyphs"):
    assert(decoded(csi("57399u")*) == Event.Key(KeyEvent.of(KeyCode.Char('0')))) // KP_0
    assert(decoded(csi("57414u")*) == Event.Key(KeyEvent.of(KeyCode.Enter)))     // KP_ENTER
    assert(decoded(csi("57417u")*) == Event.Key(KeyEvent.of(KeyCode.Left)))      // KP_LEFT
    assert(decoded(csi("57423u")*) == Event.Key(KeyEvent.of(KeyCode.Home)))      // KP_HOME
    assert(decoded(csi("57376u")*) == Event.Key(KeyEvent.of(KeyCode.F(13))))     // F13
    assert(decoded(csi("57398u")*) == Event.Key(KeyEvent.of(KeyCode.F(35)))) // F35

  test("an unassigned kitty functional code point is not reported as a key"):
    dropped(csi("57344u")*) // an unassigned code point at the foot of the functional block

  test("a kitty lock or system key is reported, and reports the press rather than the resulting state"):
    // these used to be dropped along with the modifier-only keys. Menu and Pause in particular are keys applications
    // genuinely bind, and a lock key press is an ordinary key event — what glyphora still does not claim to know is
    // whether the lock is now on.
    assert(decoded(csi("57358u")*) == Event.Key(KeyEvent.of(KeyCode.CapsLock)))
    assert(decoded(csi("57363u")*) == Event.Key(KeyEvent.of(KeyCode.Menu)))

  test("kitty reports a bare modifier press as its own key"):
    assert(decoded(csi("57441u")*) == Event.Key(KeyEvent.of(KeyCode.Modifier(ModifierKey.LeftShift))))
    assert(decoded(csi("57449u")*) == Event.Key(KeyEvent.of(KeyCode.Modifier(ModifierKey.RightAlt))))
    assert(decoded(csi("57454u")*) == Event.Key(KeyEvent.of(KeyCode.Modifier(ModifierKey.IsoLevel5Shift))))

  test("a bare modifier press still carries the modifiers held with it"):
    // Pressing Shift while Ctrl is already down: the key is Shift, and Ctrl is in the modifier set.
    assert(
      decoded(csi("57441;5u")*) == Event.Key(KeyEvent(KeyCode.Modifier(ModifierKey.LeftShift), KeyModifiers.Ctrl))
    )

  test("the code points bounding the modifier block are decoded as themselves"):
    // 57440 is MUTE_VOLUME, the last of the media block directly below the modifier-only block; 57455 is the first
    // unassigned code point above it. Asserting both is what catches an off-by-one in either transcribed table.
    assert(decoded(csi("57440u")*) == Event.Key(KeyEvent.of(KeyCode.Media(MediaKey.MuteVolume))))
    dropped(csi("57455u")*)

  test("kitty modifier parameters follow the 1 + bitmask encoding"):
    assert(decoded(csi("97;5u")*) == Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl)))
    // 97;2u is the unshifted 'a' plus a Shift bit, which folds onto the legacy uppercase encoding
    assert(decoded(csi("97;2u")*) == Event.Key(KeyEvent(KeyCode.Char('A'), KeyModifiers.None)))
    // caps-lock (64) and num-lock (128) bits are reported but carry no glyphora modifier
    assert(decoded(csi("97;65u")*) == Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.None)))

  /** A key spec names one key, so the two protocols must agree on what that key *is*.
    *
    * Kitty reports a shifted letter as the unshifted base key plus a Shift bit; a legacy terminal sends the uppercase
    * character with no Shift bit. Before the decoder folded them together, `binding("alt+A", …)` fired under xterm and
    * never under kitty, while `binding("alt+shift+a", …)` did the exact reverse.
    */
  test("a shifted letter decodes the same under the kitty protocol as it does on a legacy terminal"):
    assert(decoded(csi("97;4u")*) == decoded(Esc, 'A')) // Alt+Shift+A
    assert(decoded(csi("97;4u")*) == Event.Key(KeyEvent(KeyCode.Char('A'), KeyModifiers.Alt)))
    assert(decoded(csi("97;2u")*) == decoded('A'.toInt)) // Shift+A, no other modifier

  /** Ctrl+Shift+letter is the same agreement seen from the other side: a legacy terminal has one control byte for both
    * Ctrl+S and Ctrl+Shift+S, so case cannot survive there and must not survive under kitty either — otherwise the
    * `"ctrl+s"` spec stops matching as soon as the user's caps-lock is on.
    */
  test("kitty Ctrl+Shift+letter collapses onto the legacy control byte"):
    assert(decoded(csi("115;6u")*) == decoded(0x13))
    assert(decoded(csi("115;6u")*) == Event.Key(KeyEvent(KeyCode.Char('s'), KeyModifiers.Ctrl)))

  /** A character with no uppercase form keeps its Shift bit: folding it would discard the only evidence that the key
    * was shifted, and rebuilding the shifted glyph needs a keyboard layout the decoder does not have.
    */
  test("a shifted key with no uppercase form is reported unchanged"):
    assert(decoded(csi("50;2u")*) == Event.Key(KeyEvent(KeyCode.Char('2'), KeyModifiers.Shift)))

  test("the escape timeout is configurable"):
    val impatient = InputDecoder(_ => -2, escapeTimeoutMillis = 5L)
    assert(impatient.decode(1).isEmpty)

  /** `wake()` interrupts the render thread through JLine's reader, which answers a blocked read with an
    * `InterruptedIOException` — including a read parked *inside* a sequence. The scan used to read that as a tear and
    * drop the sequence, and the bytes still buffered then arrived as literal keys (`5`, `A`). An interrupt says nothing
    * about the bytes in flight, so the consumed prefix is re-queued instead and the next decode re-parses the sequence
    * whole: Ctrl+Up arrives as Ctrl+Up.
    */
  test("an interrupt landing mid-sequence re-queues the prefix and the next decode reports the whole key"):
    // Ctrl+Up arrives as `ESC[1;5A`; the interrupt lands between the parameters and the final byte
    val tape  = WakingTape(Seq(Esc, '['.toInt, '1'.toInt, ';'.toInt, '5'.toInt, 'A'.toInt), interruptAt = Set(5))
    val input = InputDecoder(tape.read)
    assert(input.decode(10).isEmpty) // the wake: answered as "no event", not thrown — and nothing consumed is lost
    assert(input.decode(10).contains(Event.Key(KeyEvent(KeyCode.Up, KeyModifiers.Ctrl))))
    assert(input.decode(10).isEmpty) // and the interrupt was not mistaken for end of input

  test("a runaway parameter string is abandoned instead of looping forever"):
    dropped(csi("1" * 500)*)

  test("an ESC-prefixed control byte decodes to the named key, not to a raw control character"):
    // the spec an app would declare has to name what the bytes actually deliver, or the binding never fires
    assertSpecMatches("alt+backspace", Esc, 0x7f)
    assert(decoded(Esc, 0x7f) == Event.Key(KeyEvent(KeyCode.Backspace, KeyModifiers.Alt)))
    assert(decoded(Esc, 0x08) == Event.Key(KeyEvent(KeyCode.Backspace, KeyModifiers.Alt)))
    assert(decoded(Esc, 0x0d) == Event.Key(KeyEvent(KeyCode.Enter, KeyModifiers.Alt)))
    assert(decoded(Esc, 0x0a) == Event.Key(KeyEvent(KeyCode.Enter, KeyModifiers.Alt)))
    assert(decoded(Esc, 0x09) == Event.Key(KeyEvent(KeyCode.Tab, KeyModifiers.Alt)))

  test("ESC plus a control code decodes to Ctrl+Alt+letter"):
    assert(decoded(Esc, 1) == Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl | KeyModifiers.Alt)))

  test("the legacy and kitty encodings of the same key agree"):
    // the kitty path already reported these correctly, so a binding used to work on one terminal and not the other
    assert(decoded(Esc, 0x7f) == decoded(csi("127;3u")*))
    assert(decoded(Esc, 0x0d) == decoded(csi("13;3u")*))
    assert(decoded(Esc, 0x09) == decoded(csi("9;3u")*))
    assert(decoded(Esc, 1) == decoded(csi("97;7u")*))

  test("control bytes outside the letter range still carry Ctrl"):
    // a bare Char(0) with no modifier would be inserted as text instead of firing the declared binding
    assertSpecMatches("ctrl+space", 0x00)
    assertSpecMatches("ctrl+\\", 0x1c)
    assert(decoded(0x00) == Event.Key(KeyEvent(KeyCode.Char(' '), KeyModifiers.Ctrl)))
    assert(decoded(0x1c) == Event.Key(KeyEvent(KeyCode.Char('\\'), KeyModifiers.Ctrl)))
    assert(decoded(0x1d) == Event.Key(KeyEvent(KeyCode.Char(']'), KeyModifiers.Ctrl)))
    assert(decoded(0x1e) == Event.Key(KeyEvent(KeyCode.Char('^'), KeyModifiers.Ctrl)))
    assert(decoded(0x1f) == Event.Key(KeyEvent(KeyCode.Char('_'), KeyModifiers.Ctrl)))

  test("a modified F3 decodes like the other modified function keys"):
    assert(decoded(csi("1;5R")*) == Event.Key(KeyEvent(KeyCode.F(3), KeyModifiers.Ctrl)))
    assert(decoded(csi("1;2R")*) == Event.Key(KeyEvent(KeyCode.F(3), KeyModifiers.Shift)))
    assert(decoded(csi("1;5P")*) == Event.Key(KeyEvent(KeyCode.F(1), KeyModifiers.Ctrl)))

  test("a cursor-position report is still not mistaken for F3"):
    dropped(csi("24;80R")*)
    dropped(csi("1;80R")*) // row 1, column 80: the column is not a plausible modifier code

  test("a horizontal wheel report is never reported as vertical scrolling"):
    // xterm buttons 66/67 are wheel-left/right; reporting them as ScrollUp/Down scrolls a list on every sideways swipe.
    // They used to be dropped for that reason; now they have kinds of their own, and the rule they were protecting
    // still holds: neither of them may come back as a vertical scroll.
    assert(
      decoded(csi("<66;1;1M")*) ==
        Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.ScrollLeft, KeyModifiers.None, MouseButton.Unknown))
    )
    assert(
      decoded(csi("<67;1;1M")*) ==
        Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.ScrollRight, KeyModifiers.None, MouseButton.Unknown))
    )
    // the vertical wheel still works
    assert(
      decoded(csi("<64;1;1M")*) ==
        Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.ScrollUp, KeyModifiers.None, MouseButton.Unknown))
    )
    assert(
      decoded(csi("<65;1;1M")*) ==
        Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.ScrollDown, KeyModifiers.None, MouseButton.Unknown))
    )

  test("an unpaired surrogate is dropped rather than delivered as half a character"):
    // a lone surrogate corrupts every string it is appended to, which is what `printable` exists to prevent
    assert(decoder(0xd83d, 'a'.toInt).decode(10).isEmpty)
    dropped(csi("55357u")*) // kitty reporting a high surrogate as a code point

  test("a high surrogate not followed by a low one does not swallow the next key"):
    val input  = decoder(0xd83d, 'a'.toInt)
    val events = List(input.decode(10), input.decode(10)).flatten
    assert(events == List(Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.None))))

  test("kitty super/hyper/meta keys are dropped rather than delivered unmodified"):
    // Super+q delivered as a bare `q` fires the quit binding
    dropped(csi("97;9u")*)  // super
    dropped(csi("97;17u")*) // hyper
    dropped(csi("97;33u")*) // meta
    dropped(csi("1;9C")*) // and on the xterm parameter path too

  test("a kitty key release is told apart from the press, so no binding fires twice"):
    // This assertion used to be its own inverse, guarding a latent defect: while the decoder ignored the event-type
    // sub-parameter, `:3` — a key *release* — decoded to the same press as the key going down, so the moment flag 2
    // was requested every binding would have fired twice per keystroke. The decoder now reads the sub-parameter, and
    // the two must differ. See KeyReleaseSpec for the rest of the vocabulary.
    assert(decoded(csi("97;5:3u")*) != decoded(csi("97;5u")*)) // :3 is a key release
    assert(decoded(csi("97;5:3u")*) == Event.KeyRelease(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl)))
    // :2 is auto-repeat and stays a press on purpose — a held key on a legacy terminal produces exactly that
    assert(decoded(csi("97;5:2u")*) == decoded(csi("97;5u")*))

  /** A modified key whose first parameter is omitted used to lose its modifier: the empty field was skipped, the
    * modifier code slid into the key's own slot, and xterm's Ctrl+Up — written `CSI ;5A`, the omitted parameter
    * defaulting to 1 — reached the application as a plain Up.
    */
  test("a modified key with an omitted first parameter keeps its modifier"):
    assert(decoded(csi(";5A")*) == Event.Key(KeyEvent(KeyCode.Up, KeyModifiers.Ctrl)))
    assert(decoded(csi(";5B")*) == Event.Key(KeyEvent(KeyCode.Down, KeyModifiers.Ctrl)))
    assert(decoded(csi(";2D")*) == Event.Key(KeyEvent(KeyCode.Left, KeyModifiers.Shift)))
    // the explicit spelling names the same key
    assert(decoded(csi(";5A")*) == decoded(csi("1;5A")*))

  /** The defaulting is positional, not a license to invent parameters: a sequence that carries none at all must keep
    * naming nothing, or `CSI ~` starts decoding as Home.
    */
  test("a parameter-less sequence names nothing rather than defaulting its parameters"):
    dropped(csi("~")*)
    dropped(csi("u")*)

  // ---------------------------------------------------------------- wakes inside a sequence (R2-01)

  /** A scripted reader that a wake interrupts: `script` characters are handed out one per read, and a read whose
    * 1-based index is in `interruptAt` throws `InterruptedIOException` *instead of* answering — the byte it was waiting
    * on stays on the tape, exactly as JLine leaves a buffered character in place when `wake()` interrupts the parked
    * read. Past the end of the tape, a quiet terminal: `NothingAvailable`, never an exception. `asked` records the
    * timeout each read was given.
    */
  private final class WakingTape(script: Seq[Int], interruptAt: Set[Int]):
    private var tape      = script
    private var reads     = 0
    val asked             = ArrayBuffer.empty[Long]
    val read: Long => Int = timeout =>
      reads += 1
      asked += timeout
      if interruptAt.contains(reads) then throw new InterruptedIOException("wake")
      else if tape.isEmpty then NothingAvailable
      else
        val c = tape.head
        tape = tape.tail
        c

  private val escape = Some(Event.Key(KeyEvent.of(KeyCode.Escape)))

  /** What `decode` answers over `calls` successive calls against a tape a wake interrupts at the named reads. */
  private def wakingDecodes(calls: Int, interruptAt: Set[Int])(chars: Int*): List[Option[Event]] =
    val input = InputDecoder(WakingTape(chars, interruptAt).read)
    List.fill(calls)(input.decode(10))

  test("a wake while waiting for the byte after ESC reports Escape on the next decode"):
    // the wake lands in the escape window; the `ESC` already read used to be gone with it, and the keypress with it
    assert(wakingDecodes(3, interruptAt = Set(2))(Esc) == List(None, escape, None))

  test("ESC, wake, x reports Alt+x"):
    // a binding on a bare `x` — a quit key, say — used to fire instead of the `alt+x` the user pressed
    assert(
      wakingDecodes(2, interruptAt = Set(2))(Esc, 'x'.toInt) ==
        List(None, Some(Event.Key(KeyEvent(KeyCode.Char('x'), KeyModifiers.Alt))))
    )

  test("a wake between the halves of a surrogate pair still reports the code point"):
    assert(
      wakingDecodes(2, interruptAt = Set(2))(0xd83d, 0xde00) ==
        List(None, Some(Event.Key(KeyEvent(KeyCode.Char(0x1f600), KeyModifiers.None))))
    )

  test("ESC, wake, high surrogate, wake, low surrogate reports Alt + the code point"):
    // the second wake lands with two characters consumed — `ESC` and the high surrogate — and both must come back, in
    // order, so the Alt modifier survives alongside the recombined character
    assert(
      wakingDecodes(3, interruptAt = Set(2, 4))(Esc, 0xd83d, 0xde00) ==
        List(None, None, Some(Event.Key(KeyEvent(KeyCode.Char(0x1f600), KeyModifiers.Alt))))
    )

  test("a wake inside a skipped OSC never dispatches its tail, and the key after the terminator decodes normally"):
    // an OSC 11 background-colour reply; the wake lands five bytes in. The skip used to abort with the interrupt, and
    // the rest of the reply — `q` included — was typed into whatever had focus, with the BEL arriving as Ctrl+G
    val reply = Seq(Esc, ']'.toInt) ++ "11;rgb:0000/0000/0000q".map(_.toInt) :+ 7
    val got   = wakingDecodes(4, interruptAt = Set(6))((reply :+ 'k'.toInt)*)
    assert(got == List(None, None, Some(Event.Key(KeyEvent.char('k'))), None))

  test("a wake inside SS3 (ESC O, wake, A) reports Up"):
    assert(
      wakingDecodes(2, interruptAt = Set(3))(Esc, 'O'.toInt, 'A'.toInt) == List(
        None,
        Some(Event.Key(KeyEvent.of(KeyCode.Up))),
      )
    )

  test("a wake inside an X10 mouse report reports the click"):
    val report = Seq(Esc, '['.toInt, 'M'.toInt, 32 + 0, 32 + 33, 32 + 33)
    assert(
      wakingDecodes(2, interruptAt = Set(5))(report*) ==
        List(None, Some(Event.Mouse(MouseEvent(Position(32, 32), MouseEventKind.Down, KeyModifiers.None))))
    )

  /** The escape window must not restart on every resumption, or an app whose wakes arrive closer together than the
    * window — a 60 fps ticker — would re-queue a bare Escape forever. The resumed read gets what was left of the
    * interrupted one's budget, and once that is spent the Escape is reported without asking the reader again.
    */
  test("a bare ESC interrupted by wakes every 10 ms is reported as Escape once the 50 ms window has elapsed"):
    var now     = 0L
    var reads   = 0
    val asked   = ArrayBuffer.empty[Long]
    val input   = InputDecoder(
      read = timeout =>
        reads += 1
        if reads == 1 then Esc
        else
          asked += timeout
          now += 10L * 1_000_000L // the wake lands 10 ms into the wait
          throw new InterruptedIOException("wake")
      ,
      nanoTime = () => now,
    )
    val results = List.fill(6)(input.decode(10))
    assert(results.take(5).forall(_.isEmpty), results.toString)
    assert(results(5) == escape, results.toString)
    assert(asked.toList == List(50L, 40L, 30L, 20L, 10L)) // each resumption asks only for what is left of the window
    assert(reads == 6) // the sixth decode reported Escape without a further read: the budget was spent

  test("a wake inside a control string does not reset its length cap"):
    // 4300 payload bytes and no terminator; the wake lands after 4000 of them. The skip gives up at 4096 consumed
    // bytes — as it does without a wake — so exactly the 204 bytes past the cap surface as keys, not 300 and not none
    val payload = Seq.fill(4300)('a'.toInt)
    val input   = InputDecoder(WakingTape(Seq(Esc, ']'.toInt) ++ payload, interruptAt = Set(4003)).read)
    val keys    = List.fill(4500)(input.decode(10)).collect { case Some(Event.Key(key)) => key }
    assert(keys.size == 4300 - 4096, s"${keys.size} keys surfaced")
    assert(keys.forall(_ == KeyEvent.char('a')))

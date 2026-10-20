package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, KeyModifiers}

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.terminal.ScriptedInput.{csi, decoder}

/** The kitty keyboard protocol's event-type sub-parameter: what glyphora asks for, and what it makes of the answer. */
final class KeyReleaseSpec extends AnyFunSuite:

  private def decoded(body: String): Option[Event] = decoder(csi(body)*).decode(10)

  // ------------------------------------------------------------------ the request

  test("the default push asks for disambiguation only, and the opt-in push adds event types"):
    assert(AnsiSequences.PushKittyKeyboard == "[>1u")
    assert(AnsiSequences.PushKittyKeyboardEvents == "[>3u")

  /** The flags are a bitmask, so the opt-in push has to be a superset of the default one rather than a replacement:
    * asking for releases must not switch disambiguation back off.
    */
  test("the opt-in push keeps every flag the default push asked for"):
    assert((AnsiSequences.KittyDisambiguate | AnsiSequences.KittyReportEventTypes) == 3)
    assert(AnsiSequences.pushKittyKeyboard(AnsiSequences.KittyDisambiguate) == AnsiSequences.PushKittyKeyboard)

  /** One owned push needs one pop, not a blind reset: after normal close an extra pop belongs to the shell. */
  test("the unconditional teardown string never pops an unowned keyboard mode"):
    assert(!AnsiSequences.RestoreAll.contains(AnsiSequences.PopKittyKeyboard))

  // ------------------------------------------------------------------ the reply

  test("a release sub-parameter produces a release event, and nothing else does"):
    assert(decoded("97;5:3u").contains(Event.KeyRelease(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl))))
    assert(decoded("97;5:1u").contains(Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl))))
    assert(decoded("97;5u").contains(Event.Key(KeyEvent(KeyCode.Char('a'), KeyModifiers.Ctrl))))

  /** Auto-repeat is deliberately an ordinary press: a held key on a legacy terminal already arrives as a stream of
    * presses, so reporting it otherwise would make held-arrow scrolling depend on which terminal the app started in.
    */
  test("an auto-repeat is reported as the press it is indistinguishable from elsewhere"):
    assert(decoded("97;5:2u") == decoded("97;5u"))

  test("an unknown event type falls back to a press rather than being dropped"):
    assert(decoded("97;5:9u") == decoded("97;5u"))
    assert(decoded("97;5:u") == decoded("97;5u"))

  /** The sub-parameter must not shift the positional parameters after it: the modifier is still the second *parameter*,
    * not the second colon-separated field.
    */
  test("a sub-parameter changes the kind and nothing else about the key"):
    val released = decoded("97;5:3u")
    val pressed  = decoded("97;5u")
    assert(released.collect { case Event.KeyRelease(key) => key } == pressed.collect { case Event.Key(key) => key })

  test("a functional key reports its release too"):
    assert(decoded("57376;1:3u").contains(Event.KeyRelease(KeyEvent.of(KeyCode.F(13)))))

  /** Shift folding happens before the kind is decided, so `"A"` names the key on both the way down and the way up. */
  test("the shifted-key folding applies to a release exactly as it does to a press"):
    assert(decoded("97;2:3u").contains(Event.KeyRelease(KeyEvent.of(KeyCode.Char('A')))))

  // ------------------------------------------------------------------ the backend seam

  test("a backend that cannot report event types answers success and emits no releases"):
    assert(HeadlessBackend(io.worxbend.tui.core.Size(10, 3)).enableKeyEventTypes() == Right(()))

  /** A terminal that has been asked for event types reports them on *every* key it sends, not only on the ones that use
    * the kitty `u` form. Arrows, function keys and the navigation keys keep their legacy shapes and carry the event
    * type in the same sub-parameter, so a release on those forms must decode as a release too — otherwise an app that
    * opted in sees two presses per arrow keystroke and a list scrolls two rows at a time.
    */
  test("a release on a legacy CSI form is a release, not a second press"):
    assert(decoded("3;1:3~").contains(Event.KeyRelease(KeyEvent.of(KeyCode.Delete))))
    assert(decoded("1;1:3B").contains(Event.KeyRelease(KeyEvent.of(KeyCode.Down))))
    assert(decoded("1;1:3P").contains(Event.KeyRelease(KeyEvent.of(KeyCode.F(1)))))

  test("a legacy CSI form with no event type is still a press"):
    assert(decoded("3;1~").contains(Event.Key(KeyEvent.of(KeyCode.Delete))))
    assert(decoded("1;2B").contains(Event.Key(KeyEvent(KeyCode.Down, KeyModifiers.Shift))))

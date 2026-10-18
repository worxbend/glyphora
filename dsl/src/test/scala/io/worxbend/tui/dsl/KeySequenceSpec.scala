package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers}

import org.scalatest.funsuite.AnyFunSuite

/** The `KeySequence` spec vocabulary and the collision rule `KeyBindings` enforces over it. Dispatch through a running
  * app — the pending prefix, its timeout, the fall-back to single keys — is `KeySequenceRoutingSpec`'s.
  */
final class KeySequenceSpec extends AnyFunSuite:

  private val ctrlK = KeyEvent(KeyCode.Char('k'), KeyModifiers.Ctrl)
  private val ctrlS = KeyEvent(KeyCode.Char('s'), KeyModifiers.Ctrl)

  test("a space-separated spec parses into one step per key"):
    val parsed = KeySequence.parse("ctrl+k ctrl+s").toOption.get
    assert(parsed.steps == Vector(KeyStep.Key(ctrlK), KeyStep.Key(ctrlS)))
    assert(parsed.spelling == "ctrl+k ctrl+s")

  test("a single-key spec is the same key KeyEvent.parse names, padding rules included"):
    assert(KeySequence.parse("ctrl+s").toOption.get.steps == Vector(KeyStep.Key(ctrlS)))
    assert(KeySequence.parse(" ").toOption.get.steps == Vector(KeyStep.Key(KeyEvent.char(' '))))

  test("<leader> is a step resolved against the bindings' leader key"):
    val parsed = KeySequence.parse("<leader> f").toOption.get
    assert(parsed.steps == Vector(KeyStep.Leader, KeyStep.Key(KeyEvent.char('f'))))
    assert(parsed.resolve(KeyEvent.char(',')) == Vector(KeyEvent.char(','), KeyEvent.char('f')))

  test("a malformed step names the step that failed"):
    val problem = KeySequence.parse("g nope").left.toOption.get
    assert(problem.contains("nope"))

  test("the keyseq literal checks the spec while compiling"):
    assert(keyseq"g g" == KeySequence.parse("g g").toOption.get)
    assert(scala.compiletime.testing.typeCheckErrors("""keyseq"g nope"""").exists(_.message.contains("nope")))

  test("equality ignores how a sequence was spelled"):
    assert(KeySequence.parse("esc").toOption.get == KeySequence.parse("escape").toOption.get)

  test("a prefix that is also a full binding fails at construction, naming both"):
    val error = intercept[IllegalArgumentException](
      KeyBindings(binding("g", "go")(()), binding("g g", "go to top")(()))
    )
    assert(error.getMessage.contains("\"g\""))
    assert(error.getMessage.contains("\"g g\""))
    assert(error.getMessage.contains("go to top"))

  test("a leader binding collides with a plain binding on the leader key"):
    assertThrows[IllegalArgumentException](
      KeyBindings(binding("space", "page down")(()), binding("<leader> f", "find")(()))
    )

  test("a leader of the caller's own resolves the collision"):
    given KeyLeader = KeyLeader(",")
    val moved       = KeyBindings(binding("space", "page down")(()), binding("<leader> f", "find")(()))
    assert(moved.dispatch(Vector(KeyEvent.char(',')), KeyEvent.char('f')) == KeyMatch.Fired)

  test("pure dispatch walks a prefix to its binding and reports a dead end"):
    var fired    = 0
    val bindings = KeyBindings(binding("g g", "top")(fired += 1), binding("x", "cut")(()))
    assert(bindings.dispatch(Vector.empty, KeyEvent.char('g')) == KeyMatch.Pending(Vector(KeyEvent.char('g'))))
    assert(bindings.dispatch(Vector(KeyEvent.char('g')), KeyEvent.char('g')) == KeyMatch.Fired)
    assert(fired == 1)
    assert(bindings.dispatch(Vector(KeyEvent.char('g')), KeyEvent.char('x')) == KeyMatch.NoMatch)
    // a single key still goes through handle
    assert(!bindings.handle(KeyEvent.char('g')))
    assert(bindings.handle(KeyEvent.char('x')))

  test("continuations list the keys that may follow a prefix with their descriptions"):
    val bindings = KeyBindings(
      binding("ctrl+k ctrl+s", "save all")(()),
      binding("ctrl+k ctrl+w", "close all")(()),
      binding("q", "quit")(()),
    )
    assert(bindings.continuations(Vector(ctrlK)) == Seq(("ctrl+s", "save all"), ("ctrl+w", "close all")))

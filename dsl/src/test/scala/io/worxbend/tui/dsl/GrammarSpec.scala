package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Flex, KeyCode}
import io.worxbend.tui.runtime.ReactiveScope

import org.scalatest.funsuite.AnyFunSuite

final class GrammarSpec extends AnyFunSuite:

  test("Key constants and constructors build the expected events"):
    assert(Key.Up == KeyEvent.of(KeyCode.Up))
    assert(Key.ctrl('s') == Key.CtrlS)
    assert(Key.char('a') == KeyEvent.char('a'))
    assert(Key.f(5) == KeyEvent.of(KeyCode.F(5)))

  /** The named keys and the character keys take the same modifiers, so `Ctrl+Left` and `Shift+F5` are spellable in the
    * typed vocabulary rather than only through a key-spec string.
    */
  test("modifier suffixes apply to any key, named or printable"):
    assert(Key.Left.ctrl == KeyEvent(KeyCode.Left, KeyModifiers.Ctrl))
    assert(Key.Enter.alt == KeyEvent(KeyCode.Enter, KeyModifiers.Alt))
    assert(Key.f(5).shift == KeyEvent(KeyCode.F(5), KeyModifiers.Shift))
    assert(Key.shift(Key.Tab) == Key.BackTab)

  /** The `Key` constants and the string spec vocabulary are two names for the same events, and they drift apart
    * silently: a binding written with a spec no parser understands never fires and never complains.
    */
  test("both spec spellings of back-tab name the Key.BackTab constant"):
    assert(KeyEvent.parse("backtab") == Right(Key.BackTab))
    assert(KeyEvent.parse("shift+tab") == Right(Key.BackTab))

  test("modifier suffixes compose and are idempotent"):
    assert(Key.char('p').ctrl.shift == KeyEvent(KeyCode.Char('p'), KeyModifiers.Ctrl | KeyModifiers.Shift))
    assert(Key.char('p').ctrl.shift == Key.char('p').shift.ctrl)
    assert(Key.char('p').ctrl.ctrl == Key.ctrl('p'))

  /** `KeyModifiers` is aliased into this package by hand rather than `export`ed, because an exported opaque type loses
    * its companion's extension methods. This is the assertion that the alias still carries them — the whole point being
    * that an application which only wrote `import io.worxbend.tui.dsl.*` can spell a Ctrl+Shift chord.
    */
  test("the package's KeyModifiers keeps the opaque type's own operators"):
    val chord: KeyModifiers = KeyModifiers.Ctrl | KeyModifiers.Shift
    assert(chord.hasAll(KeyModifiers.Ctrl | KeyModifiers.Shift))
    assert(!chord.hasAny(KeyModifiers.Alt))
    assert(chord.show == "Shift|Ctrl")

  test("onKey consumes a bound key, declines others, and returns via the handler"):
    var fired = false
    val el    = text("x").onKey(Key.Up) { fired = true }
    assert(el.props.onKey.exists(_(Key.Up)))
    assert(fired)
    fired = false
    assert(el.props.onKey.exists(handler => !handler(Key.Down)))
    assert(!fired)

  test("onKey binds several keys to one action"):
    var count = 0
    val el    = text("x").onKey(Key.Up, Key.Down) { count += 1 }
    el.props.onKey.foreach(h => { h(Key.Up); h(Key.Down); h(Key.Left) })
    assert(count == 2)

  /** The type annotation is the assertion: `onKey` gives back the element's own type, so the node-specific builders are
    * still reachable after a binding and `.rounded` below still type-checks.
    */
  test("onKey keeps the element's own type, so node-specific builders stay reachable"):
    val bound: PanelElement = panel(text("x")).onKey(Key.Enter) { () }.rounded
    assert(bound.borderType == BorderType.Rounded)

  test("chained onKey calls compose instead of overwriting"):
    var up   = false
    var down = false
    val el   = text("x").onKey(Key.Up) { up = true }.onKey(Key.Down) { down = true }
    el.props.onKey.foreach(h => { h(Key.Up); h(Key.Down) })
    assert(up && down)

  // withStyle's cascade is specified in StyleCascadeSpec, by what it paints rather than by the props it rewrote

  /** The annotations are the assertion: the flex helpers hand back the container's own type, so the result can be read
    * for `flex`/`spacing` without a cast, and `text("x").center` no longer compiles at all.
    */
  test("flex helpers set the mode on row/column/panel and keep the container's own type"):
    val centeredRow: RowElement     = row(text("a")).center
    val spreadColumn: ColumnElement = column(text("a")).spaceBetween
    val gappedRow: RowElement       = row(text("a")).gap(3)
    // a panel stacks its children with the same widget a column does, so it carries the same two knobs — and the
    // annotation is half the assertion: `.rounded` after `.gap` only compiles while the type is still PanelElement
    val gappedPanel: PanelElement   = panel("Logs")(text("a")).gap(2).rounded
    assert(centeredRow.flex == Flex.Center)
    assert(spreadColumn.flex == Flex.SpaceBetween)
    assert(gappedRow.spacing == 3)
    assert(gappedPanel.spacing == 2)
    assert(panel("Logs")(text("a")).spaceBetween.flex == Flex.SpaceBetween)
    assert(panel("Logs")(text("a")).gap(-4).spacing == 0) // negative counts clamp, as they do on row/column

  test("the View alias is a reactive computation producing an Element"):
    val v: View         = text("from a view alias")
    given ReactiveScope = ReactiveScope.generational(() => ())
    val el: Element     = v // applies the context function with the given scope and theme
    el match
      case node: TextElement => assert(node.content == "from a view alias")
      case other             => fail(s"expected a TextElement, got $other")

  /** The point of the spec overload is that one key has one spelling. Every spec below is bound both ways and the two
    * handlers must agree about which event fires them.
    */
  test("a key-spec string binds the same event the typed Key vocabulary does"):
    val cases = Seq(
      "q"         -> Key.char('q'),
      "ctrl+s"    -> Key.ctrl('s'),
      "shift+tab" -> Key.BackTab,
      "esc"       -> Key.Escape,
      "f2"        -> Key.f(2),
      "up"        -> Key.Up,
      "+"         -> Key.char('+'),
      "space"     -> Key.Space,
    )
    cases.foreach { (spec, event) =>
      var fromSpec  = false
      var fromTyped = false
      val bySpec    = text("x").onKey(spec) { fromSpec = true }
      val byTyped   = text("x").onKey(event) { fromTyped = true }
      assert(bySpec.props.onKey.exists(_(event)), s"spec '$spec' did not fire on $event")
      assert(byTyped.props.onKey.exists(_(event)))
      assert(fromSpec && fromTyped, s"spec '$spec' and its typed twin disagree")
    }

  test("a spec-string binding declines an unbound key, so it keeps bubbling"):
    var fired = false
    val el    = text("x").onKey("ctrl+s") { fired = true }
    assert(el.props.onKey.exists(handler => !handler(Key.ctrl('d'))))
    assert(!fired)

  test("several spec strings share one action, and the overload composes with the typed one"):
    var count = 0
    val el    = text("x").onKey("down", "j") { count += 1 }.onKey(Key.Up) { count += 10 }
    el.props.onKey.foreach { handler =>
      val _ = handler(Key.Down)
      val _ = handler(Key.char('j'))
      val _ = handler(Key.Up)
      val _ = handler(Key.char('z'))
    }
    assert(count == 12)

  /** The annotation is half the assertion: like the typed overload, the spec overload hands back the element's own
    * type, so `.rounded` still resolves after the binding.
    */
  test("a spec-string binding keeps the element's own type"):
    val bound: PanelElement = panel(text("x")).onKey("enter") { () }.rounded
    assert(bound.borderType == BorderType.Rounded)

  test("a misspelt spec fails where the element is built, not silently at run time"):
    val failure = intercept[IllegalArgumentException](text("x").onKey("ctlr+s") { () })
    assert(failure.getMessage.contains("ctlr+s"))

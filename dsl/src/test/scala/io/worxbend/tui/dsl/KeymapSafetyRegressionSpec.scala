package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

final class KeymapSafetyRegressionSpec extends AnyFunSuite:

  test("parsing rejects a framework sequence used where an element needs one key, naming its line"):
    val result = Keymap.parse("# keys\nframework.menu-bar = g g\n")
    assert(result.left.exists(error => error.line == 2 && error.message.contains("single key")))

  test("user rebind errors are values and validation keeps the offending file line"):
    val app = KeyBindings(binding("s", "save")(()), binding("g g", "top")(()))
    val bad = Map(BindingId("save") -> keyseq"g")
    assert(app.rebindEither(bad).left.exists(_.ids.contains(BindingId("save"))))
    assert(app.rebindEither(bad).getOrElse(app) eq app)
    assert(Keymap.validate("# keys\n\nsave = g\n", app, FrameworkKeys.default).left.exists(_.line == 3))
    assert(FrameworkKeys.default.rebindEither(Map(FrameworkKeys.MenuBar -> keyseq"g g")).isLeft)
    assert(Keymap.validate("save = ctrl+s\nframework.menu-bar = f9", app, FrameworkKeys.default).isRight)

  test("public composition checks modal versus global prefix collisions in both directions"):
    val modal  = KeyBindings.modes(Mode("normal", binding("g g", "top")(())))
    val global = KeyBindings(binding("g", "go")(()))
    assertThrows[IllegalArgumentException](modal ++ global)
    assertThrows[IllegalArgumentException](global ++ modal)
    assert((modal ++ KeyBindings(binding("x", "cut")(()))).bindings.size == 2)

  test("app and framework rebinding resolve the same leader"):
    given KeyLeader = KeyLeader(",")
    val map         = Map(FrameworkKeys.Quit -> keyseq"<leader> q", BindingId("save") -> keyseq"<leader> s")
    val framework   = FrameworkKeys.default.rebind(map)
    val app         = KeyBindings(binding("s", "save")(())).rebind(map)
    assert(framework.quit.keys == keyseq", q".keys)
    assert(app.bindings.head.triggers.head.keys == keyseq", s".keys)

  test("none round trips and disables application bindings without colliding or dispatching"):
    assert(Keymap.parse("save = none").contains(Map(BindingId("save") -> KeySequence.unbound)))
    var saves     = 0
    val app       = KeyBindings(binding("s", "save")(saves += 1), binding("x", "cut")(()))
      .rebind(Map(BindingId("save") -> KeySequence.unbound))
    assert(!app.handle(KeyEvent.char('s')))
    assert(app.handle(KeyEvent.char('x')))
    assert(saves == 0)
    val framework = FrameworkKeys.default.unbind(FrameworkKeys.Inspector)
    assert(Keymap.parse(s"${FrameworkKeys.Inspector} = ${framework.inspector.spelling}").isRight)

  test("application ids cannot silently rebind framework actions with the same description"):
    val map       = Keymap.parse("quit = ctrl+q").toOption.get
    val framework = FrameworkKeys.default.rebind(map)
    val app       = KeyBindings(binding("q", "quit")(())).rebind(map)
    assert(framework.quit == keyseq"ctrl+c")
    assert(app.bindings.head.triggers.head == keyseq"ctrl+q")
    assert(FrameworkKeys.Quit == BindingId("framework.quit"))
    assertThrows[IllegalArgumentException](KeyBindings(binding("q", "quit")(()).withId("framework.quit")))

package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.macros.deriveForm
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** A top-level case class so `deriveForm` can summon its `Mirror`. */
final case class Signup(username: String, subscribe: Boolean)

final class FormAccessibleSpec extends AnyFunSuite with PilotFixture:

  private def newState(): FormState[Signup] =
    val spec = deriveForm[Signup]
    FormState.of(spec, spec.field(_.username).validate(_.trim.nonEmpty, "required"))

  test("the accessible form announces each field's position and checkbox state as text"):
    val state   = newState()
    val backend = HeadlessBackend(Size(40, 12))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = Form.accessible(state)
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    assert(pilot.screenText.contains("Field 1 of 2: username"))
    assert(pilot.screenText.contains("Field 2 of 2: subscribe (unchecked)"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("validation failures are announced with an Error prefix, not color alone"):
    val state   = newState()
    val backend = HeadlessBackend(Size(40, 12))
    val app     = new TuiApp:
      override def bindings: KeyBindings            =
        KeyBindings(binding("ctrl+s", "submit")(state.submit()), binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = Form.accessible(state)
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.pressKey(KeyCode.Char('s'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Error: required"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.macros.{FieldInput, FormFieldType, deriveForm}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Top level so `deriveForm` can summon the mirrors, and so the given sits where an application would put it. */
enum Environment:
  case Development, Staging, Production

object Environment:
  given FormFieldType[Environment] = FormFieldType.ofEnum[Environment]

final case class Deployment(service: String, environment: Environment, dryRun: Boolean)

/** A derived form with an enum field: how the picklist renders, what it submits, and what a validator on it can do. */
final class FormEnumFieldSpec extends AnyFunSuite with PilotFixture:

  /** Starts an app around `view0`, with `Ctrl+S` bound to `submit()`.
    *
    * The binding is not a convenience: `submit` writes signals, and a signal write off the render thread throws. While
    * a runner is up, the test thread is not the render thread, so a submit has to travel in as an event the way a real
    * one does.
    */
  private def startApp(state: FormState[?])(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(50, 10))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+s", "submit")(state.submit()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def submit(pilot: Pilot): Unit =
    val _ = pilot.pressKey(KeyCode.Char('s'), KeyModifiers.Ctrl).waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("an enum field renders as a labelled one-row cycler starting on the first option"):
    val state = FormState.of(deriveForm[Deployment])
    val pilot = startApp(state)(Form(state))
    assert(pilot.screenLines(1).contains("environment:"))
    assert(pilot.screenLines(1).contains("Development"))
    quitApp(pilot)

  test("stepping the cycler and submitting yields the case the visible label names"):
    val state = FormState.of(deriveForm[Deployment])
    val pilot = startApp(state)(Form(state))
    pilot.pressKey(KeyCode.Tab).waitForIdle()   // past the text field, onto the picklist
    pilot.pressKey(KeyCode.Right).waitForIdle() // Development -> Staging
    assert(pilot.screenLines(1).contains("Staging"))
    submit(pilot)
    assert(state.errors.peek.isEmpty)
    assert(state.result.peek.contains(Deployment("", Environment.Staging, false)))
    quitApp(pilot)

  test("a form submitted untouched takes the first option rather than failing on a blank"):
    // The cycler always shows something, so unlike a text field there is no "nothing entered" state to reject.
    val state = FormState.of(deriveForm[Deployment])
    state.submit()
    assert(state.errors.peek.isEmpty)
    assert(state.result.peek.map(_.environment).contains(Environment.Development))

  test("a validator on the enum field rejects a choice and its message lands beside that field"):
    val spec  = deriveForm[Deployment]
    val state = FormState.of(
      spec,
      spec.field(_.environment).validate {
        case Environment.Production => Left("production needs an approval")
        case _                      => Right(())
      },
    )
    val pilot = startApp(state)(Form(state))
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Left).waitForIdle() // wraps from Development back to Production
    assert(pilot.screenLines(1).contains("Production"))
    submit(pilot)
    assert(state.errors.peek.get("environment").contains("production needs an approval"))
    assert(state.result.peek.isEmpty)
    quitApp(pilot)

  test("an enum validator cannot replace the choice with a String"):
    val errors = scala.compiletime.testing.typeCheckErrors("""
      val spec = deriveForm[Deployment]
      spec.field(_.environment).validate(value => Right(value.toString))
    """)
    assert(errors.nonEmpty)

  test("the accessible rendering spells out the chosen option and how many there are"):
    val state = FormState.of(deriveForm[Deployment])
    val pilot = startApp(state)(Form.accessible(state))
    val frame = pilot.screenLines.mkString("\n")
    assert(frame.contains("environment (Development, 3 options)"))
    quitApp(pilot)

  test("a picklist with no options submits an error rather than throwing on the render thread"):
    // Only an empty enum can produce this, but `submit` runs where an exception would take the whole app down, so the
    // impossible case still has to answer with a field error.
    final case class EmptyChoice(choice: String)
    given FormFieldType[String] = FormFieldType.ofLabels[String](Seq.empty)
    val empty                   = FormState.of(deriveForm[EmptyChoice])
    empty.submit()
    assert(empty.errors.peek.get("choice").exists(_.contains("no option to choose")))
    assert(empty.result.peek.isEmpty)

  test("the derived spec reports the picklist and its options to whoever inspects it"):
    assert(
      deriveForm[Deployment].fields.map(_.input) == Seq(
        FieldInput.TextField,
        FieldInput.SelectField(Seq("Development", "Staging", "Production")),
        FieldInput.BoolField,
      )
    )

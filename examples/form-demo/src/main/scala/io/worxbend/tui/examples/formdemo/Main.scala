package io.worxbend.tui.examples.formdemo

import io.worxbend.tui.dsl.*
import io.worxbend.tui.macros.deriveForm

/** form-demo: a form derived at compile time from a case class (`deriveForm`, zero reflection) with typed field
  * validation surfacing errors in the UI.
  *
  * Keys: type in fields · `Tab` next field · `Space` toggles the checkbox · `Ctrl+S` submit · `Esc` quit.
  */
final case class Signup(username: String, age: Int, subscribe: Boolean)

class FormDemoApp extends TuiApp:

  private val spec                 = deriveForm[Signup]
  val formState: FormState[Signup] = FormState.of(
    spec,
    spec.field(_.username).validate(_.trim.nonEmpty, "required"),
    spec.field(_.age).validate(_ >= 18, "must be 18 or older"),
  )

  /** The two app-wide keys are bindings rather than a panel-level handler: one declaration drives dispatch, the
    * status-bar hints and the palette. `TextInput` never consumes Esc or Ctrl+S, so both fire while a field is focused.
    */
  override def bindings: KeyBindings = KeyBindings(
    binding("ctrl+s", "submit")(formState.submit()),
    binding("esc", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    panel("Signup")(
      Form(formState),
      spacer,
      // `result` holds the last successful submit and later edits do not clear it, hence "last submitted".
      formState.result.get match
        case Some(signup) => text(s"last submitted: $signup").fg(Color.Green)
        case None         => text("Tab: next · Space: toggle · Ctrl+S: submit · Esc: quit").dim,
    ).rounded

object Main extends FormDemoApp

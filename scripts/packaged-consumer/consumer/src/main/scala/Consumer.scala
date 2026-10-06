package consumer

import io.worxbend.tui.dsl.*
import io.worxbend.tui.macros.deriveForm

final case class Registration(name: String, age: Int)
final case class Choice(enabled: Boolean)

final class ConsumerApp extends TuiApp:
  def view(using ReactiveScope, Theme): Element = text("packaged consumer")
  override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))

object Main:
  def main(args: Array[String]): Unit =
    val spec       = deriveForm[Registration]
    require(spec.fields.map(_.name) == Seq("name", "age"))
    val state      = FormState.of(spec)
    require(state.result.peek.isEmpty)
    val choiceSpec = deriveForm[Choice]
    val choices    = FormState.of(choiceSpec, choiceSpec.field(_.enabled).validate(!_, "must remain disabled"))
    choices.submit()
    require(choices.errors.peek.isEmpty)
    require(choices.result.peek.contains(Choice(false)))
    val area       = Rect(0, 0, 20, 1)
    val buffer     = Buffer(area)
    text("packaged consumer").widget.render(area, buffer)
    require(buffer.get(0, 0).symbol == "p")
    val combined   = KeyModifiers.Ctrl | KeyModifiers.Shift
    require(combined.hasAll(KeyModifiers.Ctrl))
    println("packaged DSL, derivation, rendering and opaque extensions passed")

package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.text as bufferText

import org.scalatest.funsuite.AnyFunSuite

/** Every word the framework draws on its own — a dialog's button, the palette's title, the help overlay's heading, the
  * wizard's Back/Next — comes from the [[Messages]] table an app hands in through `TuiApp.messages`, so an app shipped
  * in Spanish does not carry an English "OK" it never wrote.
  */
final class MessagesSpec extends AnyFunSuite with PilotFixture:

  private val Spanish: Messages = Messages.Default
    .withDialogOk("Sí")
    .withDialogCancel("Cancelar")
    .withHelpTitle("Ayuda")
    .withPaletteTitle("Órdenes")
    .withPalettePlaceholder("escribe para filtrar…")

  private def app(view0: View): TuiApp =
    new TuiApp:
      override def messages: Messages               = Spanish
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "salir")(quit()))
      def view(using ReactiveScope, Theme): Element = view0

  test("TuiApp.messages(Messages.Default.withDialogOk(\"Sí\")) shows Sí on a dialog"):
    val backend = HeadlessBackend(Size(40, 10))
    val pilot   = startPilot(backend)(app(dialog("Hola", "¿Listo?")).runWith(backend)).waitForIdle()
    assert(pilot.screenText.contains("[ Sí ]"), pilot.screenText)
    assert(!pilot.screenText.contains("OK"), pilot.screenText)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("Screen.confirm takes its two buttons from the app's messages"):
    val backend = HeadlessBackend(Size(50, 10))
    val confirm = new TuiApp:
      override def messages: Messages               = Spanish
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+d", "preguntar")(pushScreen(Screen.confirm("Salir", "¿Seguro?")((), ()))),
        binding("ctrl+q", "salir")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("principal")
    val pilot   = startPilot(backend)(confirm.runWith(backend)).waitForIdle()
    pilot.pressKey(KeyCode.Char('d'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("[ Sí ]") && pilot.screenText.contains("[ Cancelar ]"), pilot.screenText)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the help overlay and the command palette are titled from the app's messages"):
    val backend = HeadlessBackend(Size(60, 16))
    val help    = KeyBindings(binding("ctrl+g", "guardar")(()))
    val pilot   = startPilot(backend)(app(layers(text("principal"), helpOverlay(help))).runWith(backend)).waitForIdle()
    assert(pilot.screenText.contains("Ayuda"), pilot.screenText)
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Órdenes"), pilot.screenText)
    assert(pilot.screenText.contains("escribe para filtrar"), pilot.screenText)
    pilot.press("esc").waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("outside any app the defaults apply, and Messages.scoped overrides them for a block"):
    val english = bufferText(Snapshot.render(Size(30, 7))(dialog("Hi", "Ready?")))
    assert(english.contains("[ OK ]"), english)
    val spanish = Messages.scoped(Spanish)(bufferText(Snapshot.render(Size(30, 7))(dialog("Hola", "¿Listo?"))))
    assert(spanish.contains("[ Sí ]"), spanish)

  test("the wizard's Back, Next and Finish buttons come from the messages"):
    val labels = Messages.Default.withWizardBack("Atrás").withWizardNext("Siguiente").withWizardFinish("Terminar")
    assert(Messages.Default.wizardBack == "Back" && Messages.Default.wizardNext == "Next")
    assert(labels.wizardBack == "Atrás" && labels.wizardNext == "Siguiente" && labels.wizardFinish == "Terminar")

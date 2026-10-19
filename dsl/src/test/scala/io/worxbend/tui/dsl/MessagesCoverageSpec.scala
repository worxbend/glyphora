package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyModifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.text as bufferText
import io.worxbend.tui.widgets.NoticeLevel

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/** The rest of what the framework writes on its own, through [[Messages]]: the which-key ellipsis, the framework-key
  * descriptions, the prompts' validation errors, and the severity icons and tags of notices, badges and toasts.
  */
final class MessagesCoverageSpec extends AnyFunSuite with PilotFixture:

  private val Spanish: Messages = Messages.Default
    .withWhichKeyEllipsis("...")
    .withFrameworkKeyDescription(FrameworkKeys.Quit, "salir")
    .withPromptNotANumber("'{input}' no es un número entero")
    .withPromptNumberRange("escribe un número de {min} a {max}")
    .withPromptChooseAtLeast("elige al menos {min}")
    .withPromptPathRequired("hace falta una ruta")
    .withPromptPathMissing("{input} no existe")
    .withPromptAnswerYesNo("responde {yes} o {no}, no '{input}'")
    .withPromptNotAnOption("'{input}' no es ninguna de: {options}")
    .withIcon(NoticeLevel.Error, "E")
    .withTag(NoticeLevel.Error, "ERROR")

  /** What a plain prompt session writes while asking `ask` against the scripted `lines`. */
  private def plainly(lines: String*)(ask: PromptSession ?=> Unit): String =
    val input   = lines.iterator
    val written = StringBuilder()
    Messages.scoped(Spanish) {
      given PromptSession = PromptSession.plain(() => input.nextOption(), written ++= _)
      ask
    }
    written.toString

  test("the prompts' validation errors come from the messages, placeholders filled in"):
    val number  = plainly("x", "500", "7")(Prompt.number("Count", min = 1, max = 9): Unit)
    assert(number.contains("'x' no es un número entero"), number)
    assert(number.contains("escribe un número de 1 a 9"), number)
    val missing = Files.createTempDirectory("glyphora-messages")
    val path    = plainly("", "nope", "")(Prompt.path("File", base = missing, mustExist = true): Unit)
    assert(path.contains("hace falta una ruta"), path)
    assert(path.contains("nope no existe"), path)
    val many    = plainly("", "a")(Prompt.multiSelect("Many", Seq("a", "b"), min = 1): Unit)
    assert(many.contains("elige al menos 1"), many)
    val one     = plainly("zzz", "a")(Prompt.select("One", Seq("a", "b")): Unit)
    assert(one.contains("'zzz' no es ninguna de: a, b"), one)
    val yesNo   = plainly("quizá", "y")(Prompt.confirm("Go?"): Unit)
    assert(yesNo.contains("responde yes o no, no 'quizá'"), yesNo)

  test("the framework-key descriptions come from the messages"):
    val entries = Messages.scoped(Spanish)(FrameworkKeys.default.entries)
    assert(entries.find(_.id == FrameworkKeys.Quit).map(_.description).contains("salir"))
    assert(entries.find(_.id == FrameworkKeys.FocusNext).map(_.description).contains("focus the next control"))
    assert(FrameworkKeys.default.entries.find(_.id == FrameworkKeys.Quit).map(_.description).contains("quit"))

  test("notice icons and badge tags come from the messages"):
    val drawn   = Messages.scoped(Spanish)(
      bufferText(Snapshot.render(Size(20, 2))(column(notice("disk", NoticeLevel.Error), badge(NoticeLevel.Error))))
    )
    assert(drawn.contains("E disk"), drawn)
    assert(drawn.contains("ERROR"), drawn)
    val english =
      bufferText(Snapshot.render(Size(20, 2))(column(notice("disk", NoticeLevel.Error), badge(NoticeLevel.Error))))
    assert(english.contains("✖ disk") && english.contains("FAIL"), english)

  test("the which-key popup, the pending status bar and a toast use the app's messages"):
    final class Localised extends TuiApp:
      override def messages: Messages               = Spanish
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+k ctrl+s", "guardar todo")(()),
        binding("n", "avisar")(notify("lleno", NoticeLevel.Error)),
        binding("ctrl+q", "salir")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = column(text("editor").fill, statusBar(activeBindings))
    val backend = HeadlessBackend(Size(40, 10))
    val pilot = startPilot(backend)(Localised().runWith(backend)).waitForIdle()
    pilot.press("n").waitForIdle()
    assert(pilot.screenText.contains("E lleno"), pilot.screenText)
    pilot.pressKey(KeyCode.Char('k'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("ctrl+k ..."), pilot.screenText)
    assert(!pilot.screenText.contains("…"), pilot.screenText)
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

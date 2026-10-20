package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters.*

/** `AppServices.editExternally` and `openInEditor`: `$EDITOR` run on a file through `suspend`, with the result read
  * back. The editor here is a shell script in a temporary directory, which is what `EDITOR` points at.
  */
final class EditExternallySpec extends AnyFunSuite with PilotFixture:

  private def script(directory: Path, body: String): Path =
    val file = directory.resolve("editor.sh")
    Files.writeString(file, s"#!/bin/sh\n$body\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
    file

  private final class EditorApp(editor: Path) extends TuiApp:
    var edited: Option[Option[String]]                            = None
    var opened                                                    = false
    override protected def editorEnvironment: Map[String, String] = Map("EDITOR" -> editor.toString)
    override def bindings: KeyBindings                            = KeyBindings(
      binding("e", "edit")(edit()),
      binding("o", "open")(open()),
      binding("ctrl+q", "quit")(quit()),
    )
    def view(using ReactiveScope, Theme): Element                 = text("editor host")

    private def edit(): Unit = edited = Some(services.editExternally("draft\n", suffix = ".txt"))

    private def open(): Unit =
      services.openInEditor(editor.resolveSibling("notes.md"), line = Some(7))
      opened = true

  private def drive(app: EditorApp, key: String): HeadlessBackend =
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText(key).waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
    backend

  test("editExternally returns the new content when the editor changed the file"):
    val directory = Files.createTempDirectory("glyphora-edit")
    val app       = EditorApp(script(directory, """printf 'appended\n' >> "$1""""))
    val backend   = drive(app, "e")
    assert(app.edited.contains(Some("draft\nappended\n")))
    assert(backend.suspendCount == 1, "the editor ran with the terminal handed back")
    assert(Files.list(directory).iterator().asScala.map(_.getFileName.toString).toSeq == Seq("editor.sh"))

  test("editExternally returns None when the editor left the file as it was"):
    val directory = Files.createTempDirectory("glyphora-edit")
    val app       = EditorApp(script(directory, ":"))
    drive(app, "e")
    assert(app.edited.contains(None))

  test("openInEditor hands the editor the path and a +line argument"):
    val directory = Files.createTempDirectory("glyphora-edit")
    val recorded  = directory.resolve("args.txt")
    val app       = EditorApp(script(directory, s"""printf '%s\\n' "$$@" > "$recorded""""))
    drive(app, "o")
    assert(app.opened)
    assert(Files.readString(recorded).linesIterator.toSeq == Seq("+7", directory.resolve("notes.md").toString))

  test("the editor command comes from VISUAL, then EDITOR, then vi, and is split on blanks"):
    assert(ExternalEditor.command(Map("VISUAL" -> "code --wait", "EDITOR" -> "vim")) == Seq("code", "--wait"))
    assert(ExternalEditor.command(Map("EDITOR" -> "vim")) == Seq("vim"))
    assert(ExternalEditor.command(Map("EDITOR" -> "   ")) == Seq("vi"))
    assert(ExternalEditor.command(Map.empty) == Seq("vi"))

  test("quoted editor paths and arguments retain their literal argv boundaries"):
    assert(
      ExternalEditor.command(Map("EDITOR" -> "\"/Applications/My Editor\" --wait 'two words' escaped\\ space")) ==
        Seq("/Applications/My Editor", "--wait", "two words", "escaped space")
    )
    assert(ExternalEditor.command(Map("EDITOR" -> "editor '' \"\"")) == Seq("editor", "", ""))
    assert(
      ExternalEditor
        .command(Map("EDITOR" -> "vim; rm -rf /not-a-command")) == Seq("vim;", "rm", "-rf", "/not-a-command")
    )

  test("editor launch failures do not escape into a backend's suspended-body error boundary"):
    var caughtByBackend                = false
    def suspended(body: => Unit): Unit =
      try body
      catch case _: java.io.IOException => caughtByBackend = true
    assert(ExternalEditor.edit(Seq("/nonexistent/glyphora-editor"), "draft", ".txt", suspended).isEmpty)
    ExternalEditor.open(Seq("/nonexistent/glyphora-editor"), Path.of("notes"), None, suspended)
    assert(!caughtByBackend)

  test("a quoted executable path with spaces launches without a shell"):
    val directory = Files.createTempDirectory("glyphora editor path")
    val file      = script(directory, """printf 'changed' > "$1"""")
    val spaced    = directory.resolve("my editor.sh")
    Files.move(file, spaced)
    val argv      = ExternalEditor.command(Map("EDITOR" -> s"\"$spaced\""))
    assert(ExternalEditor.edit(argv, "draft", ".txt", body => body).contains("changed"))

  test("the no-op services edit nothing and open nothing"):
    assert(AppServices.NoOp.editExternally("x").isEmpty)
    AppServices.NoOp.openInEditor(Path.of("nowhere"))

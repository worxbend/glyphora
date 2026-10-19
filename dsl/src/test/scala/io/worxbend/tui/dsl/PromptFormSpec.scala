package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.macros.deriveForm
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

final class PromptFormSpec extends AnyFunSuite with PilotFixture:

  private final case class Config(name: String, port: Int, tls: Boolean)

  test("Prompt.form walks a three-field case class one field per step"):
    val backend = HeadlessBackend(Size(40, 8))
    val result  = AtomicReference(Option.empty[Option[Config]])
    val pilot   = startPilot(backend) {
      result.set(Some(Prompt.form[Config](using PromptSession.on(backend))))
      Right(())
    }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("? name"))
    pilot.typeText("api").press("enter").waitForIdle()
    assert(pilot.screenLines.contains("? name api"))
    assert(pilot.screenText.contains("? port"))
    pilot.typeText("8080").press("enter").waitForIdle()
    assert(pilot.screenText.contains("? tls"))
    pilot.press("y")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(Config("api", 8080, tls = true))))
    assert(pilot.screenLines.filter(_.startsWith("? ")) == Seq("? name api", "? port 8080", "? tls yes"))
    assert(backend.keptInlineRows.contains(3))

  test("Shift+Tab returns to the previous field with its value kept"):
    val backend = HeadlessBackend(Size(40, 8))
    val result  = AtomicReference(Option.empty[Option[Config]])
    val pilot   = startPilot(backend) {
      result.set(Some(Prompt.form[Config](using PromptSession.on(backend))))
      Right(())
    }
    pilot.waitForIdle().typeText("api").press("enter").waitForIdle().typeText("80").press("enter").waitForIdle()
    assert(pilot.screenText.contains("? tls"))
    pilot.press("shift+tab").waitForIdle()
    assert(!pilot.screenText.contains("? tls"), "the port is being asked again")
    assert(pilot.screenText.contains("? port 80"), "the field still holds the answer it was given")
    pilot.press("shift+tab").waitForIdle()
    assert(!pilot.screenText.contains("? port"))
    assert(pilot.screenText.contains("? name api"))
    pilot.press("enter").waitForIdle().typeText("81").press("enter").waitForIdle().press("n")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(Config("api", 8081, tls = false))))

  /** Ctrl-U used to be the form's "back" key, which took the kill-to-line-start chord away from every text field in a
    * form. It is the field's again.
    */
  test("Ctrl-U in a form's text field kills to the start of the line and stays on the field"):
    val backend = HeadlessBackend(Size(40, 8))
    val result  = AtomicReference(Option.empty[Option[Config]])
    val pilot   = startPilot(backend) {
      result.set(Some(Prompt.form[Config](using PromptSession.on(backend))))
      Right(())
    }
    pilot.waitForIdle().typeText("api").press("enter").waitForIdle().typeText("12").press("ctrl+u").waitForIdle()
    assert(pilot.screenText.contains("? port"), "Ctrl-U must not step back to the name")
    pilot.typeText("443").press("enter").waitForIdle().press("y")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(Config("api", 443, tls = true))))

  test("a field whose parser rejects the text keeps the form on that field"):
    val backend = HeadlessBackend(Size(40, 8))
    val pilot   = startPilot(backend) {
      val _ = Prompt.form[Config](using PromptSession.on(backend))
      Right(())
    }
    pilot.waitForIdle().typeText("api").press("enter").waitForIdle().typeText("eighty").press("enter").waitForIdle()
    assert(pilot.screenText.contains("? port"))
    assert(!pilot.screenText.contains("? tls"))
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("a scripted form takes one answer per field and checks the spec's validators"):
    val spec            = deriveForm[Config]
    val check           = spec.field(_.port).validate(port => port > 0 && port < 65536, "not a port")
    given PromptSession = Prompt.answers(Seq("api", "443", "yes"), _ => ())
    assert(Prompt.formOf(spec, check) == Some(Config("api", 443, tls = true)))
    val failing         = Prompt.answers(Seq("api", "70000", "no"), _ => ())
    assert(intercept[IllegalArgumentException](Prompt.formOf(spec, check)(using failing)).getMessage.contains("port"))

  test("path completes against the directory with Tab"):
    val base    = Files.createTempDirectory("prompt-path")
    val _       = Files.createDirectory(base.resolve("src"))
    val _       = Files.createFile(base.resolve("settings.json"))
    val _       = Files.createFile(base.resolve("README.md"))
    val backend = HeadlessBackend(Size(40, 6))
    val result  = AtomicReference(Option.empty[Option[java.nio.file.Path]])
    val pilot   = startPilot(backend) {
      result.set(Some(Prompt.path("File", base = base)(using PromptSession.on(backend))))
      Right(())
    }
    pilot.waitForIdle().typeText("s").press("tab").waitForIdle()
    assert(pilot.screenText.contains("settings.json"))
    assert(pilot.screenText.contains("src/"))
    pilot.typeText("e").press("tab").waitForIdle()
    assert(pilot.screenText.contains("? File settings.json"))
    pilot.press("enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(base.resolve("settings.json"))))

  test("password hides what is typed and summarises with a fixed mask"):
    given PromptSession = Prompt.answers(Seq("hunter2"), _ => ())
    assert(Prompt.password("Token") == Some("hunter2"))
    assert(summon[PromptSession].transcript == Seq("? Token ********"))

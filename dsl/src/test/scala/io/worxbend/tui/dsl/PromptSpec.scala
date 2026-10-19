package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.{BackendError, HeadlessBackend}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.ArrayBuffer

final class PromptSpec extends AnyFunSuite with PilotFixture:

  /** Runs `ask` on the pilot's app thread against `backend`, keeping what it returned. */
  private def asking[A](backend: HeadlessBackend)(ask: PromptSession ?=> A): (Pilot, AtomicReference[Option[A]]) =
    val result  = AtomicReference(Option.empty[A])
    val session = PromptSession.on(backend)
    val pilot   = startPilot(backend) {
      result.set(Some(ask(using session)))
      Right(())
    }
    (pilot, result)

  test("a scripted text answer comes back, and its summary line is what the scrollback keeps"):
    val echoed          = ArrayBuffer.empty[String]
    given PromptSession = Prompt.answers(Seq("alpha"), echoed += _)
    assert(Prompt.text("Name") == Some("alpha"))
    assert(summon[PromptSession].transcript == Seq("? Name alpha"))
    assert(echoed.toSeq == Seq("? Name alpha"))

  test("a scripted answer the prompt rejects fails loudly instead of hanging"):
    given PromptSession = Prompt.answers(Seq("", "x"), _ => ())
    val error           = intercept[IllegalArgumentException](Prompt.text("Name", validate = Prompt.required))
    assert(error.getMessage.contains("Name"))
    val exhausted       = Prompt.answers(Nil, _ => ())
    assert(intercept[IllegalArgumentException](Prompt.confirm("Go?")(using exhausted)).getMessage.contains("Go?"))

  test("scripted select, multiSelect, confirm and number parse their answers the way the controls would"):
    given PromptSession = Prompt.answers(Seq("beta", "1, gamma", "y", "", "42"), _ => ())
    assert(Prompt.select("Pick", Seq("alpha", "beta", "gamma")) == Some("beta"))
    assert(Prompt.multiSelect("Many", Seq("alpha", "beta", "gamma")) == Some(Seq("alpha", "gamma")))
    assert(Prompt.confirm("Sure?") == Some(true))
    assert(Prompt.confirm("Default?", default = true) == Some(true))
    assert(Prompt.number("Count", min = 1, max = 100) == Some(42L))
    assert(
      summon[PromptSession].transcript ==
        Seq("? Pick beta", "? Many alpha, gamma", "? Sure? yes", "? Default? yes", "? Count 42")
    )

  test("in a terminal, typing then Enter answers, and the strip collapses to the summary line"):
    val backend         = HeadlessBackend(Size(30, 6))
    val (pilot, result) = asking(backend)(Prompt.text("Name"))
    pilot.waitForIdle().typeText("alpha").press("enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some("alpha")))
    assert(pilot.screenLines.contains("? Name alpha"))
    assert(backend.keptInlineRows.contains(1))

  test("a failed validation keeps the prompt open with the message under it"):
    val backend         = HeadlessBackend(Size(40, 6))
    val (pilot, result) = asking(backend)(Prompt.text("Name", validate = Prompt.required))
    pilot.waitForIdle().press("enter").waitForIdle()
    assert(pilot.screenText.contains("an answer is required"))
    pilot.typeText("ok").press("enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some("ok")))

  test("select: Down Down Enter answers the third option"):
    val backend         = HeadlessBackend(Size(40, 8))
    val (pilot, result) = asking(backend)(Prompt.select("Tier", Seq("free", "pro", "team")))
    pilot.waitForIdle().press("down", "down", "enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some("team")))
    assert(pilot.screenLines.contains("? Tier team"))
    assert(backend.keptInlineRows.contains(1))

  test("select: Ctrl-C, as a signal or as a key, answers None"):
    val first         = HeadlessBackend(Size(40, 8))
    val (signal, one) = asking(first)(Prompt.select("Tier", Seq("free", "pro", "team")))
    signal.waitForIdle().press("down").interrupt()
    assert(signal.awaitTermination())
    assert(one.get.contains(None))
    assert(signal.screenLines.contains("? Tier (cancelled)"))
    val second        = HeadlessBackend(Size(40, 8))
    val (key, two)    = asking(second)(Prompt.select("Tier", Seq("free", "pro", "team")))
    key.waitForIdle().press("ctrl+c")
    assert(key.awaitTermination())
    assert(two.get.contains(None))

  test("multiSelect: Space ticks, Enter answers in list order"):
    val backend         = HeadlessBackend(Size(40, 8))
    val (pilot, result) = asking(backend)(Prompt.multiSelect("Langs", Seq("scala", "rust", "go")))
    pilot.waitForIdle().press("down", "down", "space", "up", "up", "space", "enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(Seq("scala", "go"))))

  test("confirm: y answers at once"):
    val backend         = HeadlessBackend(Size(40, 4))
    val (pilot, result) = asking(backend)(Prompt.confirm("Deploy?"))
    pilot.waitForIdle().press("y")
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(true)))
    assert(pilot.screenLines.contains("? Deploy? yes"))

  test("plain shape: questions are written, lines are read, and the end of input cancels"):
    val input           = Iterator("", "7", "maybe")
    val written         = StringBuilder()
    given PromptSession = PromptSession.plain(() => input.nextOption(), written ++= _)
    assert(Prompt.number("Count", min = 1, max = 9) == Some(7L))
    assert(written.toString.startsWith("? Count: "))
    assert(written.toString.contains("is not a whole number"))
    assert(Prompt.confirm("Go?") == None)
    assert(summon[PromptSession].transcript == Seq("? Count 7", "? Go? (cancelled)"))

  test("task: the work logs three lines under a spinner, then one summary line is all that is left"):
    val backend         = HeadlessBackend(Size(40, 8))
    val gate            = java.util.concurrent.CountDownLatch(1)
    val (pilot, result) = asking(backend) {
      Prompt.task("Downloading") { log =>
        log("one")
        log("two")
        log("three")
        gate.await()
        42
      }
    }
    pilot.waitUntil("the log to show three lines")(Seq("one", "two", "three").forall(pilot.screenText.contains))
    assert(pilot.screenText.contains("Downloading"))
    gate.countDown()
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(42)))
    assert(pilot.screenLines.filter(_.nonEmpty) == Seq("✓ Downloading"))
    assert(backend.keptInlineRows.contains(1))

  test("task: Ctrl-C cancels without waiting for the work, and a failure is rethrown after its summary"):
    val backend         = HeadlessBackend(Size(40, 8))
    val (pilot, result) = asking(backend)(Prompt.task("Waiting")(_ => Thread.sleep(60_000)))
    pilot.waitForIdle().interrupt()
    assert(pilot.awaitTermination())
    assert(result.get.contains(None))
    assert(pilot.screenLines.contains("✗ Waiting (cancelled)"))
    given PromptSession = Prompt.answers(Nil, _ => ())
    val error           = intercept[IllegalStateException](Prompt.task("Boom")(_ => throw IllegalStateException("no")))
    assert(error.getMessage == "no")
    assert(summon[PromptSession].transcript == Seq("✗ Boom no"))

  test("loader drives a progress row through every item and answers the results in order"):
    val backend         = HeadlessBackend(Size(40, 4))
    val (pilot, result) = asking(backend)(Prompt.loader("Squares", 1 to 5)(n => n * n))
    assert(pilot.awaitTermination())
    assert(result.get.contains(Some(Seq(1, 4, 9, 16, 25))))
    assert(pilot.screenLines.contains("✓ Squares 5/5"))
    given PromptSession = Prompt.answers(Nil, _ => ())
    assert(Prompt.loader("Again", Seq("a", "b"))(_.toUpperCase) == Some(Seq("A", "B")))

  test("plain shape with echoInput writes each answer back, so piped answers read as question-answer pairs"):
    val input           = Iterator("alpha", "secret")
    val written         = StringBuilder()
    given PromptSession = PromptSession.plain(() => input.nextOption(), written ++= _, echoInput = true)
    assert(Prompt.text("Name") == Some("alpha"))
    assert(Prompt.password("Token") == Some("secret"))
    assert(written.toString == "? Name: alpha\n? Token: \n")

  /** A terminal session over `backend` whose plain fallback reads `input` and appends to `written`. */
  private def failingTerminal(
      backend: HeadlessBackend,
      input: Iterator[String],
      written: StringBuilder,
  ): PromptSession =
    backend.failNext(HeadlessBackend.Op.ReserveInlineRows, BackendError.Io(java.io.IOException("the tty went away")))
    PromptSession.terminalOver(
      () => Right(backend),
      PromptSession.Mode.Plain(() => input.nextOption(), written ++= _, echoInput = false),
      Theme.Dark,
    )

  test("a terminal that fails under a prompt answers it in the plain shape instead of throwing"):
    val written         = StringBuilder()
    given PromptSession = failingTerminal(HeadlessBackend(Size(30, 6)), Iterator("alpha"), written)
    assert(Prompt.text("Name") == Some("alpha"))
    assert(written.toString.startsWith("? Name: "))
    assert(summon[PromptSession].transcript == Seq("? Name alpha"))

  test("a terminal that fails under a task runs the work once and reports it in the plain shape"):
    val written         = StringBuilder()
    val runs            = java.util.concurrent.atomic.AtomicInteger(0)
    given PromptSession = failingTerminal(HeadlessBackend(Size(30, 6)), Iterator.empty, written)
    assert(Prompt.task("Fetch")(_ => runs.incrementAndGet()) == Some(1))
    assert(runs.get == 1)
    assert(written.toString.contains("✓ Fetch"))
    assert(summon[PromptSession].transcript == Seq("✓ Fetch"))

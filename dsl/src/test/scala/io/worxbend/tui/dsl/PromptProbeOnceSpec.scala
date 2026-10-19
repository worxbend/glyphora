package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend, Support, TerminalCapabilities}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.ArrayBuffer

/** Terminal prompts are one-shot apps, each on a backend of its own; the capabilities the first one probed are handed
  * to every later one, so a run of prompts costs one probe round trip, not one per prompt.
  */
final class PromptProbeOnceSpec extends AnyFunSuite with PilotFixture:

  private val probed = TerminalCapabilities(kittyGraphics = Support.Yes, synchronizedOutput = Support.No)

  test("the first open probes; every later open is handed what the first one's backend reported"):
    val seen    = ArrayBuffer.empty[Option[TerminalCapabilities]]
    val backend = HeadlessBackend(Size(30, 4))
    backend.capabilitiesTo(probed)
    val probe   = ProbeOnce(known => { seen += known; Right(backend) })
    assert(probe.open().isRight)
    probe.remember(backend)
    assert(probe.open().isRight)
    assert(probe.open().isRight)
    assert(seen.toSeq == Seq(None, Some(probed), Some(probed)))

  test("a backend the probe did not open is never remembered"):
    val seen  = ArrayBuffer.empty[Option[TerminalCapabilities]]
    val probe = ProbeOnce(known => { seen += known; Right(HeadlessBackend(Size(30, 4))) })
    val other = HeadlessBackend(Size(30, 4))
    other.capabilitiesTo(probed)
    probe.remember(other)
    assert(probe.open().isRight)
    assert(seen.toSeq == Seq(None))

  test("two prompts in a row: the second opens its backend with the first prompt's capabilities"):
    val seen     = ArrayBuffer.empty[Option[TerminalCapabilities]]
    val first    = HeadlessBackend(Size(30, 4))
    first.capabilitiesTo(probed)
    val backends = Iterator[Either[BackendError, Backend]](Right(first), Left(BackendError.UnsupportedTerminal("x")))
    val written  = StringBuilder()
    val answers  = Iterator("beta")
    val plain    = new PromptSession.Mode.Plain(() => answers.nextOption(), text => { val _ = written ++= text }, false)
    val session  = PromptSession.probing(ProbeOnce(known => { seen += known; backends.next() }), plain, Theme.Dark)
    val result   = AtomicReference(Option.empty[(Option[String], Option[String])])
    val pilot    = startPilot(first) {
      val one = Prompt.text("One")(using session)
      val two = Prompt.text("Two")(using session)
      result.set(Some((one, two)))
      Right(())
    }
    pilot.waitForIdle().typeText("alpha").press("enter")
    assert(pilot.awaitTermination())
    assert(result.get.contains((Some("alpha"), Some("beta"))))
    assert(seen.toSeq == Seq(None, Some(probed)))

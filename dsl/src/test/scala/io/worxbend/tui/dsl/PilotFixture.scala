package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.RunnerError
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.{Exceptional, Outcome, TestSuite, TestSuiteMixin}

/** Test-only ownership for helpers that return a Pilot. Acquisition is explicit, before any startup wait. Each suite
  * has its own thread-local fixture, so concurrent tests never close another test's pilots. Normal quit/termination
  * assertions remain the test's responsibility; cancellation is only a cleanup backstop.
  */
private[tui] trait PilotFixture extends TestSuiteMixin:
  this: TestSuite =>

  private final class OwnedPilots:
    private var pilots = List.empty[Pilot]

    def start(backend: HeadlessBackend)(app: => Either[RunnerError, Unit]): Pilot =
      val pilot = Pilot.start(backend)(app)
      pilots = pilot :: pilots
      pilot

    def close(primary: Option[Throwable]): Unit =
      var failure = primary
      pilots.foreach { pilot =>
        try pilot.close()
        catch
          case error: Throwable =>
            failure match
              case Some(first) => if first ne error then first.addSuppressed(error)
              case None        => failure = Some(error)
      }
      if primary.isEmpty then failure.foreach(error => throw error)

  private val fixture = ThreadLocal.withInitial(() => Option.empty[OwnedPilots])

  protected final def startPilot(backend: HeadlessBackend)(app: => Either[RunnerError, Unit]): Pilot =
    val owned = fixture.get().getOrElse(throw IllegalStateException("startPilot requires an active test fixture"))
    owned.start(backend)(app)

  abstract override def withFixture(test: NoArgTest): Outcome =
    val owned   = new OwnedPilots
    fixture.set(Some(owned))
    var primary = Option.empty[Throwable]
    try
      val outcome = super.withFixture(test)
      outcome match
        case Exceptional(error) => primary = Some(error)
        case _                  => ()
      outcome
    catch
      case error: Throwable =>
        primary = Some(error)
        throw error
    finally
      fixture.remove()
      owned.close(primary)

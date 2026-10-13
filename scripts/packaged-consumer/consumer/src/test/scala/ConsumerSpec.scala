package consumer

import io.worxbend.tui.dsl.*
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.compiletime.testing.typeCheckErrors
import scala.concurrent.duration.*

import org.scalatest.funsuite.AnyFunSuite

final class ConsumerSpec extends AnyFunSuite:
  test("the packaged test-only Pilot drives the packaged application"):
    val backend = HeadlessBackend(Size(20, 2))
    val app     = ConsumerApp()
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      assert(pilot.screenText.contains("packaged consumer"))
    }

  test("packaged one-import frame observation and timing work with the packaged runner"):
    val delivered = CountDownLatch(1)
    val frames    = AtomicReference(Option.empty[String])
    val errors    = AtomicReference(Option.empty[Throwable])
    val timings   = AtomicReference(Option.empty[FrameTiming])
    val observer  = LatestFrameObserver(
      frame =>
        frames.set(Some(frame.text))
        delivered.countDown()
      ,
      error =>
        errors.set(Some(error))
        delivered.countDown(),
    )
    val app       = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(
        onFrame = Some(observer),
        onFrameTiming = Some((timing: FrameTiming) => timings.set(Some(timing))),
      )
      def view(using ReactiveScope, Theme): Element = text("sampled package")
    val backend   = HeadlessBackend(Size(20, 2))
    try
      Pilot.using(backend)(app.runWith(backend)) { pilot =>
        pilot.waitForIdle()
        assert(delivered.await(2, TimeUnit.SECONDS))
        assert(errors.get().isEmpty)
        assert(frames.get().exists(_.contains("sampled package")))
        assert(timings.get().exists(timing => timing.count >= 0 && timing.drawNanos >= 0))
      }
    finally
      observer.close()
      assert(observer.awaitTermination(2.seconds))

  test("packaged derived forms reject the old type-changing replacement path"):
    assert(typeCheckErrors("""
      import io.worxbend.tui.dsl.*
      import io.worxbend.tui.macros.{deriveForm, Field}
      case class Account(age: Int)
      val spec = deriveForm[Account]
      FormState.of(spec, Field.int("age").map(_.toString))
    """).nonEmpty)

  test("packaged typed field handles reject widened value identities"):
    assert(typeCheckErrors("""
      import io.worxbend.tui.macros.{deriveForm, DerivedField}
      case class Account(age: Int)
      val spec = deriveForm[Account]
      val widened: DerivedField[Account, Any] = spec.field(_.age)
    """).nonEmpty)

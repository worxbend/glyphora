package consumer

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.funsuite.AnyFunSuite

final class ConsumerSpec extends AnyFunSuite:
  test("the packaged test-only Pilot drives the packaged application"):
    val backend = HeadlessBackend(Size(20, 2))
    val app     = ConsumerApp()
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      assert(pilot.screenText.contains("packaged consumer"))
    }

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

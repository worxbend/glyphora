package glyphora.consumer.forms

import java.time.LocalDate
import java.util.UUID

import io.worxbend.tui.dsl.*
import io.worxbend.tui.macros.{Field, FieldInput, FormFieldType, deriveForm}

import org.scalatest.funsuite.AnyFunSuite

object FormDomains:
  opaque type Email = String
  object Email:
    def from(raw: String): Either[String, Email] =
      if raw.contains("@") then Right(raw.trim) else Left("invalid email")
    extension (value: Email) def text: String    = value
    given FormFieldType[Email]                   = FormFieldType(FieldInput.TextField)(from)

  opaque type Token = String
  object Token:
    given FormFieldType[Token] = FormFieldType(FieldInput.TextField)(raw => Right(raw))

final case class TypedSignup(age: Int)
final case class TypedProfile(
    email: FormDomains.Email,
    age: Int,
    count: Long,
    ratio: Double,
    price: BigDecimal,
    id: UUID,
    date: LocalDate,
    note: Option[String],
    consent: Boolean,
)
final case class GenericForm[T](value: T)

final class TypedFormConsumerSpec extends AnyFunSuite:
  private def fill[A](state: FormState[A], values: String*): Unit =
    given ReactiveScope = ReactiveScope.untracked
    val controls        = Form(state).children.flatMap(_.children)
    val inputs          = controls.collect:
      case input: InputElement        => input.state
      case number: NumberInputElement => number.state
    assert(inputs.size == values.size)
    inputs.zip(values).foreach((input, value) => input.insert(value))

  test("a type-changing replacement parser is not accepted by a derived form"):
    val errors = scala.compiletime.testing.typeCheckErrors("""
      import io.worxbend.tui.dsl.*
      import io.worxbend.tui.macros.{Field, deriveForm}
      FormState.of(deriveForm[TypedSignup], Field.int("age").map(_.toString))
    """)
    assert(errors.exists(_.message.contains("FieldValidation")))

  test("same-control parsers cannot replace Long, BigDecimal, UUID, or opaque Email fields"):
    val long    = scala.compiletime.testing.typeCheckErrors("""
      FormState.of(deriveForm[TypedProfile], Field.int("count"))
    """)
    val decimal = scala.compiletime.testing.typeCheckErrors("""
      FormState.of(deriveForm[TypedProfile], Field.double("price"))
    """)
    val uuid    = scala.compiletime.testing.typeCheckErrors("""
      FormState.of(deriveForm[TypedProfile], Field.text("id"))
    """)
    val opaque  = scala.compiletime.testing.typeCheckErrors("""
      FormState.of(deriveForm[TypedProfile], Field.text("email"))
    """)
    Seq(long, decimal, uuid, opaque).foreach(errors => assert(errors.exists(_.message.contains("FieldValidation"))))

  test("typed handles cannot map or return a replacement value from validation"):
    val mapping   = scala.compiletime.testing.typeCheckErrors("""
      deriveForm[TypedSignup].field(_.age).map(_.toString)
    """)
    val replacing = scala.compiletime.testing.typeCheckErrors("""
      deriveForm[TypedSignup].field(_.age).validate(value => Right(value.toString))
    """)
    assert(mapping.exists(_.message.contains("map")))
    assert(replacing.exists(_.message.contains("Unit")))

  test("field selections reject unknown names, computed values, methods, and widened types"):
    val unknown  = scala.compiletime.testing.typeCheckErrors("deriveForm[TypedSignup].field(_.agge)")
    val computed = scala.compiletime.testing.typeCheckErrors("deriveForm[TypedSignup].field(_.age.toString)")
    val method   = scala.compiletime.testing.typeCheckErrors("deriveForm[TypedSignup].field(_.hashCode)")
    val widened  = scala.compiletime.testing.typeCheckErrors("deriveForm[TypedSignup].field[Any](_.age)")
    assert(unknown.nonEmpty)
    assert(computed.exists(_.message.contains("direct case-class field")))
    assert(method.exists(_.message.contains("case-class field")), method.map(_.message).mkString("; "))
    assert(widened.exists(_.message.contains("exact declared type")))

  test("opaque-domain and underlying String or sibling Token identities cannot be interchanged"):
    val underlying = scala.compiletime.testing.typeCheckErrors("""
      val handle: DerivedField[TypedProfile, String] = deriveForm[TypedProfile].field(_.email)
    """)
    val sibling    = scala.compiletime.testing.typeCheckErrors("""
      val handle: DerivedField[TypedProfile, FormDomains.Token] = deriveForm[TypedProfile].field(_.email)
    """)
    val predicate  = scala.compiletime.testing.typeCheckErrors("""
      deriveForm[TypedProfile].field(_.email).validate((value: String) => value.nonEmpty, "required")
    """)
    Seq(underlying, sibling, predicate).foreach(errors => assert(errors.nonEmpty))

  test("a different product's validator cannot be attached"):
    val errors = scala.compiletime.testing.typeCheckErrors("""
      val other = deriveForm[GenericForm[Int]]
      FormState.of(deriveForm[TypedSignup], other.field(_.value).validate(_ > 0, "positive"))
    """)
    assert(errors.exists(_.message.contains("FieldValidation")))

  test("consumers cannot construct handles, replace spec defaults, or invoke untyped assembly"):
    val forged    = scala.compiletime.testing.typeCheckErrors("""
      new DerivedField[TypedSignup, String](deriveForm[TypedSignup], "age", _.age.toString)
    """)
    val replaced  = scala.compiletime.testing.typeCheckErrors("""
      deriveForm[TypedSignup].copy(defaults = Seq(Field.int("age").map(_.toString)))
    """)
    val assembled = scala.compiletime.testing.typeCheckErrors("""
      deriveForm[TypedSignup].assemble(Seq("42"))
    """)
    Seq(forged, replaced, assembled).foreach(errors => assert(errors.nonEmpty))

  test("successful submission remains usable by an ordinary consumer"):
    val spec                                   = deriveForm[TypedSignup]
    val handle: DerivedField[TypedSignup, Int] = spec.field(_.age)
    val check: FieldValidation[TypedSignup]    = handle.validate(_ >= 18, "must be adult")
    val state                                  = FormState.of(spec, check)
    fill(state, "42")
    state.submit()
    assert(state.errors.peek.isEmpty)
    assert(state.result.peek.exists(_.age == 42))

  test("generic field selection preserves its substituted type"):
    val spec                                          = deriveForm[GenericForm[Long]]
    val handle: DerivedField[GenericForm[Long], Long] = spec.field(_.value)
    val state = FormState.of(spec, handle.validate(_ > Int.MaxValue.toLong, "large"))
    fill(state, "2147483648")
    state.submit()
    assert(state.result.peek.contains(GenericForm(2147483648L)))

  test("opaque and same-control fields retain their original parsers and submit their declared types"):
    import FormDomains.Email.*
    val spec                                                 = deriveForm[TypedProfile]
    val email: DerivedField[TypedProfile, FormDomains.Email] = spec.field(_.email)
    val state                                                = FormState.of(
      spec,
      email.validate(_.text.endsWith("@example.com"), "company email"),
      spec.field(_.count).validate(_ > Int.MaxValue.toLong, "large count"),
      spec.field(_.price).validate(_ == BigDecimal("0.1"), "exact price"),
      spec.field(_.note).validate(_.isEmpty, "optional"),
    )
    val id                                                   = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    fill(state, " ada@example.com ", "42", "2147483648", "1.25", "0.1", id.toString, "2026-09-01", "")
    state.submit()
    assert(state.errors.peek.isEmpty)
    val submitted                                            = state.result.peek.get
    assert(submitted.email.text == "ada@example.com")
    assert(submitted.age == 42)
    assert(submitted.count == 2147483648L)
    assert(submitted.ratio == 1.25)
    assert(submitted.price == BigDecimal("0.1"))
    assert(submitted.id == id)
    assert(submitted.date == LocalDate.of(2026, 9, 1))
    assert(submitted.note.isEmpty)
    assert(!submitted.consent)

  test("handles keep the parser instance captured during derivation rather than re-summoning a typeclass"):
    var parses = 0
    val spec   = locally:
      given FormFieldType[Int] = FormFieldType(FieldInput.IntField) { raw =>
        parses += 1
        raw.toIntOption.map(_ + 100).toRight("not a number")
      }
      deriveForm[TypedSignup]
    val state  = FormState.of(spec, spec.field(_.age).validate(_ == 107, "captured parser"))
    fill(state, "7")
    state.submit()
    assert(parses == 1)
    assert(state.result.peek.contains(TypedSignup(107)))

  test("submission parses before construction and constructs before typed validation"):
    var steps                = Vector.empty[String]
    final case class Ordered(value: Int):
      steps :+= "construct"
    given FormFieldType[Int] = FormFieldType(FieldInput.IntField) { raw =>
      steps :+= "parse"
      raw.toIntOption.toRight("not a number")
    }
    val spec                 = deriveForm[Ordered]
    val state                = FormState.of(
      spec,
      spec.field(_.value).validate { _ =>
        steps :+= "validate"
        Left("rejected")
      },
    )
    fill(state, "7")
    state.submit()
    assert(steps == Vector("parse", "construct", "validate"))
    assert(state.result.peek.isEmpty)
    assert(state.errors.peek == Map("value" -> "rejected"))

  test("standalone parser mapping may change type but is separate from derived validation"):
    val parser = Field.int("count").map(_.toString)
    assert(parser.parse("42") == Right("42"))

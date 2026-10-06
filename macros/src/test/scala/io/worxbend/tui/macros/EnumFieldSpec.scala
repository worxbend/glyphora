package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

/** An enum of parameterless cases, opting into a picklist from its own companion — the shape the documentation shows.
  * Top level, because a `Mirror.SumOf` for an enum nested inside a test method is not what an application writes.
  */
enum Role:
  case Admin, Editor, Viewer

object Role:
  given FormFieldType[Role] = FormFieldType.ofEnum[Role]

/** The same enum with labels chosen by hand rather than taken from the case names. */
enum Tier:
  case Free, Paid

object Tier:
  given FormFieldType[Tier] = FormFieldType.ofLabels(Seq("no charge" -> Tier.Free, "billed" -> Tier.Paid))

private final case class Member(name: String, role: Role, tier: Tier)

/** `FormFieldType.ofEnum` and the derivation path it opens: an enum field in a derived form.
  *
  * Everything here resolves at compile time — the case names come from the type and the case values from implicit
  * search — so nothing in this feature reads a class at runtime.
  */
final class EnumFieldSpec extends AnyFunSuite:

  private val role = summon[FormFieldType[Role]]

  test("the control is a picklist whose options are the case names, in declaration order"):
    assert(role.input == FieldInput.SelectField(Seq("Admin", "Editor", "Viewer")))

  test("a label parses back to the case it names"):
    assert(role.parse("Admin") == Right(Role.Admin))
    assert(role.parse("Editor") == Right(Role.Editor))
    assert(role.parse("Viewer") == Right(Role.Viewer))

  test("matching ignores surrounding whitespace and letter case, because the label travels as text"):
    assert(role.parse("  admin ") == Right(Role.Admin))
    assert(role.parse("VIEWER") == Right(Role.Viewer))

  test("a label that names no case is rejected with a message listing the ones that exist"):
    val rejected = role.parse("Owner")
    assert(rejected.isLeft)
    assert(rejected.left.exists(_.contains("Admin, Editor, Viewer")))
    assert(role.parse("").isLeft)

  test("ofLabels takes labels the case names do not supply"):
    val tier = summon[FormFieldType[Tier]]
    assert(tier.input == FieldInput.SelectField(Seq("no charge", "billed")))
    assert(tier.parse("billed") == Right(Tier.Paid))
    assert(tier.parse("Paid").isLeft) // the case name is not a label unless it was made one

  test("a picklist with no options at all rejects everything rather than accepting a blank"):
    val nothing = FormFieldType.ofLabels[Role](Seq.empty)
    assert(nothing.input == FieldInput.SelectField(Seq.empty))
    assert(nothing.parse("Admin").isLeft)
    assert(nothing.parse("").isLeft)

  test("an enum field joins a derived form as a picklist, beside the ordinary ones"):
    val spec = deriveForm[Member]
    assert(spec.fields.map(_.name) == Seq("name", "role", "tier"))
    assert(
      spec.fields.map(_.input) == Seq(
        FieldInput.TextField,
        FieldInput.SelectField(Seq("Admin", "Editor", "Viewer")),
        FieldInput.SelectField(Seq("no charge", "billed")),
      )
    )

  test("the derived form assembles a value with the enum case the label named"):
    val spec   = deriveForm[Member]
    val parsed = spec.defaults.zip(Seq("Ada", "Editor", "billed")).map((field, raw) => field.parse(raw))
    assert(parsed.forall(_.isRight))
    assert(spec.assemble(parsed.collect { case Right(value) => value }) == Member("Ada", Role.Editor, Tier.Paid))

  test("Field.enumeration builds a standalone parser with the same derived options"):
    val validator = Field.enumeration[Role]("role")
    assert(validator.spec == FieldSpec("role", FieldInput.SelectField(Seq("Admin", "Editor", "Viewer"))))
    val guarded   = validator.mapValidated {
      case Role.Admin => Left("an admin cannot be created here")
      case other      => Right(other)
    }
    assert(guarded.parse("Admin") == Left("an admin cannot be created here"))
    assert(guarded.parse("Viewer") == Right(Role.Viewer))

  test("an Option of an enum renders as the same picklist and treats blank as absent"):
    // `option` reuses the inner instance's control, so an optional picklist is still a picklist.
    val optional = summon[FormFieldType[Option[Role]]]
    assert(optional.input == FieldInput.SelectField(Seq("Admin", "Editor", "Viewer")))
    assert(optional.parse("  ") == Right(None))
    assert(optional.parse("Editor") == Right(Some(Role.Editor)))
    assert(optional.parse("Owner").isLeft)

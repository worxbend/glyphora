package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

/** Top level so the snippet compiled by `typeCheckErrors` can name it. `tags` is a `List[String]`, a type no
  * `FormFieldType` exists for — and, unlike the enum the diagnostic's last sentence is about, not a type anyone could
  * fix by writing `ofEnum` for it.
  */
private final case class Tagged(name: String, tags: List[String])

/** The `@implicitNotFound` message on [[FormFieldType]] is the only guidance the author of an unsupported field gets,
  * so its advice has to be phrased for the type it is about: the missing-instance half names the field's own type, and
  * the enum shortcut is offered for *an enum*, not spelled out as `ofEnum[List[String]]` — a line that could never
  * compile.
  */
final class UnsupportedFieldMessageSpec extends AnyFunSuite:

  private def message: String =
    scala.compiletime.testing.typeCheckErrors("deriveForm[Tagged]").map(_.message).mkString("; ")

  test("the message names the missing instance for the field's own type"):
    val text = message
    assert(text.contains("no form control is defined for a field of type List[String]"), text)
    assert(text.contains("define a `given FormFieldType[List[String]]`"), text)
    assert(text.contains("next to your own type to teach the derivation about it"), text)

  test("the enum advice is phrased for an enum, not for the unsupported type"):
    val text = message
    assert(!text.contains("ofEnum[List[String]]"), text)
    assert(!text.contains("FormFieldType[List[String]] = FormFieldType.ofEnum"), text)
    assert(text.contains("If the field's type is an enum whose cases all take no parameters"), text)
    assert(text.contains("`given FormFieldType[YourEnum] = FormFieldType.ofEnum[YourEnum]` in its companion"), text)

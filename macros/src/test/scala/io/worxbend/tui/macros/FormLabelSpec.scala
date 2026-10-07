package io.worxbend.tui.macros

import org.scalatest.funsuite.AnyFunSuite

final class FormLabelSpec extends AnyFunSuite:
  test("offered labels with surrounding whitespace round-trip without changing their display"):
    val field = FormFieldType.ofLabels(Seq(" Admin " -> 1, " Viewer " -> 2))
    assert(field.input == FieldInput.SelectField(Seq(" Admin ", " Viewer ")))
    assert(field.parse(" Admin ") == Right(1))
    assert(field.parse("viewer") == Right(2))

  test("labels that collide under whitespace and case matching fail at declaration"):
    val failure = intercept[IllegalArgumentException] {
      FormFieldType.ofLabels(Seq("Alpha" -> 1, " alpha " -> 2))
    }
    assert(failure.getMessage.contains("alpha"))

  test("Unicode case collisions use the same comparison as parsing"):
    intercept[IllegalArgumentException] {
      FormFieldType.ofLabels(Seq("I" -> 1, "ı" -> 2))
    }
    val field = FormFieldType.ofLabels(Seq("I" -> 1))
    assert(field.parse("ı") == Right(1))

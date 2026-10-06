package io.worxbend.tui.dsl

import io.worxbend.tui.macros.deriveForm

import org.scalatest.funsuite.AnyFunSuite

final case class Registration(email: String, age: Int, terms: Boolean)

final class FormValidatorWiringSpec extends AnyFunSuite:
  private val spec = deriveForm[Registration]

  private def fill(state: FormState[Registration], email: String = "someone@example.com", age: String = "30"): Unit =
    state.bindings.foreach:
      case FieldBinding.TextLike(field, input, _) => input.insert(if field.name == "age" then age else email)
      case _                                      => ()

  test("two validators for the same field are rejected rather than silently last-wins"):
    val thrown = intercept[IllegalArgumentException]:
      FormState.of(
        spec,
        spec.field(_.email).validate(_ => Left("first")),
        spec.field(_.email).validate(_ => Left("second")),
      )
    assert(thrown.getMessage.contains("email"))

  test("a validator from another spec is rejected even when the product and field types match"):
    val other  = deriveForm[Registration]
    val thrown = intercept[IllegalArgumentException]:
      FormState.of(spec, other.field(_.email).validate(_ => Left("wrong owner")))
    assert(thrown.getMessage.contains("different form spec"))

  test("a typed validator runs and does not replace the parsed value"):
    val state = FormState.of(spec, spec.field(_.email).validate(_.contains("@"), "no @"))
    fill(state, email = "not-an-email")
    state.submit()
    assert(state.errors.peek == Map("email" -> "no @"))
    assert(state.result.peek.isEmpty)

  test("a boolean field's validator runs on submit"):
    val state = FormState.of(spec, spec.field(_.terms).validate(identity, "you must accept"))
    fill(state)
    state.submit()
    assert(state.errors.peek == Map("terms" -> "you must accept"))
    assert(state.result.peek.isEmpty)

  test("a boolean field with a validator passes once it is ticked"):
    val state = FormState.of(spec, spec.field(_.terms).validate(identity, "you must accept"))
    fill(state)
    state.bindings.foreach:
      case bound: FieldBinding.BoolLike => bound.value.set(true)
      case _                            => ()
    state.submit()
    assert(state.errors.peek.isEmpty)
    assert(state.result.peek.contains(Registration("someone@example.com", 30, true)))

  test("a boolean field with no validator submits its value unchanged"):
    val state = FormState.of(spec)
    fill(state)
    state.submit()
    assert(state.result.peek.contains(Registration("someone@example.com", 30, false)))

  test("all checks run on a parseable candidate and failures are collected by field"):
    val state = FormState.of(
      spec,
      spec.field(_.email).validate(_.contains("@"), "no @"),
      spec.field(_.age).validate(_ >= 18, "must be adult"),
      spec.field(_.terms).validate(identity, "you must accept"),
    )
    fill(state, email = "bad", age = "12")
    state.submit()
    assert(state.errors.peek == Map("email" -> "no @", "age" -> "must be adult", "terms" -> "you must accept"))

  test("parsing precedes typed validation and never calls checks on a nonexistent candidate"):
    var checks = 0
    val state  = FormState.of(
      spec,
      spec.field(_.email).validate { _ =>
        checks += 1
        Left("no @")
      },
    )
    fill(state, age = "invalid")
    state.submit()
    assert(checks == 0)
    assert(state.errors.peek.keySet == Set("age"))
    assert(state.result.peek.isEmpty)

  test("composed checks stop at the first failure and retain field ownership"):
    val email  = spec.field(_.email)
    val state  = FormState.of(spec, email.validate(_.nonEmpty, "required").and(email.validate(_.contains("@"), "no @")))
    fill(state, email = "")
    state.submit()
    assert(state.errors.peek == Map("email" -> "required"))
    val thrown = intercept[IllegalArgumentException]:
      email.validate(_ => Right(())).and(spec.field(_.age).validate(_ => Right(())))
    assert(thrown.getMessage.contains("same spec and field"))

  test("failed validation clears a previously successful result and a correction clears errors"):
    val state    = FormState.of(spec, spec.field(_.terms).validate(identity, "you must accept"))
    fill(state)
    val checkbox = state.bindings.collectFirst { case bound: FieldBinding.BoolLike => bound.value }.get
    checkbox.set(true)
    state.submit()
    assert(state.result.peek.nonEmpty)
    checkbox.set(false)
    state.submit()
    assert(state.result.peek.isEmpty)
    assert(state.errors.peek == Map("terms" -> "you must accept"))
    checkbox.set(true)
    state.submit()
    assert(state.errors.peek.isEmpty)
    assert(state.result.peek.nonEmpty)

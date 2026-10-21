package io.worxbend.tui.examples.airsensor

import org.scalatest.funsuite.AnyFunSuite

final class SensorPayloadSpec extends AnyFunSuite:
  test("flat numeric payload retains decimal signs, duplicate-last and unrelated-field policy"):
    val body = """{"rco2":640,"pm02":-2.5,"tvocIndex":12.75,"atmp":-0.5,"ignored":9,"rco2":700}"""
    assert(AirGradientClient.readingFrom(body) == Right(Reading(700, -2.5, 12.75, -0.5)))
    assert(AirGradientClient.numericFields(body)("ignored") == 9.0)

  test("missing and nonnumeric fields fail in the established required-field order"):
    assert(AirGradientClient.readingFrom("garbage") == Left("missing field 'rco2'"))
    assert(AirGradientClient.readingFrom("""{"rco2":1,"pm02":null}""") == Left("missing field 'pm02'"))
    assert(
      AirGradientClient.readingFrom("""{"rco2":1,"pm02":2,"tvocIndex":"3"}""") ==
        Left("missing field 'tvocIndex'")
    )
    assert(
      AirGradientClient.readingFrom("""{"rco2":1,"pm02":2,"tvocIndex":3}""") ==
        Left("missing field 'atmp'")
    )

  test("the deliberately partial numeric scanner does not validate JSON or exponent syntax"):
    assert(
      AirGradientClient.numericFields("""broken "x":1e3, "y":+2, "z":.5, "w":4.}""") ==
        Map("x" -> 1.0, "w" -> 4.0)
    )
    assert(
      AirGradientClient.readingFrom(""""rco2":1 "pm02":2 "tvocIndex":3 "atmp":4""") ==
        Right(Reading(1, 2, 3, 4))
    )

package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

final class TextMeasurementReuseSpec extends AnyFunSuite:
  test("an immutable text node reuses its measured paragraph between layout and paint"):
    val element = text("word " * 1000).wrapped
    val first   = element.widget
    assert(element.intrinsicHeight(20).contains(250))
    assert(element.widget eq first)
    assert(element.intrinsicHeight(20).contains(250))

  test("restyling an immutable text node creates a separate paragraph"):
    val element = text("hello").wrapped
    val styled  = element.fg(Color.Red)
    assert(styled.widget ne element.widget)
    assert(styled.widget eq styled.widget)

  test("an immutable styled-line node reuses its measured paragraph"):
    val element = line(Span.raw("hello"), Span.raw(" world"))
    assert(element.widget eq element.widget)

package io.worxbend.tui.widgetsconsumer

import io.worxbend.tui.core.Style
import io.worxbend.tui.widgets.{HighlightSpacing, ListDirection, ListView}

import org.scalatest.funsuite.AnyFunSuite

final class ListViewCompatibilitySpec extends AnyFunSuite:

  test("ListView retains the established positional constructor style slots"):
    val items       = Seq("row")
    val style       = Style.Default.bold
    val highlighted = Style.Default.reverse
    val applied = ListView(items, ListDirection.TopToBottom, HighlightSpacing.Always, false, style, highlighted, "> ")
    val constructed =
      new ListView(items, ListDirection.TopToBottom, HighlightSpacing.Always, false, style, highlighted, "> ")
    assert(applied == constructed)
    assert(applied.style == style && applied.highlightStyle == highlighted)
    assert(applied.highlightSymbol == "> ")

  test("ListView retains the established positional copy style slots"):
    val style       = Style.Default.bold
    val highlighted = Style.Default.reverse
    val copied      = ListView(Seq("row")).copy(
      Seq("replacement"),
      ListDirection.TopToBottom,
      HighlightSpacing.Always,
      false,
      style,
      highlighted,
      "> ",
    )
    assert(copied.items == Seq("replacement"))
    assert(copied.style == style && copied.highlightStyle == highlighted)
    assert(copied.highlightSymbol == "> ")

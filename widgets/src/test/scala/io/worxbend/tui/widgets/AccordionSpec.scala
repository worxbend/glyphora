package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Text, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class AccordionSpec extends AnyFunSuite:

  private def body(text: String): Widget = Paragraph(Text.raw(text))

  private val sections = Seq("General" -> body("name, email"), "Network" -> body("proxy"), "About" -> body("v1"))

  test("toggling one section closes whichever other section was open"):
    val state = AccordionState()
    assert(state.open.isEmpty)
    state.toggle(0)
    assert(state.open.contains(0))
    state.toggle(2)
    assert(state.open.contains(2))
    state.toggle(2)
    assert(state.open.isEmpty)

  test("headers stack one row each and the open section's body takes the rows between them"):
    val buffer = rendered(Accordion(sections), AccordionState(Some(1)), 16, 6)
    assert(
      lines(buffer).map(_.stripTrailing) == Seq(
        "▸ General",
        "▾ Network",
        "proxy",
        "",
        "",
        "▸ About",
      )
    )

  test("with nothing open the headers sit at the top and the rest stays blank"):
    val buffer = rendered(Accordion(sections), AccordionState(), 16, 4)
    assert(lines(buffer).map(_.stripTrailing) == Seq("▸ General", "▸ Network", "▸ About", ""))

  test("an open index past the sections is ignored, and a short area keeps as many headers as fit"):
    val buffer = rendered(Accordion(sections), AccordionState(Some(9)), 16, 2)
    assert(lines(buffer).map(_.stripTrailing) == Seq("▸ General", "▸ Network"))

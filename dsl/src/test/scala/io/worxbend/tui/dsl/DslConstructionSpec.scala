package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Alignment, Color, Constraint, Flex, KeyCode, KeyEvent, KeyModifiers, Modifiers, Span}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.widgets.{BigText, BorderType}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** DSL-construction tests: build element trees, assert their shape and props as plain data. */
final class DslConstructionSpec extends AnyFunSuite:

  test("the hello-world tree has the documented shape"):
    val tree = panel("Hello")(
      text("Welcome!").bold.fg(Color.Cyan),
      spacer,
      text("Press 'q' to quit").dim,
    ).rounded
    tree match
      case node: PanelElement if node.title.contains("Hello") && node.borderType == BorderType.Rounded =>
        val children = node.children
        assert(children.size == 3)
        children(0) match
          case node: TextElement => assert(node.content == "Welcome!")
          case other             => fail(s"expected the first child to be a TextElement, got $other")
        children(1) match
          case _: SpacerElement => ()
          case other            => fail(s"expected the second child to be a SpacerElement, got $other")
      case other => fail(s"unexpected tree shape: $other")

  test("styling extensions accumulate into the element style"):
    val element = text("x").bold.dim.fg(Color.Red).bg(Color.Black)
    assert(element.style.modifiers.hasAny(Modifiers.Bold))
    assert(element.style.modifiers.hasAny(Modifiers.Dim))
    assert(element.style.fg.contains(Color.Red))
    assert(element.style.bg.contains(Color.Black))

  test("layout extensions set the explicit constraint"):
    assert(text("x").length(5).props.constraint.contains(Constraint.Length(5)))
    assert(text("x").percent(30).props.constraint.contains(Constraint.Percentage(30)))
    assert(text("x").fill.props.constraint.contains(Constraint.Fill(1)))
    assert(text("x").fill(3).props.constraint.contains(Constraint.Fill(3)))
    assert(text("x").minSize(2).props.constraint.contains(Constraint.Min(2)))
    assert(text("x").maxSize(9).props.constraint.contains(Constraint.Max(9)))

  test("text claims one row per line by default; containers fill"):
    // the claim is the paragraph's own measured box (`Constraint.Auto` both ways), not a box recomputed by the node
    assert(text("a\nb").claim == SizeClaim.Measured)
    assert(text("a\nb").intrinsicHeight(20).contains(2))
    assert(text("ab\nwide line").intrinsicWidth(2).contains(9))
    assert(row().claim.vertical == Constraint.Fill(1))
    assert(spacer(2).props.constraint.contains(Constraint.Length(2)))

  /** A one-row control claims one row *vertically* and whatever the container has *horizontally*. Saying so with a
    * baked-in `Constraint.Length(1)` in the node's props instead — which is what `progressBar`, `indeterminateBar` and
    * `marquee` used to do — sets both axes at once, so the same bar came out one column wide inside a `row` and every
    * call site had to write `.fill` to undo it.
    */
  test("one-row controls claim a row of height, not a column of width"):
    given Theme = Theme.default
    Seq(progressBar(0.5), indeterminateBarAt(0.millis), marqueeAt("news", 0.millis)).foreach { element =>
      assert(element.props.constraint.isEmpty, s"$element should leave the constraint to the caller")
      assert(element.claim == SizeClaim.OneRow, s"$element should claim one row and a fill of width")
    }

  /** The same rule for the fixed-height widgets behind the [[WidgetElement]] escape hatch. `rule`, `link`, `bigText`
    * and `tooltip` each used to bake their *height* into `props.constraint`, which a container applies along whichever
    * axis it runs — so inside a `row` a divider was one column of `─`, a link showed one letter, and a tooltip was
    * three columns of border with the help text gone. Their height is a fact about the widget, so it belongs in the
    * claim.
    */
  test("fixed-height wrapped widgets keep their width inside a row"):
    given Theme = Theme.default
    Seq(
      rule("a")                -> 1,
      link("docs", "http://x") -> 1,
      bigText("HI")            -> BigText.GlyphHeight,
      tooltip("some help")     -> 3,
    ).foreach { case (element, expectedRows) =>
      assert(element.props.constraint.isEmpty, s"$element should leave the constraint to the caller")
      assert(element.claim == SizeClaim.rows(expectedRows), s"$element should claim $expectedRows rows and fill width")
    }
    // and the layout actually honours it: two rules share a 40-column row instead of taking a column each
    assert(trimmedLines(rendered(row(rule(), rule()).widget, 40, 1)).head.length == 40)
    assert(trimmedLines(rendered(row(text("See: "), link("docs", "http://x")).widget, 40, 1)).head == "See: docs")

  /** `.rows(n)` is the escape hatch's own way to say a height, and it survives into the claim rather than into the
    * axis-ambiguous constraint that `.length(n)` sets.
    */
  test("widget(...).rows(n) claims height without claiming width"):
    assert(widget((_, _) => ()).claim == SizeClaim.Fill)
    assert(widget((_, _) => ()).rows(3).claim == SizeClaim.rows(3))
    assert(widget((_, _) => ()).rows(3).props.constraint.isEmpty)

  test("border builders are typed to panels and give back a panel"):
    assert(panel(text("x")).rounded.borderType == BorderType.Rounded)
    assert(panel(text("x")).doubleBorder.borderType == BorderType.Double)
    // `text("x").rounded` does not compile: `rounded` exists only on PanelElement
    assertDoesNotCompile("""text("x").rounded""")

  /** The type annotations are the assertion: every fluent call gives back the element's own type, so the node's own
    * builders stay reachable after a styling or layout call and the two orders mean the same thing.
    */
  test("styling and layout calls preserve the element's own type in either order"):
    given Theme                    = Theme.default
    val first: ProgressBarElement  = progressBar(0.5).bare.fill
    val second: ProgressBarElement = progressBar(0.5).fill.bare
    assert(first == second)
    val styledPanel: PanelElement  = panel(text("x")).bold.length(3).rounded
    assert(styledPanel.borderType == BorderType.Rounded)

  /** The knobs the factory Scaladoc promised. `table`'s doc said "add a header with `.header(...)`" and `sparkline`'s
    * said "pin the ceiling with `.max(n)`" while neither method existed, and `marquee` returned the builder-less
    * escape-hatch node, so its two real knobs — speed and gap — could not be reached from the DSL at all.
    */
  test("the header, ceiling and ticker builders reach the widget"):
    val headed = table(Seq(Seq("api", "ready")), Constraint.Length(6), Constraint.Fill(1)).header("svc", "state")
    assert(headed.header.contains(Seq("svc", "state")))
    assert(trimmedLines(rendered(headed.widget, 20, 2)).head.startsWith("svc"))

    // the same series drawn against its own peak and against a pinned ceiling of ten: the taller of the two glyphs is
    // what makes two sparklines side by side incomparable until the ceiling is pinned
    val floating = sparkline(Seq(1L, 4L))
    val pinned   = floating.max(10L)
    assert(pinned.max.contains(10L))
    assert(rendered(floating.widget, 2, 1).get(1, 0).symbol != rendered(pinned.widget, 2, 1).get(1, 0).symbol)

    val ticker: MarqueeElement = marqueeAt("news", 0.millis).speed(4.0).gap(2)
    assert(ticker.cellsPerSecond == 4.0)
    assert(ticker.gap == 2)
    // one lap is the four clusters of "news" plus the two-cell gap, so six cells in three seconds is two a second
    assert(ticker.period(3.seconds).cellsPerSecond == 2.0)
    assert(ticker.claim.vertical == Constraint.Length(1))

  test("onKeyEvent attaches a handler without disturbing the rest of the props"):
    var ran                          = false
    val handler: KeyEvent => Boolean = _ =>
      ran = true
      true
    val element                      = text("x").bold.onKeyEvent(handler)
    // The stored handler wraps the one passed in, so that a second `onKeyEvent` composes rather than replacing it;
    // asserting on behaviour rather than identity is what makes the test independent of that wrapping.
    assert(element.props.onKey.exists(_(Key.Enter)))
    assert(ran)
    assert(element.style.modifiers.hasAny(Modifiers.Bold))

  test("key events route to the innermost handler first and consumption stops propagation"):
    val seen     = scala.collection.mutable.Buffer[String]()
    val tree     = column(
      text("inner").onKeyEvent { _ =>
        seen += "inner"
        true
      }
    ).onKeyEvent { _ =>
      seen += "outer"
      true
    }
    val consumed = EventRouter.dispatchKey(tree, KeyEvent(KeyCode.Enter, KeyModifiers.None))
    assert(consumed)
    assert(seen.toSeq == Seq("inner"))

  test("an unconsumed event bubbles from leaf to ancestor"):
    val seen     = scala.collection.mutable.Buffer[String]()
    val tree     = column(
      text("inner").onKeyEvent { _ =>
        seen += "inner"
        false
      }
    ).onKeyEvent { _ =>
      seen += "outer"
      false
    }
    val consumed = EventRouter.dispatchKey(tree, KeyEvent(KeyCode.Enter, KeyModifiers.None))
    assert(!consumed)
    assert(seen.toSeq == Seq("inner", "outer"))

  test("a line's placement builders travel into the Line the element renders"):
    val element = line(Span.raw("Total"), Span.raw(" 42")).rightAligned
    assert(element.align.contains(Alignment.Right))
    element.widget match
      case paragraph: io.worxbend.tui.widgets.Paragraph =>
        assert(paragraph.text.lines.map(_.alignment) == Seq(Some(Alignment.Right)))
      case other                                        => fail(s"expected a Paragraph, got $other")

  test("a line with no placement builder leaves the choice to the widget drawing it"):
    val element = line(Span.raw("Total"))
    assert(element.align.isEmpty)
    assert(trimmedLines(rendered(element.widget, 8, 1)) == Seq("Total"))

  test("a right-aligned line pushes its spans to the right edge when rendered"):
    val element = line(Span.raw("Total")).rightAligned
    assert(trimmedLines(rendered(element.widget, 8, 1)) == Seq("   Total"))

  /** A divider inside a thick panel has to be drawn with the thick run, or the two read as separate frames. */
  test("rule takes its weight from a BorderType and still claims one row"):
    given Theme = Theme.default
    val thick   = rule("cfg", BorderType.Thick)
    assert(
      thick.widget == io.worxbend.tui.widgets.Rule(
        Some("cfg"),
        style = Theme.default.border,
        labelStyle = Theme.default.muted,
        borderType = BorderType.Thick,
      )
    )
    assert(thick.claim == SizeClaim.rows(1))
    assert(trimmedLines(rendered(rule("", BorderType.Double).widget, 6, 1)) == Seq("══════"))

  /** The panel-side half of border merging: the builder reaches the widget, and the default is the no-op. */
  test("panel .mergeBorders reaches the block and defaults to Replace"):
    given Theme = Theme.default
    assert(panel(text("a")).mergeBorders == io.worxbend.tui.widgets.MergeStrategy.Replace)
    val merged  = panel(text("a")).mergeBorders(io.worxbend.tui.widgets.MergeStrategy.Exact)
    assert(merged.mergeBorders == io.worxbend.tui.widgets.MergeStrategy.Exact)
    // panels only merge where they actually overlap: a `row` gives each child its own columns, so the seam that needs
    // joining is the one an overlapping overlay creates
    val frame   = rendered(
      layers(
        panel(spacer).length(4),
        positioned(3, 0, 5, 3)(panel(spacer).mergeBorders(io.worxbend.tui.widgets.MergeStrategy.Exact)),
      ).widget,
      8,
      3,
    )
    assert(trimmedLines(frame) == Seq("┌──┬───┐", "│  │   │", "└──┴───┘"))

  /** `table` is a `FlexContainer`, so the alignment and gap builders every `row` and `column` has apply to its columns
    * too. Before that, fixed-width columns could only ever pack at the left of the area they were given.
    */
  test("a table's columns can be centred and its column gap set through the container builders"):
    val fixed   = table(Seq(Seq("ab", "cd")), Constraint.Length(2), Constraint.Length(2)).gap(0)
    val centred = fixed.center
    assert(centred.flex == Flex.Center)
    assert(fixed.columnSpacing == 0)
    // four columns of content in an eight-column area: packed left it starts at 0, centred it starts at 2
    assert(rendered(fixed.widget, 8, 1).get(0, 0).symbol == "a")
    assert(rendered(centred.widget, 8, 1).get(2, 0).symbol == "a")

  /** `table`'s widths are varargs, so writing none of them compiles. It used to draw a blank rectangle. */
  test("a table with no widths divides its area equally"):
    val bare   = table(Seq(Seq("a", "b")))
    assert(bare.widths.isEmpty)
    val buffer = rendered(bare.gap(0).widget, 8, 1)
    assert(buffer.get(0, 0).symbol == "a")
    assert(buffer.get(4, 0).symbol == "b")

  /** `.footer(...)` pins a summary row to the bottom of the area. Before it existed a totals row had to be a second
    * `table` in a `column`, with the width constraints written out twice and free to drift apart.
    */
  test("a table's footer is pinned to the bottom, not appended after the last row"):
    val totals = table(Seq(Seq("api", "3")), Constraint.Length(4), Constraint.Length(2))
      .gap(0)
      .header("svc", "n")
      .footer("all", "3")
    assert(totals.footer.contains(Seq("all", "3")))
    assert(trimmedLines(rendered(totals.widget, 6, 5)) == Seq("svc n", "api 3", "", "", "all 3"))

package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

enum InspectMode:
  case Dev, Prod

final case class InspectDatabase(host: String, port: Int, replicas: List[String])

final case class InspectConfig(
    name: String,
    mode: InspectMode,
    database: InspectDatabase,
    flags: Map[String, Boolean],
    timeout: Option[Int],
)

/** Construction and rendering of the prototyping and inspection nodes in `ChoiceElements`, `DisplayElements` and
  * `CollectionElements` (formerly `ToolElements`).
  */
final class ToolElementsSpec extends AnyFunSuite:
  given Theme = Theme.default

  test("placeholder draws its label and its size in the theme's colours"):
    val element = placeholder("Sidebar")
    assert(element.debugName == "placeholder")
    val buffer  = rendered(element.widget, 11, 4)
    assert(lines(buffer) == Seq("┌╌╌╌╌╌╌╌╌╌┐", "╎ Sidebar ╎", "╎  11×4   ╎", "└╌╌╌╌╌╌╌╌╌┘"))
    assert(buffer.get(2, 1).style == Theme.default.muted.patch(Theme.default.accent))
    assert(buffer.get(0, 0).style == Theme.default.muted)

  test("inspect draws a derived nested case class as a tree"):
    val config  = InspectConfig(
      "glyphora",
      InspectMode.Prod,
      InspectDatabase("db.internal", 5432, List("replica-a", "replica-b")),
      Map("beta" -> true),
      None,
    )
    val element = inspect(config)
    assert(element.debugName == "inspect")
    GoldenFrames.assertMatches("inspect-nested-case-class", rendered(element.widget, 52, 10))

  test("qrCode claims exactly the box its symbol and quiet zone measure"):
    val element = qrCode("HELLO WORLD")
    assert(element.debugName == "qrCode")
    // version 1 is 21 modules; a two-module quiet zone each side makes 25 columns and 13 half-block rows
    val buffer  = rendered(row(element, text("after")).widget, 32, 13)
    assert(lines(buffer).head.drop(25).startsWith("after"))
    assert(buffer.get(2, 1).symbol == "█")

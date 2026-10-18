package io.worxbend.tui.widgets

import io.worxbend.tui.core.Style
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class InspectSpec extends AnyFunSuite:

  private val PV = PrettyValue

  private val config = PV.Product(
    "Config",
    Seq(
      "name"    -> PV.Atom("\"glyphora\""),
      "tags"    -> PV.Seq("List", Seq(PV.Atom("\"tui\""), PV.Atom("\"scala\""))),
      "db"      -> PV.Product("Db", Seq("host" -> PV.Atom("\"localhost\""), "pool" -> PV.Atom("4"))),
      "plugins" -> PV
        .Seq("List", Seq(PV.Product("Plugin", Seq("id" -> PV.Atom("1"), "deps" -> PV.Seq("List", Seq(PV.Atom("2"))))))),
      "mode"    -> PV.Product("Fast", Seq.empty),
      "limits"  -> PV.Map("Map", Seq(PV.Atom("\"cpu\"") -> PV.Atom("2"))),
    ),
  )

  test("a nested value is drawn as a tree, with short all-atom nodes kept on one row"):
    assert(
      trimmedLines(rendered(Inspect(config, inlineWidth = 40), 50, 11)) == Seq(
        "Config",
        "├─ name: \"glyphora\"",
        "├─ tags: List(\"tui\", \"scala\")",
        "├─ db: Db(host = \"localhost\", pool = 4)",
        "├─ plugins: List(1)",
        "│  └─ [0] Plugin",
        "│     ├─ id: 1",
        "│     └─ deps: List(2)",
        "├─ mode: Fast",
        "└─ limits: Map(\"cpu\" -> 2)",
        "",
      )
    )

  test("a node whose one-row form is wider than inlineWidth is expanded instead"):
    assert(
      trimmedLines(rendered(Inspect(config, inlineWidth = 10), 30, 14)).take(8) == Seq(
        "Config",
        "├─ name: \"glyphora\"",
        "├─ tags: List(2)",
        "│  ├─ [0] \"tui\"",
        "│  └─ [1] \"scala\"",
        "├─ db: Db",
        "│  ├─ host: \"localhost\"",
        "│  └─ pool: 4",
      )
    )

  test("an atom, an empty collection and a short root render on a single row"):
    assert(lines(rendered(Inspect(PV.Atom("42")), 4, 1)) == Seq("42  "))
    assert(lines(rendered(Inspect(PV.Seq("List", Seq.empty)), 8, 1)) == Seq("List()  "))
    assert(trimmedLines(rendered(Inspect(PV.Product("P", Seq("a" -> PV.Atom("1")))), 10, 1)) == Seq("P(a = 1)"))

  test("text is cut at the area's width by display width"):
    val wide = PV.Product(
      "名前",
      Seq("値" -> PV.Atom("\"日本語テキスト\""), "x" -> PV.Seq("L", Seq(PV.Product("Q", Seq("k" -> PV.Atom("1")))))),
    )
    assert(lines(rendered(Inspect(wide, inlineWidth = 4), 9, 2)) == Seq("名前     ", "├─ 値: \" "))

  test("the inspector measures one row per line of the tree and its widest line"):
    val inspect = Inspect(config, inlineWidth = 40)
    assert(inspect.heightAt(80).contains(10))
    assert(inspect.widthAt(10).contains("├─ db: Db(host = \"localhost\", pool = 4)".length))

  test("field labels and guides take their own styles"):
    val buffer = rendered(
      Inspect(
        PV.Product("P", Seq("a" -> PV.Atom("1"), "b" -> PV.Seq("L", Seq(PV.Atom("2"), PV.Atom("3"))))),
        inlineWidth = 0,
        labelStyle = Style.Default.bold,
        guideStyle = Style.Default.dim,
      ),
      12,
      5,
    )
    assert(buffer.get(0, 1).style == Style.Default.dim)
    assert(buffer.get(3, 1).style == Style.Default.bold)
    assert(buffer.get(6, 1).style == Style.Default)

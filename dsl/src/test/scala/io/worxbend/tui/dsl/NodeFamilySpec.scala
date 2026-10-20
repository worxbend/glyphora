package io.worxbend.tui.dsl

import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

/** Keep the moved nodes with their layout/decorator families; behavioral suites test the factories unchanged. */
final class NodeFamilySpec extends AnyFunSuite:
  test("focus groups and wrappers live in their node families"):
    val root      = DocumentationSources.repoRoot.getOrElse(cancel("not running from a checkout"))
    val directory = root.resolve("dsl/src/main/scala/io/worxbend/tui/dsl")
    val families  = Map(
      "FocusGroupElement"         -> "NavigationElements.scala",
      "DismissibleOverlayElement" -> "ElementDecorators.scala",
      "EffectedElement"           -> "ElementDecorators.scala",
      "StyleScopeElement"         -> "ElementDecorators.scala",
    )
    families.foreach { (name, file) =>
      assert(Files.readString(directory.resolve(file)).contains(s"final case class $name"), s"$name belongs in $file")
    }

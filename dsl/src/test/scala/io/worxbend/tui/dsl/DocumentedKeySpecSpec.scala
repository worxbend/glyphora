package io.worxbend.tui.dsl

import io.worxbend.tui.core.KeyEvent

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/** The `binding("+", "increment")(…)` in the README and the getting-started guide threw `IllegalArgumentException` from
  * a field initializer — every reader who copied the documented counter app got an `ExceptionInInitializerError` before
  * the first frame. Nothing caught it because published prose is not compiled.
  *
  * This suite extracts every `binding("…", …)` spec from the Markdown that ships to the site and the Wiki and asserts
  * it parses — as a `KeySequence`, the vocabulary `binding` reads, so `"g g"` is as valid as `"q"`. It is a spec-level
  * check, not a compile of the snippets, so it cannot catch every documentation defect — but the class of defect it
  * does catch is the one that reached users.
  */
final class DocumentedKeySpecSpec extends AnyFunSuite:

  /** Matches the spec literal in `binding("ctrl+s", "save")(…)` — the first string argument only. */
  private val BindingSpec = """binding\("((?:[^"\\]|\\.)*)"""".r

  /** The multi-key form, `binding(Seq("down", "j"), "next")(…)`: the whole `Seq(…)` argument, whose string literals are
    * then pulled out by [[StringLiteral]]. Every one of them is a key spec, unlike the single-key form where only the
    * first argument is.
    */
  private val BindingSpecSeq = """binding\(Seq\(([^)]*)\)""".r

  private val StringLiteral = """"((?:[^"\\]|\\.)*)"""".r

  /** Every key spec on one line of documentation, from either `binding` form. */
  private def specsOn(line: String): Iterator[String] =
    BindingSpec.findAllMatchIn(line).map(_.group(1)) ++
      BindingSpecSeq
        .findAllMatchIn(line)
        .flatMap(argument => StringLiteral.findAllMatchIn(argument.group(1)).map(_.group(1)))

  test("every key spec published in the documentation parses"):
    val root  = DocumentationSources.repoRoot
      .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))
    val found =
      for
        page <- DocumentationSources.markdownSources(root)
        line <- Files.readAllLines(page).asScala
        spec <- specsOn(line)
      yield (root.relativize(page).toString, spec)

    assert(found.nonEmpty, "found no documented bindings — the extraction pattern or the doc paths are wrong")

    val broken = found.collect:
      case (page, spec) if KeySequence.parse(spec).isLeft =>
        s"$page: binding(\"$spec\") -> ${KeySequence.parse(spec).left.getOrElse("")}"
    assert(broken.isEmpty, s"documented key specs that throw at declaration time:\n${broken.mkString("\n")}")

  /** The specific specs the counter app in the README and the getting-started guide declares. Pinned by value so a
    * rewrite of those pages that drops the `+`/`-` pair still leaves this suite honest about what it covers.
    */
  test("the documented counter app's own specs parse"):
    Seq("+", "-", "q").foreach: spec =>
      assert(KeyEvent.parse(spec).isRight, s"the documented counter app cannot declare binding(\"$spec\")")

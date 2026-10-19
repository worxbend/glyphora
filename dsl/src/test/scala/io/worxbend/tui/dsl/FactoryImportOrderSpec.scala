package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/** Keeps the first import of `ElementFactories.scala` out of every exported factory's signature, which is what stops an
  * incremental compile of `dsl` from failing with "Cyclic reference involving val <import>".
  *
  * The mechanism, reproduced with the compiler on just `ElementFactories.scala` and `dsl.scala`, the pair Zinc
  * recompiles when a change to the factories' bodies arrives with a change to the export block:
  *
  *   1. The typer reaches the file's first import and resolves its first segment, `io`. Inside package `dsl` that means
  *      asking the package for a member named `io`, which completes the package's top-level definitions — `dsl.scala`'s
  *      `export Element.{…}` among them.
  *   1. Elaborating that export needs every exported factory's signature, default arguments included, and those are
  *      written with names this very file imports.
  *   1. If one of those names comes from the import still being completed in step 1, the compiler reports the cycle.
  *
  * When `Element.scala` or `dsl.scala` happens to be typed first the package is completed from there instead and the
  * build is green, which is why the failure only ever showed up on some incremental compiles and a `clean dsl` always
  * cleared it. Opening the file with an import none of the exported signatures use makes step 3 impossible: by the time
  * the export asks for `Style` or `FiniteDuration`, the import that started it is complete and the others are fresh.
  *
  * The same cycle exists in principle for any node file whose constructor the factories' default arguments reach
  * (`ElementProps.scala`, `InteractiveWidgetElement.scala`), but only if that file is typed *before* this one in a run
  * that also holds `dsl.scala`. Zinc hands the compiler its sources in path order, which puts `ElementFactories.scala`
  * ahead of both, and a sweep of every file paired with this one and `dsl.scala` in that order compiles clean.
  */
final class FactoryImportOrderSpec extends AnyFunSuite:

  private val ImportLine  = """(?m)^import\s+(.+)$""".r
  private val ExportBlock = """export Element\.\{([^}]*)\}""".r
  private val Identifier  = """[A-Za-z_][A-Za-z0-9_]*""".r

  private lazy val root = DocumentationSources.repoRoot
    .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))

  private def source(file: String): String =
    Files.readString(root.resolve(s"dsl/src/main/scala/io/worxbend/tui/dsl/$file"))

  /** The names an import clause brings into scope: the selectors of `a.{B, C as D}`, or the last segment of `a.B`. */
  private def importedNames(clause: String): Set[String] =
    val trimmed = clause.trim
    val braces  = trimmed.indexOf('{')
    if braces >= 0 then
      trimmed
        .slice(braces + 1, trimmed.lastIndexOf('}'))
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(selector => selector.split("\\s+as\\s+").last.trim)
        .toSet
    else Set(trimmed.split("\\s+as\\s+").last.split('.').last.trim)

  /** Comments and Scaladoc blanked out, so a name mentioned in prose never counts as a use. */
  private def withoutComments(code: String): String =
    code.replaceAll("""(?s)/\*.*?\*/""", " ").replaceAll("""(?m)//.*$""", " ")

  /** Each `def` of the trait with everything up to the `=` that starts its body: parameters, default arguments, `using`
    * clauses and result type — everything the export has to know before it can forward the method.
    */
  private def signatures(code: String): Map[String, String] =
    val Def = """(?m)^  def ([A-Za-z_][A-Za-z0-9_]*)""".r
    Def
      .findAllMatchIn(code)
      .map { found =>
        var depth = 0
        var at    = found.end
        var done  = false
        while !done && at < code.length do
          code.charAt(at) match
            case '(' | '[' | '{'                                                  => depth += 1
            case ')' | ']' | '}'                                                  => depth -= 1
            case '=' if depth == 0 && at + 1 < code.length && code(at + 1) != '>' => done = true
            case _                                                                => ()
          if !done then at += 1
        found.group(1) -> code.slice(found.start, at)
      }
      .toVector
      .groupMapReduce(_._1)(_._2)(_ + "\n" + _)

  test("the first import of ElementFactories.scala names nothing an exported factory's signature uses"):
    val code      = source("ElementFactories.scala")
    val first     =
      ImportLine.findFirstMatchIn(code).map(_.group(1)).getOrElse(fail("ElementFactories.scala has no import"))
    assert(
      !first.startsWith("_root_"),
      "the first import has to resolve `io` through package `dsl` — that lookup is what completes the export first",
    )
    val anchor    = importedNames(first)
    val exported  = ExportBlock
      .findFirstMatchIn(source("dsl.scala"))
      .map(_.group(1).split(",").map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(fail("dsl.scala has no `export Element.{…}` block"))
    val offending = for
      (name, signature) <- signatures(withoutComments(code)).toSeq.sortBy(_._1)
      if exported.contains(name)
      used  = Identifier.findAllIn(signature).toSet
      clash = anchor.intersect(used)
      if clash.nonEmpty
    yield s"$name uses ${clash.mkString(", ")}"
    assert(
      offending.isEmpty,
      s"ElementFactories.scala's first import ($first) is used by exported signatures: ${offending.mkString("; ")}. " +
        "Move an import none of them use to the top — see this suite's Scaladoc for the compiler cycle it prevents",
    )
    assert(exported.sizeIs > 50, s"read only ${exported.size} exported names; the export block moved")

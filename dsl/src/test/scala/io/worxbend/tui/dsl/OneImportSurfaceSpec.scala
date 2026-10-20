package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The one-import rule as a gate rather than a comment.
  *
  * `dsl.scala` promises that a name appearing in the signature of anything the package exports is itself re-exported,
  * so `import io.worxbend.tui.dsl.*` is the whole application-facing surface. The rule was kept by hand, and six names
  * slipped through it: `ChartOptions` was exported while the `LegendConstraints` one of its fields is typed as was not,
  * `ScrollbarOptions` without `ScrollbarSide`, `OrbitSpinnerOptions` without `SpinDirection`, `Style` without the
  * `UnderlineStyle` its `withUnderlineStyle` takes, `Buffer` without the `Cell` its `get` returns, and `Signal` without
  * the `SignalEquality` its constructor and its documented `given` need. [[io.worxbend.tui.dslimport.OneImportSpec]]
  * proves those six compile today; this suite is what keeps the *next* one from being missed.
  *
  * It reads the `export io.worxbend.tui.<module>.{…}` blocks and the `type X = …` aliases out of `dsl.scala`, locates
  * the primary constructor of every exported class in that module's main sources — plus every public class declared in
  * `dsl` itself that is not a DSL node — and asserts that each glyphora type named in a constructor parameter is
  * reachable from the one import: exported, aliased, or defined in package `dsl`. A type that is deliberately left
  * behind its own module import is listed in [[BehindModuleImportByDesign]] with the reason, and that list is checked
  * too, so an entry cannot outlive the parameter that justified it.
  *
  * Scope, stated so the limits are known: constructor parameters of classes plus public method signatures on
  * ElementHost (the embedding boundary), not arbitrary other method signatures and not the parameter lists of enum
  * cases (`RunnerError.Backend(error: BackendError, …)` is covered by `OneImportSpec` as compiled code). DSL node types
  * (`*Element`) and `ElementProps` are excluded on purpose: their constructors are not part of the compatibility
  * contract — the factories and fluent builders are (see [[Element]]).
  *
  * The source is *parsed*, not reflected over: no `java.lang.reflect` anywhere, and this is a test source in any case.
  */
final class OneImportSurfaceSpec extends AnyFunSuite:

  /** Glyphora types that an exported constructor names and that stay behind a module import, each with the reason.
    * Every entry must still be named by some audited constructor, so the list cannot keep a stale exemption.
    */
  private val BehindModuleImportByDesign: Map[String, String] = Map(
    "Field" -> ("`FormSpec`'s constructor is `private[macros]` and its `defaults: Seq[Field[?]]` is `private[tui]`: " +
      "only `deriveForm` builds one, and no view writes a `Field`. It is the derivation's own vocabulary and stays " +
      "behind the macros import with `FormFieldType` and `FieldSpec`, exactly as the forms guide imports them")
  )

  private val ExportBlock = """export io\.worxbend\.tui\.(\w+)\.\{([^}]*)\}""".r
  private val TypeAlias   = """(?m)^type (\w+) = io\.worxbend\.tui\.""".r

  /** A top-level type declaration — `class`, `trait`, `enum`, `type`, with whatever modifiers precede it. */
  private val TypeDeclaration =
    """(?m)^(?:(?:final|sealed|abstract|private|protected|opaque|open)\s+)*(?:case\s+)?(?:class|trait|enum|type)\s+(\w+)""".r

  /** A class declaration's header up to and including the `(` that opens its first parameter clause. */
  private def classHeader(name: String): scala.util.matching.Regex =
    (s"""(?m)^(?:(?:final|sealed|abstract|private|protected|open)\\s+)*(?:case\\s+)?class\\s+${java.util.regex.Pattern
        .quote(name)}""" +
      """(\[[^\]]*\])?\s*(?:private(?:\[\w+\])?\s*|protected(?:\[\w+\])?\s*)?\(""").r

  private val Modules = Seq("core", "terminal", "widgets", "runtime", "macros", "dsl")

  /** One constructor parameter the parser found: where it lives and the glyphora type names its type mentions. */
  private final case class Parameter(owner: String, module: String, text: String, glyphoraTypes: Set[String])

  private def mainSources(root: Path, module: String): Seq[Path] =
    val dir = root.resolve(s"$module/src/main/scala/io/worxbend/tui/${module.replace("-", "")}")
    if Files.isDirectory(dir) then
      Using.resource(Files.list(dir))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)
    else Vector.empty

  private def readAll(paths: Seq[Path]): String = paths.map(Files.readString).mkString("\n")

  private def dslSource(root: Path): String =
    Files.readString(root.resolve("dsl/src/main/scala/io/worxbend/tui/dsl/dsl.scala"))

  /** The names each `export io.worxbend.tui.<module>.{…}` block re-exports, keyed by module. */
  private def exportedByModule(dsl: String): Map[String, Seq[String]] =
    ExportBlock
      .findAllMatchIn(dsl)
      .map(block => block.group(1) -> block.group(2).split(",").iterator.map(_.trim).filter(_.nonEmpty).toVector)
      .toVector
      .groupMapReduce(_._1)(_._2)(_ ++ _)

  private def aliased(dsl: String): Set[String] = TypeAlias.findAllMatchIn(dsl).map(_.group(1)).toSet

  private def declaredTypes(source: String): Set[String] = TypeDeclaration.findAllMatchIn(source).map(_.group(1)).toSet

  /** The index of the `)` that closes the clause opened just before `from`. */
  private def closingParen(source: String, from: Int): Int =
    var depth = 1
    var index = from
    while depth > 0 && index < source.length do
      source.charAt(index) match
        case '(' | '[' | '{' => depth += 1
        case ')' | ']' | '}' => depth -= 1
        case _               => ()
      index += 1
    index - 1

  private def stripComments(text: String): String =
    text.replaceAll("""(?s)/\*.*?\*/""", "").replaceAll("""//[^\n]*""", "")

  /** The parameter list split on the commas that separate parameters, not the ones inside a type or a default. */
  private def splitTopLevel(parameters: String): Seq[String] =
    val parts   = Vector.newBuilder[String]
    val current = StringBuilder()
    var depth   = 0
    parameters.foreach { char =>
      char match
        case '(' | '[' | '{' => depth += 1
        case ')' | ']' | '}' => depth -= 1
        case _               => ()
      if char == ',' && depth == 0 then
        parts += current.result()
        current.clear()
      else current.append(char)
    }
    val last    = current.result()
    if last.trim.nonEmpty then parts += last
    parts.result().map(_.trim).filter(_.nonEmpty)

  /** The type of one `name: Type = default` parameter — what follows the first top-level `:` and precedes the first
    * top-level `=` that is not the `=>` of a function type.
    */
  private def typeOf(parameter: String): String =
    var depth = 0
    var colon = -1
    var cut   = parameter.length
    var index = 0
    while cut == parameter.length && index < parameter.length do
      parameter.charAt(index) match
        case '(' | '[' | '{'                 => depth += 1
        case ')' | ']' | '}'                 => depth -= 1
        case ':' if depth == 0 && colon < 0  => colon = index
        case '=' if depth == 0 && colon >= 0 =>
          val next     = if index + 1 < parameter.length then parameter.charAt(index + 1) else ' '
          val previous = parameter.charAt(index - 1)
          if next != '>' && next != '=' && previous != '=' && previous != '<' && previous != '>' && previous != '!' then
            cut = index
        case _                               => ()
      index += 1
    if colon < 0 then "" else parameter.slice(colon + 1, cut)

  /** Every capitalised identifier in a type, which is where a glyphora type name can appear: `Option[Seq[Style]]`,
    * `(Seq[String], Int) => Style`, `w.ChartOptions`, `Map[LocalDate, Style]`.
    */
  private def typeNames(tpe: String): Set[String] = """\b[A-Z]\w*\b""".r.findAllIn(tpe).toSet

  /** The parameters of `name`'s primary constructor — every clause, so a trailing `(using …)` is read too — with the
    * class's own type parameters removed from the names its types mention. `None` when `name` is not a class with a
    * constructor in `source` (an enum, a trait, an object, a type alias).
    */
  private def constructorParameters(source: String, module: String, name: String): Option[Seq[Parameter]] =
    classHeader(name).findFirstMatchIn(source).map { header =>
      val typeParams = Option(header.group(1)).toSeq
        .flatMap(group => splitTopLevel(group.drop(1).dropRight(1)))
        .map(_.split("[\\s<>:]").head.stripPrefix("+").stripPrefix("-").trim)
        .toSet
      val clauses    = Vector.newBuilder[String]
      var open       = header.end
      var more       = true
      while more do
        val close = closingParen(source, open)
        clauses += source.slice(open, close)
        val rest  = source.drop(close + 1)
        val next  = rest.indexWhere(!_.isWhitespace)
        if next >= 0 && rest.charAt(next) == '(' then open = close + 1 + next + 1 else more = false
      clauses.result().flatMap(clause => splitTopLevel(stripComments(clause))).map { raw =>
        val parameter = raw.replaceAll("""^(?:(?:private|protected|val|var|using|implicit|inline|override)\s+)+""", "")
        Parameter(name, module, parameter, typeNames(typeOf(parameter)) -- typeParams)
      }
    }

  /** Public method signatures on the embedding host, including arguments and result types. Unlike constructor coverage
    * this sees ImageProtocol's getter/setter and render(Frame, ...). Restrict indentation to direct members; private
    * helpers and method bodies are not API. The compiled external-package OneImportSpec backs this parser.
    */
  private def hostMembers(source: String): Seq[Parameter] =
    val clean = stripComments(source)
    "(?m)^  def (\\w+)".r
      .findAllMatchIn(clean)
      .map { method =>
        var index     = method.end
        var depth     = 0
        var done      = false
        while index < clean.length && !done do
          val char = clean.charAt(index)
          char match
            case '(' | '[' | '{'                                         => depth += 1
            case ')' | ']' | '}'                                         => depth -= 1
            case '=' if depth == 0 && clean.lift(index + 1) != Some('>') => done = true
            case _                                                       => ()
          if !done then index += 1
        val signature = clean.slice(method.end, index)
        Parameter(s"ElementHost.${method.group(1)}", "dsl", signature.trim, typeNames(signature))
      }
      .toVector

  private def audit: (Set[String], Seq[Parameter]) =
    val root       = DocumentationSources.repoRoot
      .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))
    val dsl        = dslSource(root)
    val sources    = Modules.map(module => module -> readAll(mainSources(root, module))).toMap
    val exported   = exportedByModule(dsl)
    assert(
      exported.values.map(_.size).sum > 100,
      s"read ${exported.values.map(_.size).sum} names out of the module export blocks in dsl.scala — the blocks moved " +
        "or were renamed, and this suite is no longer checking anything",
    )
    val dslDefined = declaredTypes(sources("dsl"))
    val reachable  = exported.values.flatten.toSet ++ aliased(dsl) ++ dslDefined
    val glyphora   = sources.values.map(declaredTypes).reduce(_ ++ _)

    val exportedCtors = exported.toSeq.flatMap { (module, names) =>
      names.flatMap(name => constructorParameters(sources(module), module, name).toSeq.flatten)
    }
    val dslCtors      = declaredTypes(sources("dsl")).toSeq.sorted
      .filterNot(name => name.endsWith("Element") || name == "ElementProps")
      .flatMap(name => constructorParameters(sources("dsl"), "dsl", name).toSeq.flatten)
    val members       = hostMembers(
      Files.readString(
        root.resolve(
          "dsl/src/main/scala/io/worxbend/tui/dsl/ElementHost.scala"
        )
      )
    )
    assert(members.sizeIs > 20, "ElementHost public-member parser stopped recognising the source")
    val parameters    = (exportedCtors ++ dslCtors ++ members).map(parameter =>
      parameter.copy(glyphoraTypes = parameter.glyphoraTypes.filter(glyphora.contains))
    )
    assert(
      parameters.sizeIs > 100,
      s"parsed ${parameters.size} constructor parameters across the exported classes — the parser no longer " +
        "recognises the sources, and this suite is no longer checking anything",
    )
    (reachable, parameters)

  test("every glyphora type an exported constructor names is reachable from `import io.worxbend.tui.dsl.*`"):
    val (reachable, parameters) = audit
    val unreachable             = parameters
      .flatMap(parameter => parameter.glyphoraTypes.toSeq.sorted.map(tpe => tpe -> parameter))
      .filterNot((tpe, _) => reachable.contains(tpe) || BehindModuleImportByDesign.contains(tpe))
      .groupMap(_._1)(_._2)
    assert(
      unreachable.isEmpty,
      "these types are named by constructors the one import exposes, but cannot be written from it:\n" +
        unreachable.toSeq
          .sortBy(_._1)
          .map { (tpe, uses) =>
            s"  $tpe — ${uses.map(use => s"${use.owner}(${use.text}) in tui-${use.module}").distinct.mkString("; ")}"
          }
          .mkString("\n") +
        "\nAdd each to its module's export block in dsl.scala (alphabetical position; a type alias plus a value alias " +
        "for an opaque type), or to BehindModuleImportByDesign with the reason it stays behind a module import.",
    )

  test("every type left behind a module import by design is still named by an audited constructor"):
    val (reachable, parameters) = audit
    val named                   = parameters.flatMap(_.glyphoraTypes).toSet
    BehindModuleImportByDesign.keys.toSeq.sorted.foreach { tpe =>
      assert(
        named.contains(tpe),
        s"`$tpe` is in BehindModuleImportByDesign but no audited constructor names it any more",
      )
      assert(!reachable.contains(tpe), s"`$tpe` is in BehindModuleImportByDesign and is also exported — drop the entry")
    }

  test("the parser reads a representative constructor the way the compiler does"):
    val source =
      """/** `Probe(x = 1)` in a doc comment. */
        |final class Probe[K, +V] private (
        |    val keyed: Map[K, V],                       // a type parameter is not a glyphora type
        |    styleFor: (Seq[String], Int) => Style = (_, _) => Style.Default,
        |    side: ScrollbarSide = ScrollbarSide.Far,
        |    legend: Option[LegendConstraints] = None,
        |)(using equality: SignalEquality[V]):
        |  def ignored(cell: Cell): Unit = ()
        |
        |final case class Plain(a: Int = 1, b: Int = 2)
        |
        |enum NotAClass:
        |  case One(style: Style)
        |""".stripMargin
    val probe  = constructorParameters(source, "test", "Probe").getOrElse(fail("Probe not found"))
    assert(
      probe.map(_.glyphoraTypes) == Seq(
        Set("Map"),
        Set("Seq", "String", "Int", "Style"),
        Set("ScrollbarSide"),
        Set("Option", "LegendConstraints"),
        Set("SignalEquality"),
      )
    )
    assert(constructorParameters(source, "test", "Plain").exists(_.sizeIs == 2))
    assert(constructorParameters(source, "test", "NotAClass").isEmpty)

  test("the host-member parser sees arguments and result types but ignores private helpers"):
    val methods = hostMembers("""final class ElementHost:
      |  def imageProtocol: Option[ImageProtocol] = None
      |  def imageProtocol_=(protocol: Option[ImageProtocol]): Unit = ()
      |  def render(frame: Frame, view: View)(using scope: ReactiveScope): Unit = ()
      |  private def ignored(secret: Hidden): Unit = ()
      |""".stripMargin)
    assert(methods.map(_.owner) == Seq("ElementHost.imageProtocol", "ElementHost.imageProtocol_", "ElementHost.render"))
    assert(methods.head.glyphoraTypes == Set("Option", "ImageProtocol"))
    assert(methods.last.glyphoraTypes == Set("Frame", "View", "ReactiveScope", "Unit"))

  test("the published prose never pattern-matches a DSL node type"):
    // R1-03: node constructors, `copy` and extractors are not part of the compatibility contract — the factories and
    // fluent builders are — so a documented `case TextElement(…) =>` would teach a shape that may stop compiling.
    val root = DocumentationSources.repoRoot
      .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))
    val hits = DocumentationSources
      .markdownSources(root)
      .flatMap(page =>
        Files.readAllLines(page).asScala.zipWithIndex.collect {
          case (line, index) if """\bcase \w+Element[(\[]""".r.findFirstIn(line).isDefined =>
            s"$page:${index + 1}: $line"
        }
      )
    assert(
      hits.isEmpty,
      "these pages pattern-match a DSL node type, which is not a compatibility surface:\n" + hits.mkString("\n"),
    )

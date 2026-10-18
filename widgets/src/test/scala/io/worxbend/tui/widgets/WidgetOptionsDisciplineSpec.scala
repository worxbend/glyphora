package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** CLAUDE.md "Adding a widget" §1 says what happens to a widget whose optional knobs keep multiplying: once the list
  * grows past a handful, everything past the arguments that identify the widget moves into a dedicated `…Options` case
  * class in the same file — `Chart`, `DataTable`, `Scrollbar` and `OrbitSpinner` are the shape. A rule nothing checks
  * is a rule the next widget quietly breaks, which is how `BarChart` reached twelve defaulted parameters with the
  * behaviour knobs filed after the glyph set.
  *
  * This suite reads every widget constructor out of the main sources and counts the defaulted primary-constructor
  * parameters of each `final case class` that extends `Widget` or `StatefulWidget`. More than [[MaxDefaultedKnobs]]
  * without an options bundle fails the build, unless the class is named in [[DeferredWithReason]] — and an entry there
  * is itself checked, so a bundle that has since landed cannot leave a stale exemption behind.
  *
  * The source is *parsed*, not reflected over: no `java.lang.reflect` anywhere, and this is a test source in any case.
  */
final class WidgetOptionsDisciplineSpec extends AnyFunSuite:

  /** The most defaulted knobs a widget may carry on its own constructor before they bundle. `Sparkline` sits exactly on
    * the line with eight; the three widgets that were over it when this suite was written each carried eleven or
    * twelve.
    */
  private val MaxDefaultedKnobs = 8

  /** Widgets allowed over the line, each with the reason the bundle is deferred. Every name here must still exist and
    * must still exceed the threshold, so the list cannot outlive the exemption it records.
    */
  private val DeferredWithReason: Map[String, String] = Map(
    "Block" -> ("the most-used widget in the docs and the examples: ten knobs, every one of them named at hundreds " +
      "of call sites, so the bundle waits for the next parameter `Block` genuinely needs rather than landing as churn")
  )

  /** A widget constructor as the parser sees it: the class name and how many of its parameters carry a default. */
  private final case class Constructor(name: String, defaultedKnobs: Int, hasOptionsBundle: Boolean)

  private val ClassHeader   = """final case class (\w+)(?:\[[^\]]*\])?\(""".r
  private val ExtendsClause = """\A\s*extends\s+([^:]+):""".r

  /** The characters that turn a following `=` into a comparison or an arrow rather than a default. */
  private val ComparisonLead = Set('=', '<', '>', '!')

  /** The test's working directory is not guaranteed, so find the checkout by walking up to the build file. `None` means
    * the suite is not running from a checkout at all, which is a reason to cancel rather than to fail.
    */
  private def repoRoot: Option[Path] =
    Iterator
      .iterate(Option(Paths.get("").toAbsolutePath))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.isRegularFile(candidate.resolve("build.mill")))

  private def widgetSources(root: Path): Seq[Path] =
    val dir = root.resolve("widgets/src/main/scala/io/worxbend/tui/widgets")
    Using.resource(Files.list(dir))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toVector.sorted)

  /** Every `final case class` in `source` whose extends clause names `Widget` or `StatefulWidget`. */
  private def widgetConstructors(source: String): Seq[Constructor] =
    ClassHeader
      .findAllMatchIn(source)
      .flatMap { header =>
        val close      = closingParen(source, header.end)
        val parameters = source.slice(header.end, close)
        val tail       = source.slice(close + 1, close + 400)
        ExtendsClause
          .findFirstMatchIn(tail)
          .map(_.group(1))
          .filter(clause => """\b(StatefulWidget|Widget)\b""".r.findFirstIn(clause).isDefined)
          .map { _ =>
            val params = splitTopLevel(stripComments(parameters))
            Constructor(
              header.group(1),
              params.count(isDefaulted),
              params.exists(param => """:\s*\w*Options\b""".r.findFirstIn(param).isDefined),
            )
          }
      }
      .toVector

  /** The index of the `)` that closes the parameter list opened just before `from`. */
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

  /** Line comments and block comments removed, so a `=` or a `,` inside a remark is not read as syntax. */
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

  /** A parameter carries a default when a bare `=` — not the `=>` of a function type or a lambda — appears at the top
    * nesting level after its type.
    */
  private def isDefaulted(parameter: String): Boolean =
    var depth = 0
    var found = false
    var index = 0
    while !found && index < parameter.length do
      parameter.charAt(index) match
        case '(' | '[' | '{' => depth += 1
        case ')' | ']' | '}' => depth -= 1
        case '='             =>
          val next     = if index + 1 < parameter.length then parameter.charAt(index + 1) else ' '
          val previous = if index > 0 then parameter.charAt(index - 1) else ' '
          found = depth == 0 && next != '>' && next != '=' && !ComparisonLead.contains(previous)
        case _               => ()
      index += 1
    found

  private def constructors: Seq[Constructor] =
    val root = repoRoot.getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))
    val all  = widgetSources(root).flatMap(path => widgetConstructors(Files.readString(path)))
    assert(
      all.sizeIs > 30,
      s"found ${all.size} widget constructors under widgets/src/main — the sources moved or the parser no longer " +
        "recognises them, and this suite is no longer checking anything",
    )
    all

  test("no widget keeps more than eight defaulted knobs on its own constructor without an options bundle"):
    val over = constructors
      .filter(ctor => ctor.defaultedKnobs > MaxDefaultedKnobs && !ctor.hasOptionsBundle)
      .filterNot(ctor => DeferredWithReason.contains(ctor.name))
    assert(
      over.isEmpty,
      "these widgets carry more defaulted constructor parameters than CLAUDE.md \"Adding a widget\" §1 allows:\n" +
        over.map(ctor => s"  ${ctor.name}: ${ctor.defaultedKnobs} defaulted parameters").mkString("\n") +
        s"\nMove everything past the arguments that identify the widget into a `${over.headOption.fold("X")(_.name)}" +
        "Options` case class in the same file (see `OrbitSpinnerOptions`), keeping every default, or add the name to " +
        "DeferredWithReason with the reason the bundle waits.",
    )

  test("every deferred widget still exists and still needs its exemption"):
    val byName = constructors.map(ctor => ctor.name -> ctor).toMap
    DeferredWithReason.keys.toSeq.sorted.foreach { name =>
      val ctor = byName.getOrElse(name, fail(s"`$name` is in DeferredWithReason but no widget of that name exists"))
      assert(
        ctor.defaultedKnobs > MaxDefaultedKnobs && !ctor.hasOptionsBundle,
        s"`$name` has ${ctor.defaultedKnobs} defaulted parameters" +
          (if ctor.hasOptionsBundle then " and an options bundle" else "") +
          " — it no longer needs its DeferredWithReason entry; remove the stale exemption.",
      )
    }

  test("the parser reads a representative constructor the way the compiler does"):
    // A function-typed parameter with a lambda default, a type with brackets, and a comment holding a stray `=`: the
    // three shapes a naive split on `=` and `,` gets wrong.
    val source =
      """final case class Probe(
        |    data: Seq[(String, Long)],
        |    // the default is `x = 1` for everyone, so to speak
        |    styleFor: (Int, Long) => Option[Style] = BarStyling.NoOverride,
        |    pair: Map[String, Int] = Map.empty,
        |    plain: Int,
        |) extends Widget:
        |  def render(area: Rect, buffer: Buffer): Unit = ()
        |
        |final case class NotAWidget(a: Int = 1, b: Int = 2)
        |
        |final case class Bundled(data: Seq[Int], options: BundledOptions = BundledOptions()) extends StatefulWidget[S]
        |    with Measured:
        |""".stripMargin
    assert(
      widgetConstructors(source) == Seq(
        Constructor("Probe", defaultedKnobs = 2, hasOptionsBundle = false),
        Constructor("Bundled", defaultedKnobs = 1, hasOptionsBundle = true),
      )
    )

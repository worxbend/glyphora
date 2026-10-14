package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Mechanical enforcement of rules this project states in prose and, until now, checked nowhere.
  *
  * The repository already trusts this shape: `ci.yml` greps every main source for `java.lang.reflect` and for
  * `String.substring`, each with a named exemption and the argument for it written down, and
  * [[DocumentedFactorySpec]]/[[DocumentedKeySpecSpec]] walk the checkout from a test to hold the published prose to the
  * code. What those checks have in common is that the rule they enforce is one a reviewer would otherwise have to
  * remember — and the failures they catch are silent: nothing is red, the suite is simply not testing what its name
  * claims. Each rule below is one this repository has already been bitten by:
  *
  *   - a suite that asserts nothing still reports as a passing test. Twenty-one performance probes were written as
  *     `*Spec` classes with a `test(…)` body and no assertion in it, and every one of them was green for as long as it
  *     existed. They have since been deleted; this is the gate that stops the next twenty.
  *   - `println` from a suite writes to the forked JVM's stdout rather than into the run's report, where `info(…)` puts
  *     it.
  *   - the seven published modules take their coordinates from one `publishVersion`. A module that overrode it, or a
  *     README that offered an older one, would be found by whoever tried the install instructions.
  *   - a bare `toLowerCase`, or `%d`/`%.1f` through the `f` interpolator, reads the *ambient* locale: `"Insert"` folds
  *     to `"ınsert"` under `tr-TR` and matches nothing, and `4.1` prints as `"4,1"` across much of Europe. Eight of
  *     those were fixed before this suite existed, each one found by hand.
  *   - `GLYPHORA_GOLDEN_UPDATE` turns every `GoldenFrames.assertMatches` in the process from a comparison into a
  *     recording. A run that leaks it is a run where the golden suites pass without looking at anything.
  *
  * It lives in `dsl.test` and scans the whole checkout rather than sitting once per module, for the same reason
  * [[GoldenFixtureDisciplineSpec]] does: one suite that walks the repository cannot be forgotten when a new module is
  * added. It reads sources as text — no `java.lang.reflect`, and no `String.substring` either, which is the rule
  * [[scan]] would otherwise be the most tempted by.
  */
final class DisciplineSpec extends AnyFunSuite:

  // ---------------------------------------------------------------------------------------------------------------
  // What the rules match
  // ---------------------------------------------------------------------------------------------------------------

  /** A ScalaTest suite declaration. Matched on the `extends` clause rather than on the class name, so a file called
    * `Fixtures.scala` that happens to declare a suite is still held to the rules, and `Bench.scala`-style objects with
    * a `main` — which are not suites and are not run by the test runner — are correctly left out.
    */
  private val SuiteDeclaration =
    ("""\bextends\b[^\n]*\b(?:AnyFunSuite|AnyFlatSpec|AnyFunSpec|AnyFreeSpec|AnyWordSpec""" +
      """|AnyPropSpec|AnyFeatureSpec|AsyncFunSuite)\b""").r

  /** Anything that can fail a test: ScalaTest's own `assert`, `assertResult`, `assertThrows`, `assertCompiles` and
    * `assertDoesNotCompile`, `intercept`, the `Matchers` spelling `shouldBe`/`should`, and this repository's own
    * `assertRendered`/`assertMatches` helpers — which is why the prefix is matched rather than the exact five names.
    */
  private val AssertionCall = """\b(?:assert[A-Za-z]*|intercept|shouldBe|should)\b""".r

  private val ConsolePrint = """\b(?:System\.(?:out|err)\.print|print|println)\s*\(""".r

  private val PublishVersionDeclaration = """def\s+publishVersion\b""".r

  /** A coordinate a reader can paste: `com.worxbend::tui-dsl:0.18.3` or `"com.worxbend" %% "tui-dsl" % "0.18.3"`. */
  private val PublishedCoordinate =
    """com\.worxbend(?:"\s*%%\s*"|::)(tui-[a-z-]+)(?:"\s*%\s*"|:)([0-9][0-9A-Za-z.+-]*)""".r

  /** `.toLowerCase` / `.toUpperCase` with no argument — including the explicit `()` overload.
    *
    * `Character.toLowerCase(codePoint)` and `Character.toUpperCase(codePoint)` are exempt without needing to be named:
    * they take an argument, so the lookahead already excludes them, and the codepoint overloads are locale-independent
    * in any case.
    */
  private val BareCaseFold = """\.(?:toLowerCase|toUpperCase)\s*(?:\(\s*\))?(?!\s*\()""".r

  /** `String.format(` and whatever its first argument starts with, up to the comma. */
  private val StringFormatCall = """String\.format\s*\(\s*([^,()\n]*)""".r

  /** A format specifier `java.util.Formatter` localizes: `d` takes the locale's zero digit and grouping separator,
    * `f`/`e`/`g` its decimal separator. `%x`, `%o` and `%s` are not localized, so `f"#$r%02x$g%02x$b%02x"` in
    * `core.Color` is not a violation and must not be reported as one.
    */
  private val LocaleSensitiveConversion = """%[-#+ 0,(]*\d*(?:\.\d+)?[dfeEgG]""".r

  // ---------------------------------------------------------------------------------------------------------------
  // Exemptions. Each entry carries the argument for it, the way `ci.yml` argues for `examples/weather/…/Json.scala`.
  // ---------------------------------------------------------------------------------------------------------------

  /** Suites allowed to write to the console.
    *
    * `LayoutCharacterisationSpec` prints one line, and only from its recording branch — the branch that runs solely
    * when `GLYPHORA_GOLDEN_UPDATE` is set, which the last test in this suite forbids for a verification run. What it
    * prints is "no comparison was made", which is precisely the fact a green run must not hide; `info(…)` would bury it
    * in a report nobody reads when they are regenerating a fixture by hand.
    */
  private val MayPrint: Set[String] =
    Set("core/src/test/scala/io/worxbend/tui/core/LayoutCharacterisationSpec.scala")

  /** Main sources allowed a locale-sensitive call.
    *
    * Empty on purpose: every case fold and every number format in the checkout passes an explicit locale today. A new
    * entry here has to say why *this* string is never compared against an identifier and never drawn — the argument
    * `examples/weather/…/Json.scala` makes for its exemption from the `substring` grep. Relaxing the patterns above
    * instead would quietly re-open all eight of the bugs this rule exists for.
    */
  private val AmbientLocaleByDesign: Set[String] = Set.empty

  // ---------------------------------------------------------------------------------------------------------------
  // The rules
  // ---------------------------------------------------------------------------------------------------------------

  test("every ScalaTest suite in the checkout asserts something"):
    assert(
      testSuites.sizeIs > 200,
      s"found ${testSuites.size} ScalaTest suites under $checkout — the `extends` clause stopped matching, so this " +
        "check is no longer looking at anything",
    )

    val silent = testSuites.collect {
      case (file, scanned) if AssertionCall.findFirstIn(scanned.code).isEmpty => relative(checkout, file)
    }
    assert(
      silent.isEmpty,
      "these suites declare tests and assert nothing, so they pass against a correct and a broken implementation " +
        "alike:\n" + silent.map(name => s"  $name").mkString("\n") +
        "\nGive each one a real assertion, or — if it is a performance probe — make it an object with a `main` built " +
        "on `testsupport.Bench`, which the test runner does not run.",
    )

  test("no ScalaTest suite prints to the console"):
    val printing = testSuites.flatMap { (file, scanned) =>
      val name = relative(checkout, file)
      if MayPrint(name) then Seq.empty
      else ConsolePrint.findAllMatchIn(scanned.code).map(at => s"  $name:${lineAt(scanned, at.start)}").toVector
    }
    assert(
      printing.isEmpty,
      "these suites write to the forked JVM's console instead of the run's report:\n" + printing.mkString("\n") +
        "\nUse `info(…)`, which ScalaTest captures and attributes to the test that produced it, or add the file to " +
        "MayPrint with the argument for it.",
    )

  test("the seven published modules share one publishVersion"):
    assert(
      declaredVersions.nonEmpty,
      s"no build file under $checkout declares publishVersion — `TuiPublishModule` moved and this check is vacuous",
    )

    val distinct = declaredVersions.map(_._2).distinct
    assert(
      distinct.sizeIs == 1,
      "the published modules disagree about the version, so a release would ship mismatched coordinates:\n" +
        declaredVersions.map((file, version) => s"  $file declares $version").mkString("\n"),
    )

    val notPublishing = PublishedModules.filterNot { module =>
      val file = checkout.resolve(module).resolve("package.mill")
      Files.isRegularFile(file) && scan(Files.readString(file)).code.contains("TuiPublishModule")
    }
    assert(
      notPublishing.isEmpty,
      "these modules no longer extend TuiPublishModule, so they inherit neither its POM nor its single version: " +
        notPublishing.mkString(", "),
    )

  test("the published prose offers the version that is actually published"):
    val version     = publishedVersion
    val coordinates = DocumentationSources.markdownSources(checkout).flatMap { page =>
      val name = relative(checkout, page)
      PublishedCoordinate.findAllMatchIn(Files.readString(page)).map(at => (name, at.group(1), at.group(2))).toVector
    }
    assert(
      coordinates.sizeIs >= 5,
      s"found ${coordinates.size} `com.worxbend::tui-*` coordinates in the published prose — the install snippets " +
        "moved or were reworded, and this check is no longer reading them",
    )

    val drifted = coordinates.collect {
      case (page, artifact, quoted) if quoted != version => s"  $page offers $artifact:$quoted"
    }
    assert(
      drifted.isEmpty,
      s"the published prose hands readers coordinates at a version nobody publishes (publishVersion is $version):\n" +
        drifted.mkString("\n"),
    )

  test("no main source folds case or formats a number through the ambient locale"):
    val sources = sourceRoots("main").flatMap(scalaFilesUnder)
    assert(
      sources.sizeIs > 150,
      s"found ${sources.size} main sources under $checkout — the module layout changed and this check is no longer " +
        "reading most of the code it is meant to cover",
    )

    val violations = sources.flatMap { file =>
      val name = relative(checkout, file)
      if AmbientLocaleByDesign(name) then Seq.empty else ambientLocaleUses(name, scan(Files.readString(file)))
    }
    assert(
      violations.isEmpty,
      "these main sources read the ambient locale, so their output depends on the machine that runs them:\n" +
        violations.mkString("\n") +
        "\nPass `Locale.ROOT` explicitly — `toLowerCase(Locale.ROOT)`, `String.format(Locale.ROOT, …)` instead of " +
        "the `f` interpolator — or add the file to AmbientLocaleByDesign with the argument for it.",
    )

  test("GLYPHORA_GOLDEN_UPDATE is unset, so the golden suites are comparing rather than recording"):
    val recording = Option(System.getenv("GLYPHORA_GOLDEN_UPDATE")).filter(_.nonEmpty)
    assert(
      recording.isEmpty,
      s"GLYPHORA_GOLDEN_UPDATE is set to '${recording.getOrElse("")}', which turns every GoldenFrames.assertMatches " +
        "in this JVM into a recording. Every golden suite in the run passed without comparing anything — unset it " +
        "and run again before trusting this result.",
    )

  // ---------------------------------------------------------------------------------------------------------------
  // Locating the sources
  // ---------------------------------------------------------------------------------------------------------------

  /** The seven artifacts `TuiPublishModule` puts on Maven Central, by directory name. */
  private val PublishedModules: Seq[String] =
    Seq("core", "terminal", "widgets", "runtime", "macros", "dsl", "test-support")

  private lazy val checkout: Path =
    DocumentationSources.repoRoot
      .getOrElse(cancel("not running from a checkout: no build.mill above the working directory"))

  private lazy val testSuites: Seq[(Path, Scanned)] =
    sourceRoots("test")
      .flatMap(scalaFilesUnder)
      .map(file => file -> scan(Files.readString(file)))
      .filter((_, scanned) => SuiteDeclaration.findFirstIn(scanned.code).isDefined)

  private lazy val buildFiles: Seq[Path] =
    (checkout.resolve("build.mill") +: modules.map(_.resolve("package.mill"))).filter(Files.isRegularFile(_))

  /** Every `def publishVersion = "…"` the checkout's build files declare, as `(build file, version)`.
    *
    * The declaration is found in the *blanked* source, so a version quoted in a comment is not mistaken for one — and
    * the value is then read back out of the literal the scanner recorded on that line, because blanking is exactly what
    * removed it from the text the pattern matched against.
    */
  private lazy val declaredVersions: Seq[(String, String)] =
    buildFiles.flatMap { file =>
      val scanned = scan(Files.readString(file))
      val name    = relative(checkout, file)
      PublishVersionDeclaration
        .findAllMatchIn(scanned.code)
        .map { at =>
          val line    = lineAt(scanned, at.start)
          val version = scanned.literals.find(_.line == line).map(_.content)
          name -> version.getOrElse(
            fail(
              s"$name declares publishVersion on line $line as something other than a plain string literal — this " +
                "check can only compare literals, so it would silently stop covering that module"
            )
          )
        }
        .toVector
    }

  private lazy val publishedVersion: String =
    declaredVersions.map(_._2).distinct.headOption.getOrElse(cancel("no build file declares publishVersion"))

  /** Every module directory in the checkout, examples included.
    *
    * Examples nest one level deeper than the seven library modules, and a glob of one module directory plus
    * `src/main/scala` expands only to the top-level ones — the blind spot that kept the examples exempt from `ci.yml`'s
    * two greps for as long as they were written that way. The one-level-deeper children are looked at for exactly that
    * reason.
    */
  private def modules: Seq[Path] =
    val top = directChildren(checkout).filterNot { child =>
      val name = child.getFileName.toString
      name.startsWith(".") || name == "out" || name == "node_modules"
    }
    top ++ top.flatMap(directChildren)

  private def sourceRoots(kind: String): Seq[Path] =
    modules.map(_.resolve(s"src/$kind/scala")).filter(Files.isDirectory(_))

  private def directChildren(directory: Path): Seq[Path] =
    if !Files.isDirectory(directory) then Seq.empty
    else Using.resource(Files.list(directory))(_.iterator.asScala.filter(Files.isDirectory(_)).toVector)

  private def scalaFilesUnder(directory: Path): Seq[Path] =
    Using.resource(Files.walk(directory)) { paths =>
      paths.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".scala"))
        .toVector
    }

  private def relative(root: Path, file: Path): String =
    root.relativize(file).toString.replace(java.io.File.separatorChar, '/')

  // ---------------------------------------------------------------------------------------------------------------
  // Reading Scala as text
  // ---------------------------------------------------------------------------------------------------------------

  /** One string literal as [[scan]] found it: the line its opening quote is on, its interpolator prefix (`""` for a
    * plain literal), its text with every `${…}` interpolation standing in as a single `0`, and whether `.format(` is
    * applied to it directly.
    *
    * The `0` is what makes `s"%.${decimals}f".format(value)` still read as a precision rather than as `%.f`, which
    * matches nothing — that call is the exact shape the airsensor example had.
    */
  private final case class Literal(line: Int, prefix: String, content: String, formatted: Boolean)

  /** A Scala source split into the two things the rules can reason about.
    *
    * `code` is the source with every comment and every string literal's *content* blanked to spaces, the same length
    * and with the same line breaks, so an offset into it still names the right line of the original. Both directions of
    * that blanking matter: `core.KeyEvent`'s comment spells out that `"Insert".toLowerCase` is wrong and would
    * otherwise be read as a call that *is* wrong, and a `"https://…"` inside a literal would otherwise be read as the
    * start of a comment.
    */
  private final case class Scanned(code: String, literals: Seq[Literal], newlines: Array[Int])

  /** The 1-based line an offset into [[Scanned.code]] falls on. */
  private def lineAt(scanned: Scanned, index: Int): Int =
    val found = java.util.Arrays.binarySearch(scanned.newlines, index)
    (if found >= 0 then found else -found - 1) + 1

  private def ambientLocaleUses(name: String, scanned: Scanned): Seq[String] =
    val folds = BareCaseFold
      .findAllMatchIn(scanned.code)
      .map(at => s"  $name:${lineAt(scanned, at.start)} case fold with no Locale argument")
      .toVector

    val formats = StringFormatCall
      .findAllMatchIn(scanned.code)
      .filterNot(_.group(1).contains("ocale"))
      .map(at => s"  $name:${lineAt(scanned, at.start)} String.format with no Locale as its first argument")
      .toVector

    // Only two shapes carry a format string to a `Formatter` without a locale beside it: the `f` interpolator, and
    // `.format` applied straight to a literal. A literal handed to a helper that supplies `Locale.ROOT` itself — as
    // `procmon` and `loadtest` both do — is correct, and is deliberately not matched here.
    val specifiers = scanned.literals
      .filter(literal => literal.prefix == "f" || literal.formatted)
      .filter(literal => LocaleSensitiveConversion.findFirstIn(literal.content).isDefined)
      .map(literal => s"  $name:${literal.line} locale-sensitive format specifier in `${literal.content}`")

    folds ++ formats ++ specifiers

  /** Blanks comments and string-literal content out of a Scala source, and collects the literals it blanked.
    *
    * Deliberately a lexer and not a parser: it needs to be right about where a comment and a literal begin and end, and
    * about nothing else. The cases it does have to get right are the ones this checkout actually contains — `'"'` as a
    * char literal (`core.Style`, `widgets.SyntaxHighlighter`, the weather example's JSON parser), `"""` literals that
    * end in a run of four or more quotes (`core.KeySpecLiteralSpec`), nested block comments, a `//` inside a URL
    * literal, and a string interpolation whose `${…}` contains another string (`weather.Main`).
    */
  private def scan(source: String): Scanned =
    val length   = source.length
    val out      = new StringBuilder(length)
    val literals = mutable.ArrayBuffer.empty[Literal]
    val newlines = source.indices.filter(source.charAt(_) == '\n').toArray

    def lineOf(index: Int): Int =
      val found = java.util.Arrays.binarySearch(newlines, index)
      (if found >= 0 then found else -found - 1) + 1

    def blank(from: Int, until: Int): Unit =
      var cursor = from
      while cursor < until do
        out.append(if source.charAt(cursor) == '\n' then '\n' else ' ')
        cursor += 1

    /** The interpolator prefix written immediately before an opening quote: `f`, `s`, `raw`, `hex`, or `""`. */
    def prefixBefore(quote: Int): String =
      var start = quote
      while start > 0 && (source.charAt(start - 1).isLetterOrDigit || source.charAt(start - 1) == '_') do start -= 1
      val text  = new StringBuilder
      var at    = start
      while at < quote do
        text.append(source.charAt(at))
        at += 1
      text.toString

    /** A one-character literal, an escape such as `'\n'`, or a six-character unicode escape — and nothing else: a lone
      * `'` in Scala 3 opens a quoted expression (`'{`, `'[`), which is ordinary code. Guessing wrong here leaves the
      * scanner inside a string for the rest of the line, and `'"'` really does appear in main sources.
      */
    def charLiteralEnd(quote: Int): Option[Int] =
      val escaped = quote + 1 < length && source.charAt(quote + 1) == '\\'
      val unicode = escaped && quote + 2 < length && source.charAt(quote + 2) == 'u'
      if unicode && quote + 7 < length && source.charAt(quote + 7) == '\'' then Some(quote + 8)
      else if escaped && quote + 3 < length && source.charAt(quote + 3) == '\'' then Some(quote + 4)
      else if !escaped && quote + 2 < length && source.charAt(quote + 2) == '\'' then Some(quote + 3)
      else None

    def skipCharLiteral(quote: Int): Int =
      charLiteralEnd(quote) match
        case Some(end) =>
          blank(quote, end)
          end
        case None      =>
          out.append(source.charAt(quote))
          quote + 1

    def quoteRunAt(index: Int): Int =
      var end = index
      while end < length && source.charAt(end) == '"' do end += 1
      end - index

    /** Reads the literal whose opening quote is at `quote` and returns the index just past its closing quote. */
    def readLiteral(quote: Int, prefix: String): Int =
      val triple  = quoteRunAt(quote) >= 3
      val content = new StringBuilder
      out.append(if triple then "\"\"\"" else "\"")
      var pos     = quote + (if triple then 3 else 1)
      var open    = true
      while open && pos < length do
        val char = source.charAt(pos)
        if char == '"' then
          val run = quoteRunAt(pos)
          if triple && run >= 3 then
            // A multi-line literal ends at the *last* three quotes of a run, so `hex""""` is `hex""` plus the
            // terminator. Anything less than three is ordinary content.
            blank(pos, pos + run - 3)
            out.append("\"\"\"")
            var extra = run - 3
            while extra > 0 do
              content.append('"')
              extra -= 1
            pos += run
            open = false
          else if triple then
            blank(pos, pos + run)
            var extra = run
            while extra > 0 do
              content.append('"')
              extra -= 1
            pos += run
          else
            out.append('"')
            pos += 1
            open = false
        else if !triple && char == '\\' && pos + 1 < length then
          content.append(char).append(source.charAt(pos + 1))
          blank(pos, pos + 2)
          pos += 2
        else if !triple && char == '\n' then
          // A single-quoted literal cannot span a line, so a mis-scan can never run past one: every line starts
          // again in code.
          open = false
        else if prefix.nonEmpty && char == '$' && pos + 1 < length && source.charAt(pos + 1) == '{' then
          content.append('0')
          blank(pos, pos + 2)
          pos = readInterpolation(pos + 2)
        else
          content.append(char)
          out.append(if char == '\n' then '\n' else ' ')
          pos += 1

      var after = pos
      while after < length && source.charAt(after).isWhitespace do after += 1
      literals += Literal(lineOf(quote), prefix, content.toString, source.startsWith(".format", after))
      pos

    /** The code inside `${ … }`, copied through rather than blanked: a `.toLowerCase` written there is still a call. */
    def readInterpolation(from: Int): Int =
      var depth = 1
      var pos   = from
      while pos < length && depth > 0 do
        val char = source.charAt(pos)
        if char == '"' then pos = readLiteral(pos, prefixBefore(pos))
        else
          depth += (if char == '{' then 1 else if char == '}' then -1 else 0)
          out.append(char)
          pos += 1
      pos

    var index = 0
    while index < length do
      val char = source.charAt(index)
      if source.startsWith("//", index) then
        var end = index
        while end < length && source.charAt(end) != '\n' do end += 1
        blank(index, end)
        index = end
      else if source.startsWith("/*", index) then
        var depth = 1
        var end   = index + 2
        while end < length && depth > 0 do
          if source.startsWith("/*", end) then
            depth += 1
            end += 2
          else if source.startsWith("*/", end) then
            depth -= 1
            end += 2
          else end += 1
        blank(index, end)
        index = end
      else if char == '\'' then index = skipCharLiteral(index)
      else if char == '"' then index = readLiteral(index, prefixBefore(index))
      else
        out.append(char)
        index += 1

    Scanned(out.toString, literals.toSeq, newlines)

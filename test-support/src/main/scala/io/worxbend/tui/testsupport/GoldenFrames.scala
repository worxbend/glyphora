package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Buffer, BufferExport}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import scala.io.Source
import scala.util.Using

/** Full-frame snapshot assertions: a rendered buffer is compared against a text fixture in the module's test resources
  * (`/golden/<name>.txt`).
  *
  * **What a fixture records is layout and glyphs, and nothing else.** The frame is serialised through
  * [[BufferAssertions.text]], which concatenates each cell's symbol, so colours, modifiers and hyperlinks are dropped:
  * a frame that lost every style still matches its fixture byte for byte. Use a golden fixture to pin *where things
  * landed*, and assert styling separately against the buffer's cells. Widening the fixture format to carry a style
  * plane was considered and rejected — it would make every fixture unreadable in review, which is the one thing a text
  * snapshot is for.
  *
  * To (re)generate fixtures, run the tests with `GLYPHORA_GOLDEN_UPDATE=<resources-dir>` — each assertion then writes
  * its actual frame there instead of comparing, and the diff shows up in review like any code change.
  *
  * Comparison ignores trailing line terminators on both sides, so trailing blank rows are not significant: a fixture
  * generated under `GLYPHORA_GOLDEN_UPDATE` always matches the frame it was generated from, and a frame that stops
  * short of the fixture's bottom blank rows still matches. Interior blank rows stay significant.
  *
  * **A styled fixture is the other kind.** [[assertMatchesStyled]] compares the frame's `BufferExport.ansi` export
  * against `golden/<name>.ansi`, so colours, modifiers and links *are* recorded; the price is a fixture that is not
  * readable in review, which is why a mismatch writes a visual report — `<name>.expected.svg`, `<name>.actual.svg` and
  * a side-by-side `<name>.diff.html` with every differing cell outlined — next to the fixture and names the three files
  * in the failure. Use a text golden for "did anything move?" and a styled one for "is the selection still reversed?";
  * the same `GLYPHORA_GOLDEN_UPDATE` records both.
  */
object GoldenFrames:

  private val UpdateEnvVar = "GLYPHORA_GOLDEN_UPDATE"

  /** The subdirectory of a module's test resources that fixtures live in. Shared with [[GoldenFixtures]], which checks
    * the same directory for orphans.
    */
  private[testsupport] val FixtureDirectory: String = "golden"

  /** The file extension fixtures are written with, including the dot. Shared with [[GoldenFixtures]] for the same
    * reason as [[FixtureDirectory]].
    */
  private[testsupport] val FixtureExtension: String = ".txt"

  /** The file extension styled fixtures are written with, including the dot. */
  private[testsupport] val StyledFixtureExtension: String = ".ansi"

  /** Line terminators at the end of a frame, compiled once rather than per comparison. */
  private val TrailingNewlines: Pattern = Pattern.compile("\\R+$")

  /** Compares `buffer` against the `golden/<name>.txt` fixture on the test classpath, or — when
    * `GLYPHORA_GOLDEN_UPDATE` names a test-resources directory — writes the frame there instead of comparing.
    */
  def assertMatches(name: String, buffer: Buffer): Unit =
    recordingDirectory match
      case Some(directory) =>
        announceRecording(name)
        writeFixture(directory, name, buffer)
      case None            =>
        val expected = loadFixture(name, FixtureExtension)
        assertMatchesText(name, buffer, expected)

  /** Compares `buffer`'s styled export against the `golden/<name>.ansi` fixture on the test classpath, or — when
    * `GLYPHORA_GOLDEN_UPDATE` names a test-resources directory — records the export there instead of comparing.
    *
    * On a mismatch the visual report (see the object notes) is written next to the fixture when the fixture is a plain
    * file on disk, which is what a module's `src/test/resources` is under Mill; a fixture read out of a jar has no
    * "next to", so the report goes to the JVM's temporary directory instead. Either way the failure names the paths.
    */
  def assertMatchesStyled(name: String, buffer: Buffer): Unit =
    recordingDirectory match
      case Some(directory) =>
        announceRecording(name)
        writeStyledFixture(directory, name, buffer)
      case None            =>
        val expected = loadFixture(name, StyledFixtureExtension)
        assertMatchesStyledText(name, buffer, expected, reportDirectory(name))

  /** The directory `GLYPHORA_GOLDEN_UPDATE` names, or `None` when fixtures are to be compared.
    *
    * A blank value is not a setting: `export GLYPHORA_GOLDEN_UPDATE=` (or a CI job that leaks the variable empty rather
    * than unset) must not silently drop every golden assertion in the process to a no-op — see the same "empty is not a
    * setting" rule for `LC_ALL` in `terminal.TerminalGlyphs`.
    */
  private def recordingDirectory: Option[Path] = sys.env.get(UpdateEnvVar).filter(_.nonEmpty).map(Path.of(_))

  /** Recording asserts nothing. Say so on stderr, so a run that only rewrote fixtures (a CI job that leaked the
    * environment variable, say) is distinguishable from a run whose golden tests actually compared frames.
    */
  private def announceRecording(name: String): Unit =
    System.err.println(s"golden: recorded $name (no comparison — $UpdateEnvVar is set)")

  /** The fixture `golden/<name><extension>` read off the test classpath, failing with the recording instructions when
    * it is not there.
    */
  private def loadFixture(name: String, extension: String): String =
    val stream = getClass.getResourceAsStream(s"/$FixtureDirectory/$name$extension")
    if stream == null then // scalafix:ok DisableSyntax; getResourceAsStream returns null when the fixture is absent
      CallSite.fail(
        s"missing golden fixture $FixtureDirectory/$name$extension" +
          s" — run once with GLYPHORA_GOLDEN_UPDATE=<test-resources-dir>"
      )
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)

  /** Where a styled mismatch writes its report: beside the fixture when the fixture is a file, else the temp dir. */
  private def reportDirectory(name: String): Path =
    Option(getClass.getResource(s"/$FixtureDirectory/$name$StyledFixtureExtension"))
      .filter(_.getProtocol == "file")
      .map(url => Path.of(url.toURI).getParent)
      .getOrElse(Path.of(System.getProperty("java.io.tmpdir")).resolve("glyphora-golden"))

  /** The on-disk location of `name`'s fixture under a test-resources `directory`. */
  private[testsupport] def fixtureFile(directory: Path, name: String): Path =
    directory.resolve(FixtureDirectory).resolve(s"$name$FixtureExtension")

  /** Writes `buffer`'s frame text to `name`'s fixture under `directory`, creating parent directories. The bytes are the
    * frame text verbatim — no terminator is appended, so previously recorded fixtures stay byte-identical.
    */
  private[testsupport] def writeFixture(directory: Path, name: String, buffer: Buffer): Unit =
    val target = fixtureFile(directory, name)
    Files.createDirectories(target.getParent)
    Files.write(target, BufferAssertions.text(buffer).getBytes(StandardCharsets.UTF_8))

  /** The on-disk location of `name`'s styled fixture under a test-resources `directory`. */
  private[testsupport] def styledFixtureFile(directory: Path, name: String): Path =
    directory.resolve(FixtureDirectory).resolve(s"$name$StyledFixtureExtension")

  /** Writes `buffer`'s ANSI export to `name`'s styled fixture under `directory`, creating parent directories. */
  private[testsupport] def writeStyledFixture(directory: Path, name: String, buffer: Buffer): Unit =
    val target = styledFixtureFile(directory, name)
    Files.createDirectories(target.getParent)
    Files.write(target, BufferExport.ansi(buffer).getBytes(StandardCharsets.UTF_8))

  /** Compares `buffer`'s ANSI export against already-loaded styled fixture text, writing the visual report into
    * `reportDirectory` and naming its files when the two differ. Trailing line terminators are stripped from both
    * sides, as [[assertMatchesText]] strips them.
    */
  private[testsupport] def assertMatchesStyledText(
      name: String,
      buffer: Buffer,
      expected: String,
      reportDirectory: Path,
  ): Unit =
    val actual   = normalise(BufferExport.ansi(buffer))
    val recorded = normalise(expected)
    if actual != recorded then
      val expectedBuffer = StyledGoldens.parseAnsi(recorded)
      val reports        = StyledGoldens.writeReport(reportDirectory, name, expectedBuffer, buffer)
      CallSite.fail(
        s"styled frame differs from $FixtureDirectory/$name$StyledFixtureExtension" +
          s" — see the report:\n${reports.mkString("\n")}" +
          s"\n--- expected (text) ---\n${BufferAssertions.text(expectedBuffer)}" +
          s"\n--- actual (text) ---\n${BufferAssertions.text(buffer)}"
      )

  /** Compares `buffer` against already-loaded fixture text. Trailing line terminators are stripped from both sides, so
    * the comparison — and the failure message — see the same normalisation.
    */
  private[testsupport] def assertMatchesText(name: String, buffer: Buffer, expected: String): Unit =
    val actual   = normalise(BufferAssertions.text(buffer))
    val recorded = normalise(expected)
    if actual != recorded then
      CallSite.fail(
        s"frame differs from $FixtureDirectory/$name$FixtureExtension\n--- expected ---\n$recorded\n--- actual ---\n$actual"
      )

  /** Drops trailing line terminators, which is what makes trailing blank rows insignificant while interior blank rows
    * still count. This is the frame-level half of the normalisation; the row-level half — stripping trailing spaces
    * from each row — has already happened in `BufferAssertions.trimmedLines`, which is why a frame that ends in blank
    * rows arrives here as text ending in bare newlines.
    */
  private def normalise(text: String): String = TrailingNewlines.matcher(text).replaceAll("")

package io.worxbend.tui.testsupport

import java.nio.file.{Files, Path}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Discipline check for the golden-frame fixtures a module keeps on disk: every `golden/<name>.txt` or
  * `golden/<name>.ansi` file under a module's test resources must be named by at least one
  * `assertMatches("<name>", …)`, `assertGolden("<name>")` or styled (`assertMatchesStyled`/`assertGoldenStyled`) call
  * in that module's test sources.
  *
  * Why this exists: [[GoldenFrames]] already fails a test that names a fixture which is not there. The opposite
  * direction — a fixture file whose test was deleted or renamed — has no such alarm. The stale file keeps sitting in
  * the repository, keeps being reviewed as if it meant something, and quietly stops recording anything at all. One
  * suite per module calling [[assertNoOrphans]] closes that gap, the same way `DocumentedFactorySpec` closes the gap
  * between a public factory and the prose that is supposed to mention it.
  *
  * Why it reads the *source files* rather than counting assertions at run time: the build forks one JVM per test class
  * (`testForkGrouping` in `build.mill`), so no single process ever observes the names that every suite in the module
  * asserted. A registry filled in as tests run would see only its own class's names and call everything else an orphan.
  *
  * Ownership and threading: this object holds no state. It reads the two directories the caller hands it on the calling
  * thread, and is safe to call from several tests at once.
  */
object GoldenFixtures:

  /** A call that names a fixture: the assertion's name, an opening parenthesis, and a string literal. Fixture names are
    * file names, so the literal is matched conservatively — letters, digits, dot, dash and underscore — and a call
    * whose name is computed rather than written out is not seen at all. That is deliberate: a false "orphan" report on
    * a fixture whose name is built at run time would be a failing build with no defect behind it, so this check only
    * ever recognises names a reader can also see.
    */
  private val Reference: Pattern =
    Pattern.compile("""(?:assertMatches|assertGolden)(?:Styled)?\(\s*"([A-Za-z0-9._-]+)"""")

  /** The two fixture extensions, text and styled; a file under `golden` with any other suffix is not a fixture. */
  private val Extensions: Seq[String] = Seq(GoldenFrames.FixtureExtension, GoldenFrames.StyledFixtureExtension)

  /** The base names of the fixtures recorded under `<resourcesDirectory>/golden` — `app-shell.txt` becomes `app-shell`,
    * and so does `app-shell.ansi`. A module with no `golden` directory at all has no fixtures, which is not an error; a
    * `resourcesDirectory` that does not exist is, because it means the caller was handed the wrong path.
    */
  def fixtureNames(resourcesDirectory: Path): Set[String] =
    assertDirectory(resourcesDirectory, "test-resources directory")
    val golden = resourcesDirectory.resolve(GoldenFrames.FixtureDirectory)
    if !Files.isDirectory(golden) then Set.empty
    else
      Using.resource(Files.list(golden)) { entries =>
        entries.iterator.asScala
          .filter(Files.isRegularFile(_))
          .map(_.getFileName.toString)
          .flatMap(file => Extensions.find(file.endsWith).map(file.stripSuffix))
          .toSet
      }

  /** Every fixture name written out in a `.scala` file under `sourcesDirectory`, at any depth. A missing directory is
    * an error rather than an empty answer: silently reading no sources would make every fixture in the module look
    * orphaned, which is the one wrong answer this check must never give.
    */
  def referencedNames(sourcesDirectory: Path): Set[String] =
    assertDirectory(sourcesDirectory, "test-sources directory")
    val sources = Using.resource(Files.walk(sourcesDirectory)) { paths =>
      paths.iterator.asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala")).toVector
    }
    sources.flatMap(namesIn).toSet

  /** The fixtures on disk that no test source names, sorted so the failure message is stable between runs. */
  def orphans(resourcesDirectory: Path, sourcesDirectory: Path): Seq[String] =
    (fixtureNames(resourcesDirectory) -- referencedNames(sourcesDirectory)).toSeq.sorted

  /** Fails, naming the files, when a fixture under `<resourcesDirectory>/golden` is referenced by no test source under
    * `sourcesDirectory`.
    *
    * Only that one direction is checked here. The other one — a test naming a fixture that is not on disk — already
    * fails inside `GoldenFrames.assertMatches` with a message telling you how to record it, and duplicating it would
    * mean two different failures for one mistake.
    */
  def assertNoOrphans(resourcesDirectory: Path, sourcesDirectory: Path): Unit =
    val stale = orphans(resourcesDirectory, sourcesDirectory)
    if stale.nonEmpty then
      CallSite.fail(
        s"golden fixtures under $resourcesDirectory/${GoldenFrames.FixtureDirectory} that no test names: ${stale.mkString(", ")}" +
          " — delete each file, or restore the test that recorded it"
      )

  /** The fixture names one source file writes out. */
  private def namesIn(source: Path): Set[String] =
    Using.resource(Files.lines(source)) { lines =>
      lines.iterator.asScala.flatMap { line =>
        val matcher = Reference.matcher(line)
        Iterator.continually(matcher.find()).takeWhile(identity).map(_ => matcher.group(1))
      }.toSet
    }

  /** Fails with an `AssertionError` attributed to the caller when the path is not a directory, naming which of the two
    * arguments was wrong.
    */
  private def assertDirectory(directory: Path, description: String): Unit =
    if !Files.isDirectory(directory) then CallSite.fail(s"$description does not exist: $directory")

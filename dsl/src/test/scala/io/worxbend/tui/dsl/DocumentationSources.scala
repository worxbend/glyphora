package io.worxbend.tui.dsl

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Locates the prose this repository publishes, for the suites that assert things about it.
  *
  * Published documentation is not compiled, so the only thing that keeps it honest is a test that reads it. Two suites
  * do that — [[DocumentedKeySpecSpec]] checks that every key spec in the prose parses, [[DocumentedFactorySpec]] checks
  * that every public factory is mentioned somewhere — and both need the same two answers: where is the checkout, and
  * which files count as documentation. They live here once rather than as two copies that can drift apart.
  */
private[dsl] object DocumentationSources:

  /** The checkout this run was built from. `None` means the suite is not running from a checkout at all, which is a
    * reason to cancel rather than to fail. See [[checkoutRoot]] for how it is found.
    */
  lazy val repoRoot: Option[Path] =
    checkoutRoot(sys.env.get("MILL_WORKSPACE_ROOT"), Paths.get("").toAbsolutePath)

  /** Mill hands every forked test `MILL_WORKSPACE_ROOT`, the workspace it is building, and that is the answer whenever
    * it names a checkout. Only a runner that sets nothing — an IDE — falls back to the nearest `build.mill` above the
    * working directory. The variable wins because it is the build's own statement of which checkout compiled these
    * tests; the working directory is wherever a process happened to be started.
    */
  def checkoutRoot(workspace: Option[String], workingDirectory: Path): Option[Path] =
    val declared = workspace.filter(_.nonEmpty).map(Paths.get(_).toAbsolutePath.normalize).filter(isCheckout)
    declared.orElse(
      Iterator
        .iterate(workingDirectory.toAbsolutePath)(_.getParent)
        .takeWhile(_ != null) // scalafix:ok DisableSyntax; `Path.getParent` returns null at the filesystem root
        .find(isCheckout)
    )

  private def isCheckout(directory: Path): Boolean = Files.isRegularFile(directory.resolve("build.mill"))

  /** Every Mill module directory of the build rooted at `root`, examples included, in no particular order.
    *
    * A directory is a module when it holds a `package.mill` and so does every directory between it and `root` — the
    * same rule Mill itself uses to find nested modules. That is what keeps a walk inside its own checkout: a git
    * worktree placed inside this one (`git worktree add ./18-theming`) has a `build.mill` at its top and no
    * `package.mill`, so neither it nor any module inside it is ever reached, and neither is `out/`, `node_modules/` or
    * any scratch directory that happens to contain a `src/` tree.
    */
  def buildModules(root: Path): Seq[Path] =
    def modulesUnder(directory: Path): Seq[Path] =
      directChildren(directory)
        .filter(child => Files.isRegularFile(child.resolve("package.mill")) && !isCheckout(child))
        .flatMap(module => module +: modulesUnder(module))
    modulesUnder(root)

  /** Everything a reader of this project sees as documentation: the pages under `website/docs`, which are published to
    * both GitHub Pages and the Wiki, the top-level README, and each module's own README — the prose the JLine drift
    * survived in across audit rounds because no suite read it.
    */
  def markdownSources(root: Path): Seq[Path] =
    val docs    = root.resolve("website/docs")
    val pages   =
      if Files.isDirectory(docs) then
        Using.resource(Files.list(docs))(_.iterator.asScala.filter(_.toString.endsWith(".md")).toVector)
      else Vector.empty
    val modules = Seq("core", "terminal", "widgets", "runtime", "dsl", "macros", "test-support")
    val readmes = modules.map(name => root.resolve(name).resolve("README.md"))
    (Seq(root.resolve("README.md")) ++ pages ++ readmes).filter(Files.isRegularFile(_))

  /** Every module of the build at `root` that keeps golden fixtures, for [[GoldenFixtureDisciplineSpec]]. */
  def fixtureModules(root: Path): Seq[Path] =
    buildModules(root).filter(module => Files.isDirectory(module.resolve("src/test/resources/golden")))

  private def directChildren(directory: Path): Seq[Path] =
    if !Files.isDirectory(directory) then Seq.empty
    else Using.resource(Files.list(directory))(_.iterator.asScala.filter(Files.isDirectory(_)).toVector)

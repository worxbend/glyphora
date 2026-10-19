package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/** The checkout walkers behind the suites that read the repository — [[GoldenFixtureDisciplineSpec]],
  * [[DisciplineSpec]], the documentation suites — must only ever see the checkout they were built from.
  *
  * Several git worktrees side by side is where that went wrong: a `dsl.test` run reported orphan golden fixtures that
  * belonged to a different worktree. These cases build that layout on disk — a second checkout beside this one, and one
  * placed inside it — and pin down which directories count.
  */
final class DocumentationSourcesSpec extends AnyFunSuite:

  /** A minimal Mill checkout at `root`: a `build.mill`, a `dsl` module and an `examples/gallery` module, each keeping
    * one golden fixture.
    */
  private def checkout(root: Path): Path =
    write(root.resolve("build.mill"))
    for module <- Seq("dsl", "examples", "examples/gallery") do write(root.resolve(module).resolve("package.mill"))
    for module <- Seq("dsl", "examples/gallery") do
      write(root.resolve(module).resolve("src/test/resources/golden/a.txt"))
    root

  private def write(file: Path): Unit =
    Files.createDirectories(file.getParent)
    val _ = Files.writeString(file, "// fixture\n")

  private def names(root: Path, paths: Seq[Path]): Seq[String] =
    paths.map(path => root.relativize(path).toString.replace(java.io.File.separatorChar, '/')).sorted

  test("a worktree placed inside the checkout is not one of its modules"):
    val root = checkout(Files.createTempDirectory("glyphora-checkout"))
    // `git worktree add ./18-theming`: a whole second checkout, one directory down
    checkout(root.resolve("18-theming"))
    assert(names(root, DocumentationSources.fixtureModules(root)) == Seq("dsl", "examples/gallery"))
    assert(names(root, DocumentationSources.buildModules(root)) == Seq("dsl", "examples", "examples/gallery"))

  test("a directory that is not a module of the build is never scanned, however its contents look"):
    val root = checkout(Files.createTempDirectory("glyphora-checkout"))
    write(root.resolve("scratch/notes/src/test/resources/golden/b.txt"))
    write(root.resolve("node_modules/src/test/resources/golden/c.txt"))
    assert(names(root, DocumentationSources.fixtureModules(root)) == Seq("dsl", "examples/gallery"))

  test("Mill's workspace root names the checkout, wherever the working directory is"):
    val parent  = Files.createTempDirectory("glyphora-worktrees")
    val mine    = checkout(parent.resolve("35-build-hygiene"))
    val sibling = checkout(parent.resolve("18-theming"))
    val sandbox = Files.createDirectories(sibling.resolve("out/dsl/test/testForked.dest/worker-0/sandbox"))
    assert(DocumentationSources.checkoutRoot(Some(mine.toString), sandbox) == Some(mine))

  test("without Mill's variable, the checkout is the nearest one above the working directory"):
    val parent  = Files.createTempDirectory("glyphora-worktrees")
    val mine    = checkout(parent.resolve("35-build-hygiene"))
    val _       = checkout(parent.resolve("18-theming"))
    val sandbox = Files.createDirectories(mine.resolve("out/dsl/test/testForked.dest/worker-0/sandbox"))
    assert(DocumentationSources.checkoutRoot(None, sandbox) == Some(mine))

  test("a workspace variable that names no checkout falls back to the working directory"):
    val mine    = checkout(Files.createTempDirectory("glyphora-checkout"))
    val sandbox = Files.createDirectories(mine.resolve("out/sandbox"))
    val nowhere = Files.createTempDirectory("glyphora-empty")
    assert(DocumentationSources.checkoutRoot(Some(nowhere.toString), sandbox) == Some(mine))
    assert(DocumentationSources.checkoutRoot(Some(""), sandbox) == Some(mine))

  test("outside any checkout there is no root, which the suites turn into a cancel"):
    val nowhere = Files.createTempDirectory("glyphora-empty")
    assert(DocumentationSources.checkoutRoot(None, nowhere).isEmpty)

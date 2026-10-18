package io.worxbend.tui.testsupport

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Covers the orphan check itself: a fixture with no test naming it must be reported, and a fixture that is named — in
  * any of the spellings the library offers — must not be.
  */
final class GoldenFixturesSpec extends AnyFunSuite:

  test("a fixture no test names is reported, and one that is named is not"):
    withWorkspace { workspace =>
      writeFixture(workspace, "stale")
      writeFixture(workspace, "live")
      writeSource(workspace, "LiveSpec.scala", """GoldenFrames.assertMatches("live", buffer)""")
      val error = intercept[AssertionError](GoldenFixtures.assertNoOrphans(resources(workspace), sources(workspace)))
      assert(error.getMessage.contains("stale"))
      assert(!error.getMessage.contains("live"))
    }

  test("the fluent Pilot spelling counts as a reference"):
    withWorkspace { workspace =>
      writeFixture(workspace, "shell")
      writeSource(workspace, "AppSpec.scala", """pilot.press("tab").assertGolden("shell")""")
      GoldenFixtures.assertNoOrphans(resources(workspace), sources(workspace))
    }

  test("a source file in a nested package still counts, and several names on one line are all seen"):
    withWorkspace { workspace =>
      writeFixture(workspace, "one")
      writeFixture(workspace, "two")
      writeSource(workspace, "deep/nested/BothSpec.scala", """assertMatches("one", a); assertGolden( "two" )""")
      assert(GoldenFixtures.orphans(resources(workspace), sources(workspace)).isEmpty)
    }

  test("only .txt and .ansi files under golden count as fixtures"):
    withWorkspace { workspace =>
      writeFixture(workspace, "kept")
      Files.writeString(resources(workspace).resolve("golden").resolve("styled.ansi"), "\u001b[0mx\u001b[0m")
      Files.writeString(resources(workspace).resolve("golden").resolve("README.md"), "not a fixture")
      writeSource(workspace, "KeptSpec.scala", """assertGolden("kept"); assertGoldenStyled("styled")""")
      assert(GoldenFixtures.fixtureNames(resources(workspace)) == Set("kept", "styled"))
      GoldenFixtures.assertNoOrphans(resources(workspace), sources(workspace))
    }

  test("a styled fixture is an orphan unless a styled assertion names it"):
    withWorkspace { workspace =>
      val golden = Files.createDirectories(resources(workspace).resolve("golden"))
      Files.writeString(golden.resolve("stale.ansi"), "\u001b[0mx\u001b[0m")
      Files.writeString(golden.resolve("live.ansi"), "\u001b[0mx\u001b[0m")
      writeSource(workspace, "LiveSpec.scala", """GoldenFrames.assertMatchesStyled("live", buffer)""")
      assert(GoldenFixtures.orphans(resources(workspace), sources(workspace)) == Seq("stale"))
    }

  test("a module with no golden directory has no fixtures and no orphans"):
    withWorkspace { workspace =>
      writeSource(workspace, "PlainSpec.scala", "assert(true)")
      assert(GoldenFixtures.fixtureNames(resources(workspace)).isEmpty)
      GoldenFixtures.assertNoOrphans(resources(workspace), sources(workspace))
    }

  test("a missing sources directory fails loudly instead of calling every fixture an orphan"):
    withWorkspace { workspace =>
      writeFixture(workspace, "kept")
      val absent = workspace.resolve("src/test/nowhere")
      val error  = intercept[AssertionError](GoldenFixtures.referencedNames(absent))
      assert(error.getMessage.contains("test-sources directory"))
      assert(!error.getMessage.contains("kept"))
    }

  test("a missing resources directory fails loudly rather than reporting no fixtures"):
    withWorkspace { workspace =>
      val absent = workspace.resolve("src/test/no-resources")
      val error  = intercept[AssertionError](GoldenFixtures.fixtureNames(absent))
      assert(error.getMessage.contains("test-resources directory"))
    }

  test("this module's own fixtures are all referenced"):
    // The check running against real directories, not synthesised ones: test-support keeps `pilot-app-frame.txt` and
    // `PilotGoldenSpec` names it. A path that does not resolve means the suite is not running from a checkout, which
    // is a reason to skip rather than to fail.
    val moduleRoot = Iterator
      .iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null) // scalafix:ok DisableSyntax; `Path.getParent` returns null at the filesystem root
      .find(candidate => Files.isDirectory(candidate.resolve("test-support/src/test/resources/golden")))
      .map(_.resolve("test-support"))
    moduleRoot.foreach { root =>
      GoldenFixtures.assertNoOrphans(root.resolve("src/test/resources"), root.resolve("src/test/scala"))
    }

  private def resources(workspace: Path): Path = workspace.resolve("src/test/resources")

  private def sources(workspace: Path): Path = workspace.resolve("src/test/scala")

  private def writeFixture(workspace: Path, name: String): Unit =
    val target = resources(workspace).resolve("golden").resolve(s"$name.txt")
    Files.createDirectories(target.getParent)
    Files.write(target, "frame".getBytes(StandardCharsets.UTF_8))

  private def writeSource(workspace: Path, relativePath: String, body: String): Unit =
    val target = sources(workspace).resolve(relativePath)
    Files.createDirectories(target.getParent)
    Files.writeString(target, body)

  /** Runs `body` against a throwaway module layout — the `src/test/{resources,scala}` pair this check reads. */
  private def withWorkspace(body: Path => Unit): Unit =
    TempWorkspace.withWorkspace("glyphora-fixtures") { workspace =>
      Files.createDirectories(workspace.resolve("src/test/resources"))
      Files.createDirectories(workspace.resolve("src/test/scala"))
      body(workspace)
    }

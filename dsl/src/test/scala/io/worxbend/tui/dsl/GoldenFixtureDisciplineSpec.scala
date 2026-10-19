package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.GoldenFixtures

import org.scalatest.funsuite.AnyFunSuite

/** Fails when a checked-in golden-frame fixture is named by no test any more.
  *
  * `GoldenFrames` already fails a test that asks for a fixture which is not on disk. The opposite mistake — deleting or
  * renaming the test and leaving its `golden/<name>.txt` behind — is invisible without a check like this one, and the
  * stale file then sits in the repository looking like it still guards something.
  *
  * It lives in `dsl.test` and scans *every* module rather than sitting once per module, for the same reason
  * [[DocumentedFactorySpec]] does: one suite that walks the checkout cannot be forgotten when a new module starts
  * recording fixtures.
  */
final class GoldenFixtureDisciplineSpec extends AnyFunSuite:

  test("every golden fixture in the checkout is named by a test"):
    DocumentationSources.repoRoot match
      case None       =>
        cancel("not running from a checkout, so there are no module directories to scan")
      case Some(root) =>
        val modules = DocumentationSources.fixtureModules(root)
        assert(modules.nonEmpty, s"found no golden fixtures under $root — this check would pass vacuously")
        modules.foreach { module =>
          GoldenFixtures.assertNoOrphans(module.resolve("src/test/resources"), module.resolve("src/test/scala"))
        }

package io.worxbend.tui.core

import java.nio.file.Files
import java.nio.file.Path

import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.funsuite.AnyFunSuite

/** Locks down what the constraint solver currently produces for a wide matrix of inputs.
  *
  * The other layout suites state the rules a reader is meant to learn; this one exists so that restructuring the solver
  * cannot quietly change a single cell. Each line of `layout-characterisation.txt` records one case as
  * `label => x+width,x+width,...`, so a diff points straight at the constraint set, extent, spacing and flex that
  * changed.
  *
  * Regenerating the file is only correct when a behaviour change is intended and explained. To do it, run the suite
  * with the same environment variable the golden-frame fixtures use, naming the resources directory to write into:
  *
  * {{{
  * GLYPHORA_GOLDEN_UPDATE=$PWD/core/src/test/resources ./mill core.test.testOnly io.worxbend.tui.core.LayoutCharacterisationSpec
  * }}}
  *
  * The recording run compares nothing and says so on stderr; the next plain run is what checks the new file. Writer and
  * reader share [[record]], so a re-recorded file cannot drift into a format the reader no longer parses. (`core`
  * cannot depend on `test-support`, where `GoldenFrames` lives, so the behaviour is reproduced here rather than
  * reused.)
  */
final class LayoutCharacterisationSpec extends AnyFunSuite:

  /** One solved case as it appears in the fixture: the segments as `x+width`, comma separated. */
  private def record(layoutCase: LayoutCharacterisationCases.Case): String =
    layoutCase.layout
      .split(Rect(0, 0, layoutCase.extent, 4))
      .map(rect => s"${rect.x}+${rect.width}")
      .mkString(",")

  /** The resources directory to record into, when the run was asked to record rather than to compare. */
  private val updateDirectory: Option[Path] =
    Option(System.getenv("GLYPHORA_GOLDEN_UPDATE")).filter(_.nonEmpty).map(Path.of(_))

  private val fixtureName: String = "layout-characterisation.txt"

  /** Lazy so a recording run never touches the file it is about to replace. */
  private lazy val expected: Map[String, String] =
    Using.resource(Source.fromInputStream(getClass.getResourceAsStream(s"/$fixtureName"), "UTF-8")) { source =>
      source.getLines().map(_.split(" => ", 2)).map(parts => parts(0) -> parts(1)).toMap
    }

  updateDirectory match
    case Some(directory) =>
      test(s"recording $fixtureName"):
        val lines = LayoutCharacterisationCases.cases.map(one => s"${one.label} => ${record(one)}")
        Files.createDirectories(directory)
        Files.write(directory.resolve(fixtureName), lines.asJava)
        System.err.println(s"$fixtureName recorded from ${lines.size} cases — no comparison was made")

    case None =>
      test("the recorded matrix covers every case"):
        assert(expected.sizeIs == LayoutCharacterisationCases.cases.size)

      LayoutCharacterisationCases.cases.foreach { layoutCase =>
        test(s"solver output is unchanged for ${layoutCase.label}"):
          assert(record(layoutCase) == expected(layoutCase.label))
      }

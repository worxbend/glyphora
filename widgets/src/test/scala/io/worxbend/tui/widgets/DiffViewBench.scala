package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.testsupport.Bench

/** Times the diff viewer's three costs on a 5 000-line Scala file with one line in twenty edited.
  *
  * Run it with:
  * {{{
  * ./mill widgets.test.runMain io.worxbend.tui.widgets.DiffViewBench
  * }}}
  *
  * *diff* is `Patch.of` over the two texts; *layout + frame* draws a fresh `DiffViewState` each time, so the rows are
  * laid out (word diffs, syntax) and one 120 × 40 frame painted; *steady frame* redraws the same state, which reuses
  * the laid-out rows and pays only for the window. A last row diffs two unrelated 20 000-line texts, the case the edit
  * distance cap exists for. Asserts nothing; see [[io.worxbend.tui.testsupport.Bench]].
  */
object DiffViewBench:

  private val area = Rect(0, 0, 120, 40)

  private val before = (0 until 5000).map(i => s"  val field$i: Int = compute($i) // line $i").mkString("\n")
  private val after  =
    (0 until 5000)
      .map(i =>
        if i % 20 == 0 then s"  val field$i: Long = recompute($i, 2) // line $i"
        else s"  val field$i: Int = compute($i) // line $i"
      )
      .mkString("\n")

  private val unrelatedBefore = (0 until 20000).map(i => s"a$i").mkString("\n")
  private val unrelatedAfter  = (0 until 20000).map(i => s"b$i").mkString("\n")

  def main(args: Array[String]): Unit =
    val _         = args
    val patch     = Patch.of(before, after)
    val options   = DiffViewOptions(contextLines = Some(3), lexer = Some(Lexer.Scala))
    val buffer    = Buffer(area)
    val steady    = DiffViewState()
    val results   = Seq(
      Bench.measure("diff 5000 lines, 250 edits", iterations = 5) { () =>
        Bench.consume(Patch.of(before, after).hunks.size.toLong)
      },
      Bench.measure("layout + frame", iterations = 5) { () =>
        DiffView(patch, options).render(area, buffer, DiffViewState())
        Bench.consume(buffer.get(0, 0).symbol.length.toLong)
      },
      Bench.measure("steady frame", iterations = 200) { () =>
        DiffView(patch, options).render(area, buffer, steady)
        steady.scrollDown()
        Bench.consume(buffer.get(0, 0).symbol.length.toLong)
      },
    )
    Bench.report("DiffView", results)
    // reported on its own, after the others: before the edit-distance cap this row ran out of a 2 GB heap
    val unrelated = Bench.measure("diff 20000 unrelated lines", iterations = 1, warmups = 1, samples = 3) { () =>
      Bench.consume(Patch.of(unrelatedBefore, unrelatedAfter).hunks.size.toLong)
    }
    Bench.report("DiffView, unrelated texts", Seq(unrelated))
    println(s"checksum ${Bench.consumed}")

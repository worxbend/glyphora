package io.worxbend.tui.examples.procmon

import io.worxbend.tui.core.{Buffer, KeyEvent, Rect, Size}
import io.worxbend.tui.dsl.{DataTableElement, ReactiveScope, Theme}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{ColumnSort, KeyedRow, SortDirection}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class ProcmonAppSpec extends AnyFunSuite:

  /** A started app with its first sample already on screen. The synthetic source keeps this offline and repeatable. */
  private def withApp[A]()(body: (ProcmonApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(96, 24))
    val app     = ProcmonApp(SyntheticProcessSource())
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      pilot.waitUntil("the first sample to land", 10.seconds)(app.sampleCount.peek > 0)
      pilot.waitForIdle()
      assert(pilot.a11yReport(app).isEmpty, pilot.a11yReport(app).mkString("\n"))
      body(app, pilot)
    }

  /** No runner or timer: every call below sees exactly the same immutable sample. */
  private def tableElementOf(app: ProcmonApp): DataTableElement[?] =
    app
      .view(using ReactiveScope.untracked, Theme.Dark)
      .children
      .collectFirst { case node: DataTableElement[?] =>
        node
      }
      .get

  private def filteredRowsOf[K](node: DataTableElement[K]): Seq[KeyedRow[K]] =
    node.table.filteredRows(node.state)

  test("unchanged selection and redraw reuse projected rows and the sorted view across table call paths"):
    val app       = ProcmonApp(SyntheticProcessSource())
    app.processes.set(
      Vector(
        ProcessInfo(10, "root", 1.0, 2.0, "nginx"),
        ProcessInfo(20, "root", 9.0, 3.0, "nginx worker"),
        ProcessInfo(30, "user", 4.0, 1.0, "java"),
      )
    )
    val initial   = tableElementOf(app)
    val projected = initial.table.rows
    val ordered   = filteredRowsOf(initial)
    assert(ordered.map(_.key) == Seq(20, 30, 10))

    for index <- 0 until 3 do
      app.tableState.selected = Some(index)
      assert(app.selectedProcessId.contains(ordered(index).key))
      assert(app.visibleProcessIds == Seq(20, 30, 10))
      val redrawn = tableElementOf(app)
      assert(redrawn.table.rows eq projected, "selection/readback/redraw must not project the sample again")
      assert(filteredRowsOf(redrawn) eq ordered, "unchanged sort and filter must retain the sorted view")
      redrawn.widget.render(Rect(0, 0, 96, 20), Buffer(Rect(0, 0, 96, 20)))
      assert(filteredRowsOf(tableElementOf(app)) eq ordered, "painting must not invalidate the sorted view")

    // syncFilter reads the old selection and reselects it against the new view inside view construction.
    app.tableState.selected = Some(0)
    app.filterInput.insert("nginx")
    val filtered = tableElementOf(app)
    assert(filtered.table.rows eq projected, "filter reselection must reuse the projection")
    val narrowed = filteredRowsOf(filtered)
    assert(narrowed.map(_.key) == Seq(20, 10))
    assert(app.selectedProcessId.contains(20))
    assert(filteredRowsOf(tableElementOf(app)) eq narrowed, "readback must not evict the filtered view")

    app.bindings.bindings.find(_.label == "p").get.action()
    val sorted = tableElementOf(app)
    assert(sorted.table.rows eq projected, "sort reselection must reuse the projection")
    val byPid  = filteredRowsOf(sorted)
    assert(byPid.map(_.key) == Seq(10, 20))
    assert(app.selectedProcessId.contains(20))
    assert(filteredRowsOf(tableElementOf(app)) eq byPid, "sort reselection must leave the cache reusable")

    app.bindings.bindings.find(_.label == "/").get.action()
    val openFilter = app.view(using ReactiveScope.untracked, Theme.Dark)
    val input      = openFilter.children(1).children(1)
    assert(input.props.onKey.get(KeyEvent.parse("esc").toOption.get))
    val cleared    = tableElementOf(app)
    assert(cleared.table.rows eq projected, "filter cancellation reselection must reuse the projection")
    val allByPid   = filteredRowsOf(cleared)
    assert(allByPid.map(_.key) == Seq(10, 20, 30))
    assert(app.selectedProcessId.contains(20))
    assert(filteredRowsOf(tableElementOf(app)) eq allByPid)

  test(
    "a same-count immutable sample replacement refreshes projected cells and the filtered sort with keyed selection"
  ):
    val app     = ProcmonApp(SyntheticProcessSource())
    app.processes.set(
      Vector(
        ProcessInfo(10, "root", 1.0, 2.0, "nginx"),
        ProcessInfo(20, "root", 9.0, 3.0, "nginx worker"),
        ProcessInfo(30, "user", 4.0, 1.0, "java"),
      )
    )
    app.filterInput.insert("nginx")
    val initial = tableElementOf(app)
    val ordered = filteredRowsOf(initial)
    assert(ordered.map(_.key) == Seq(20, 10))
    app.tableState.selected = Some(0)
    initial.widget.render(Rect(0, 0, 96, 20), Buffer(Rect(0, 0, 96, 20)))

    app.processes.set(
      Vector(
        ProcessInfo(10, "root", 12.0, 2.0, "nginx promoted"),
        ProcessInfo(20, "new-user", 2.0, 7.0, "nginx changed"),
        ProcessInfo(30, "user", 15.0, 1.0, "nginx newly matched"),
      )
    )
    // Exercise the untracked readback path before view sees the replacement.
    assert(app.visibleProcessIds == Seq(30, 10, 20))
    val refreshed      = tableElementOf(app)
    val refreshedOrder = filteredRowsOf(refreshed)
    assert(!(refreshed.table.rows eq initial.table.rows))
    assert(!(refreshedOrder eq ordered))
    assert(
      refreshed.table.rows.find(_.key == 20).get.cells ==
        Seq("    20", "new-user", "  2.0", "  7.0", "nginx changed")
    )
    refreshed.widget.render(Rect(0, 0, 96, 20), Buffer(Rect(0, 0, 96, 20)))
    assert(app.selectedProcessId.contains(20))
    assert(app.tableState.selected.contains(2))
    assert(filteredRowsOf(tableElementOf(app)) eq refreshedOrder)

    app.processes.set(Vector.empty)
    val empty = tableElementOf(app)
    empty.widget.render(Rect(0, 0, 96, 20), Buffer(Rect(0, 0, 96, 20)))
    assert(app.visibleProcessIds.isEmpty)
    assert(app.selectedProcessId.isEmpty)

  test("the summary header, the process table and the key hints all render"):
    withApp() { (app, pilot) =>
      val screen = pilot.screenText
      assert(screen.contains("procmon"))
      assert(screen.contains("48 processes"), "the header counts what the source returned")
      assert(screen.contains("source synthetic"))
      assert(screen.contains("CPU"))
      assert(screen.contains("MEM"))
      assert(screen.contains("PID"))
      assert(screen.contains("COMMAND"))
      assert(screen.contains("q quit"), "the status bar is built from the declared bindings' hints")
      // the default sort is CPU descending, the way `top` starts
      assert(app.tableState.sort.contains(ColumnSort(2, SortDirection.Descending)))
      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("sorting by a column once orders it, and a second press reverses it"):
    withApp() { (app, pilot) =>
      val pids = app.processes.peek.map(_.pid)

      pilot.press("p").waitForIdle()
      assert(app.tableState.sort.contains(ColumnSort(0, SortDirection.Ascending)))
      assert(app.visibleProcessIds.head == pids.min)

      pilot.press("p").waitForIdle()
      assert(app.tableState.sort.contains(ColumnSort(0, SortDirection.Descending)))
      assert(app.visibleProcessIds.head == pids.max)

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("the filter box narrows the table, Enter keeps the filter and Esc clears it"):
    withApp() { (app, pilot) =>
      assert(app.visibleProcessIds.sizeIs == 48)

      pilot.press("/").waitForIdle()
      pilot.typeText("nginx").waitForIdle()
      // the filter box owns the keyboard while it is open: 'n' typed into it must not also fire the sort-by-command
      // binding, because the focused element consumes the key before the app's bindings are consulted
      assert(app.tableState.sort.map(_.column).contains(2))
      assert(app.filterInput.value == "nginx")
      assert(app.visibleProcessIds.sizeIs == 3, "three of the forty-eight synthetic processes run nginx")
      assert(pilot.screenText.contains("nginx"))

      pilot.press("enter").waitForIdle()
      assert(app.visibleProcessIds.sizeIs == 3, "Enter commits the filter and closes the box")

      pilot.press("/").waitForIdle()
      pilot.press("esc").waitForIdle()
      assert(app.filterInput.value.isEmpty)
      assert(app.visibleProcessIds.sizeIs == 48, "Esc cancels the filter")

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("the selection follows its process across a re-sort and across a refresh"):
    withApp() { (app, pilot) =>
      pilot.press("down", "down", "down").waitForIdle()

      val pinned = app.selectedProcessId
      assert(pinned.isDefined)
      assert(app.tableState.selected.contains(2))

      // sorting by pid moves nearly every row; the highlight must land on the same process at its new index
      pilot.press("p").waitForIdle()
      assert(app.selectedProcessId == pinned)
      val afterSort = app.tableState.selected
      assert(afterSort.isDefined)
      assert(app.visibleProcessIds(afterSort.get) == pinned.get)

      // and a refresh replaces every row's numbers underneath it without moving the highlight to another process
      val samplesBefore = app.sampleCount.peek
      pilot.press("r")
      pilot.waitUntil("the refresh to land", 10.seconds)(app.sampleCount.peek > samplesBefore)
      pilot.waitForIdle()
      assert(app.selectedProcessId == pinned)
      val afterRefresh  = app.tableState.selected
      assert(afterRefresh.isDefined)
      assert(app.visibleProcessIds(afterRefresh.get) == pinned.get)

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("ticks refresh the table with no input at all"):
    withApp() { (app, pilot) =>
      pilot.press("-").waitForIdle() // shortest interval, so the test does not wait two seconds
      assert(app.refreshSeconds.peek == 1)
      val samplesBefore = app.sampleCount.peek
      val drawsBefore   = pilot.backend.drawCount
      pilot.waitUntil("a tick-driven refresh to repaint", 10.seconds)(
        app.sampleCount.peek > samplesBefore && pilot.backend.drawCount > drawsBefore
      )
      assert(app.sampleCount.peek > samplesBefore)
      assert(pilot.backend.drawCount > drawsBefore, "a new sample repaints without anyone pressing a key")
      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  /** Exercises the real detection path — on a machine with `ps` this samples live processes, and everywhere else it
    * proves the synthetic fallback takes over rather than leaving the app with an empty table.
    */
  test("the detected source always produces rows, live or synthetic"):
    val source = ProcessSource.detect()
    assert(Seq("ps", "synthetic").contains(source.name))
    assert(source.sample().nonEmpty)

package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Tables, trees, lists, charts, meters and the loading indicators. */
object DataPages:

  private def page(factory: String, family: String, summary: String)(view: View): GalleryPage =
    GalleryPage(factory, family, summary, view)

  private val Services = Seq(
    Seq("api", "running", "12 ms"),
    Seq("worker", "running", "40 ms"),
    Seq("cron", "stopped", "-"),
    Seq("mailer", "degraded", "310 ms"),
  )

  private val Tree = Seq(
    TreeNode("src", Seq(TreeNode("main", Seq(TreeNode("App.scala"), TreeNode("View.scala"))), TreeNode("test"))),
    TreeNode("build.mill"),
    TreeNode("README.md"),
  )

  private val Usage = Seq("mon" -> 4L, "tue" -> 7L, "wed" -> 3L, "thu" -> 9L, "fri" -> 6L)

  private val Wave = (0 until 40).map(i => (math.sin(i / 4.0) * 40 + 50).round)

  /** A fixed elapsed time for the `…At` pages, so their frame is the same on every run. */
  private val At: FiniteDuration = 700.millis

  def data(fixtures: GalleryFixtures): Vector[GalleryPage] =
    val family     = "Data"
    val listState  = ListState(selected = Some(1))
    val picks      = Signal(Set(0, 2))
    val pickState  = ListState()
    val tableState = DataTableState()
    val dataRows   = Services.zipWithIndex.map((cells, i) => KeyedRow(i, cells))
    val records    = DataTable(Seq("service", "state", "latency"), dataRows, Seq.fill(3)(Constraint.Fill(1)))
    val treeState  = TreeState()
    val tableTree  = TreeState.keyed[String](_.label)
    val sizes      = Map("src" -> "12 KB", "main" -> "9 KB", "App.scala" -> "6 KB", "View.scala" -> "3 KB")
    val treeTab    = TreeTable[String](
      Tree,
      Seq("name", "size"),
      label => Seq(sizes.getOrElse(label, "1 KB")),
      Seq(Constraint.Fill(1), Constraint.Length(8)),
    )
    // the first branch opened, so both tree pages show more than their roots
    Seq(treeState, tableTree).foreach { state =>
      state.selectNext(Tree)
      state.toggle(Tree)
    }
    val directory  = DirectoryTreeState(fixtures.directory)
    directory.loadVisible()
    Vector(
      page("table", family, "rows of strings under fixed or flexible columns")(
        table(
          Seq("service", "state", "latency") +: Services,
          Constraint.Fill(1),
          Constraint.Fill(1),
          Constraint.Length(8),
        )
      ),
      page("styledTable", family, "a table whose cells carry their own styles")(
        styledTable(
          Services.map(_.map(cell => Line.raw(cell))),
          Constraint.Fill(1),
          Constraint.Fill(1),
          Constraint.Length(8),
        )
      ),
      page("dataTable", family, "sortable, selectable, paged records with keyed rows")(dataTable(records, tableState)),
      page("treeTable", family, "a tree whose rows have columns")(treeTable(treeTab, tableTree)),
      page("tree", family, "an expandable in-memory hierarchy")(tree(Tree, treeState)),
      page("directoryTree", family, "a lazily-read view of a directory")(directoryTree(directory)),
      page("list", family, "a scrollable list with a selection")(
        list(Seq("inbox", "drafts", "sent", "archive"), listState)
      ),
      page("selectionList", family, "a list whose rows are ticked on and off")(
        selectionList(Seq("email", "sms", "push", "webhook"), picks, pickState)
      ),
      page("calendar", family, "one month with a selected day")(
        calendar(fixtures.today.getYear, fixtures.today.getMonthValue, Some(fixtures.today.getDayOfMonth))
      ),
    )

  def charts(): Vector[GalleryPage] =
    val family = "Charts"
    val fetch  = ProgressTask("fetch", Some(100L), () => 4.seconds)
    val build  = ProgressTask("build", Some(40L), () => 4.seconds)
    fetch.update(64L)
    build.update(10L)
    val ratio  = Signal(42)
    Vector(
      page("barChart", family, "vertical bars with labels")(barChart(Usage)),
      page("horizontalBarChart", family, "horizontal bars with values")(horizontalBarChart(Usage, showValues = true)),
      page("stackedBarChart", family, "bars split into segments")(
        stackedBarChart(Seq("q1" -> Seq(3L, 2L, 1L), "q2" -> Seq(4L, 3L, 2L), "q3" -> Seq(2L, 5L, 3L)))
      ),
      page("chart", family, "line and scatter datasets on two axes")(
        chart(
          Seq(Dataset("load", Wave.zipWithIndex.map((v, i) => (i.toDouble, v.toDouble)))),
          Bounds(0.0, 40.0),
          Bounds(0.0, 100.0),
        )
      ),
      page("canvas", family, "free-form shapes in braille resolution")(
        canvas(Bounds(0.0, 20.0), Bounds(0.0, 10.0))(
          Shape.Polyline(Seq((0.0, 0.0), (5.0, 8.0), (10.0, 3.0), (20.0, 9.0))),
          Shape.CircleShape(14.0, 5.0, 3.0),
        )
      ),
      page("sparkline", family, "a one-row trend")(sparkline(Wave).length(1)),
      page("dualSparkline", family, "two trends sharing a baseline")(
        dualSparkline(Wave, Wave.reverse).length(2)
      ),
      page("heatmap", family, "a grid of values as shades")(
        heatmap(Seq.tabulate(6, 24)((y, x) => math.sin(x / 3.0) * math.cos(y / 2.0)))
      ),
      page("pieChart", family, "shares of a whole")(pieChart(Seq("scala" -> 62.0, "java" -> 25.0, "other" -> 13.0))),
      page("gauge", family, "a ratio as a filled bar; the slider below edits it")(
        column(gauge(ratio.get / 100.0).length(1), slider(ratio).length(1))
      ),
      page("progressBar", family, "progress through a known total")(
        column(progressBar(0.42).length(1), progressBar(7, 10).length(1))
      ),
      page("scrollbar", family, "where a viewport sits in its content")(
        row(text("content").fill, scrollbar(100, 30).length(1))
      ),
      page("multiProgress", family, "several tasks with aligned columns")(multiProgress(Seq(fetch, build))),
    )

  def loading(): Vector[GalleryPage] =
    val family = "Loading"
    Vector(
      page("spinner", family, "a spinner driven by the app's clock")(spinner("loading")),
      page("spinnerAt", family, "a spinner at a caller-owned time")(spinnerAt(At, "loading")),
      page("spinnerGrid", family, "a grid of dots animating together")(spinnerGrid()),
      page("spinnerGridAt", family, "the grid at a caller-owned time")(spinnerGridAt(At)),
      page("orbitSpinner", family, "dots orbiting a centre")(orbitSpinner()),
      page("orbitSpinnerAt", family, "the orbit at a caller-owned time")(orbitSpinnerAt(At)),
      page("linearSpinner", family, "a bouncing segment")(linearSpinner().length(1)),
      page("linearSpinnerAt", family, "the segment at a caller-owned time")(linearSpinnerAt(At).length(1)),
      page("indeterminateBar", family, "progress with no known total")(indeterminateBar().length(1)),
      page("indeterminateBarAt", family, "the bar at a caller-owned time")(indeterminateBarAt(At).length(1)),
      page("skeleton", family, "a shimmering placeholder for content on its way")(skeleton()),
      page("skeletonAt", family, "the shimmer at a caller-owned time")(skeletonAt(At)),
    )

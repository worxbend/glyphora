package io.worxbend.tui.dslimport

import io.worxbend.tui.dsl.*
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** The `dsl` package's one-import promise, made into something the compiler checks.
  *
  * `dsl.scala` states the rule it keeps: if a name appears in the *signature* of anything the package exports, the
  * package re-exports it too, so an application writes `import io.worxbend.tui.dsl.*` and nothing else. Every other
  * suite in this module is declared `package io.worxbend.tui.dsl`, which means it sees the whole package whether it is
  * exported or not — none of them can tell the rule being kept from the rule being broken.
  *
  * This one lives in a different package on purpose. It overrides `TuiApp`'s two lifecycle seams — `colorDepth` and
  * `createBackend` — and runs the app through `run()`, which names every type in their signatures out loud:
  * `ColorDepth`, `BackendError`, `Backend`, `RunnerError`. It also names the two payload types those signatures reach
  * through — `AsyncErrorHandler`, which `Async.run` takes and an app installs as a `given`, and `QueuedTaskFailures`,
  * which is what a `RunnerError.QueuedTask` actually carries. Delete any one of those from `dsl.scala`'s export blocks
  * and this file stops compiling, which is the failure the rule wants. It is a compile-time assertion first and a
  * runtime one second.
  *
  * The single non-`dsl` import is deliberate and is the exception `dsl.scala` documents: *constructing* a backend is a
  * `tui-terminal` job, so `HeadlessBackend` is not re-exported and a test that wires one is expected to say so. `Pilot`
  * is a test tool, not part of the promise.
  */
final class OneImportSpec extends AnyFunSuite with PilotFixture:

  test("an app can be written, wired to a backend and run with `io.worxbend.tui.dsl.*` as its only glyphora import"):
    val backend = HeadlessBackend(Size(24, 4))

    val app = new TuiApp:
      private val hits = Signal(0)

      // the two overridable lifecycle seams, named in full: `ColorDepth`, `BackendError` and `Backend` all have to be
      // reachable from the one import for these two lines to compile
      override protected def colorDepth: ColorDepth                         = ColorDepth.Ansi16
      override protected def createBackend(): Either[BackendError, Backend] = Right(backend)

      override def bindings: KeyBindings = KeyBindings(
        binding("a", "count a hit") { hits.update(_ + 1) },
        binding("q", "quit")(quit()),
      )

      def view(using ReactiveScope, Theme): Element =
        column(
          text(s"hits: ${hits.get}"),
          marquee("ticker").speed(4.0).gap(2),
          sparkline(Seq(1L, 4L, 2L)).max(10L),
          table(Seq(Seq("api", "ready")), Constraint.Length(6), Constraint.Fill(1)).header("svc", "state"),
        )

    // `run()` rather than `runWith(backend)`, so the overridden `createBackend` is the thing under test; the result
    // type is written out because that is the fourth name the rule owes us.
    var outcome: Option[Either[RunnerError, Unit]] = None
    val pilot                                      = startPilot(backend) {
      val result: Either[RunnerError, Unit] = app.run()
      outcome = Some(result)
      result
    }

    pilot.waitForIdle()
    pilot.press("a").waitForIdle()
    assert(pilot.screenLines.head.startsWith("hits: 1"))

    pilot.press("q")
    assert(pilot.awaitTermination())
    assert(outcome.contains(Right(())))

    // The fifth and sixth names the rule owes us, both reached *through* an exported signature rather than written in
    // one. Installing a background-failure policy means writing the type of the `given`, and reading the error `run()`
    // hands back means naming what a `RunnerError.QueuedTask` carries.
    var reported: Option[Throwable] = None
    val boom                        = RuntimeException("boom")

    given handler: AsyncErrorHandler = error => reported = Some(error)
    handler.handle(boom)
    assert(reported.contains(boom))

    val queued: RunnerError = RunnerError.QueuedTask(QueuedTaskFailures(boom, 3))
    queued match
      case RunnerError.QueuedTask(failures: QueuedTaskFailures) => assert(failures.count == 3)
      case other                                                => fail(s"expected a QueuedTask error, got $other")

  test("an embedding host names its member-signature types through the one import"):
    val host                            = ElementHost()
    val protocol: Option[ImageProtocol] = ImageProtocol.select(HeadlessBackend(Size(8, 2)).capabilities)
    host.imageProtocol = protocol
    assert(host.imageProtocol == protocol)
    val range: TextRange                = TextRange(TextPosition(0, 0), TextPosition(1, 2))
    assert(range.start.line == 0 && range.end.column == 2)
    // Both overload selection and Frame's name must compile without a runtime import.
    val render: (Frame, Theme, View, ReactiveScope) => Unit =
      (frame, theme, view, scope) => host.render(frame, theme, view)(using scope)
    assert(render != null) // scalafix:ok DisableSyntax; compile-time method-value proof

  /** Two names no exported signature forces the block to carry, and which were therefore missing from it.
    *
    * `Spring` is the fourth member of the core motion group — `Easing`, `Effect` and `Tween` were already exported, and
    * the motion guide presents a spring and a tween as alternatives for the same job, so the guide's spring snippet was
    * the one that opened with a second glyphora import. `.ratio` is the sixth constraint shorthand: `Constraint.Ratio`
    * appears in completion, the layout solver has a branch for it, and no DSL extension reached it.
    */
  test("the whole core motion group, and every constraint, are reachable from the one import"):
    val spring = Spring(frequency = 6.0, damping = 0.75, deltaTime = 0.05)
    val next   = spring.step(Spring.State(0.0, 0.0), 100.0)
    assert(next.position > 0.0 && !spring.settled(next.position, next.velocity, 100.0))

    val third: Element = text("a").ratio(1, 3)
    assert(third.props.constraint.contains(Constraint.Ratio(1, 3)))

  /** Six names the exported API names in its *members'* signatures, each written the way a view, a theme or a config
    * writes it. Every one of these was missing from the export blocks for several releases: `ChartOptions` was
    * reachable but the `LegendConstraints` its `hiddenLegendConstraints` field takes was not, and so on down the list.
    * `OneImportSurfaceSpec` reads the constructors out of the sources and guards the next one; this test is the
    * compiled proof for the six, and it compiles against the real classes, not a `typeCheckErrors` string.
    */
  test("the types named by exported members' own signatures are reachable from the one import"):
    // `Signal.apply` takes a `SignalEquality`, and state-and-signals.md shows an app installing its own as a `given`
    final case class Reading(id: Int, value: Double)
    given SignalEquality[Reading] with
      def unchanged(next: Reading, current: Reading): Boolean = next.id == current.id
    val reading = Signal(Reading(1, 0.5))
    assert(reading.peek == Reading(1, 0.5))
    assert(summon[SignalEquality[Reading]].unchanged(Reading(1, 0.5), Reading(1, 0.9)))

    // `Style.withUnderlineStyle` takes an `UnderlineStyle`; layout-and-style.md tells the reader to call it
    val curly: Style = Style.Default.withUnderlineStyle(UnderlineStyle.Curly)
    assert(curly.underlineStyle == UnderlineStyle.Curly)

    // `Buffer.get` returns a `Cell`, which is what every `widget { … }` body that reads back what it drew holds
    var seen: Option[Cell] = None
    val probe: Element     = widget { (area: Rect, buffer: Buffer) =>
      buffer.setString(area.x, area.y, "x", Style.Default.bold)
      seen = Some(buffer.get(area.x, area.y))
    }
    probe.widget.render(Rect(0, 0, 1, 1), Buffer(Rect(0, 0, 1, 1)))
    assert(seen.map(_.symbol).contains("x"))

    // the three option bundles whose fields are typed by an enum or a pair that was not exported alongside them
    val legend  =
      ChartOptions(hiddenLegendConstraints = LegendConstraints(Constraint.Percentage(50), Constraint.Percentage(50)))
    assert(legend.hiddenLegendConstraints.horizontal == Constraint.Percentage(50))
    val near    = ScrollbarOptions(side = ScrollbarSide.Near)
    assert(near.side == ScrollbarSide.Near)
    val reverse = OrbitSpinnerOptions(direction = SpinDirection.CounterClockwise)
    assert(reverse.direction == SpinDirection.CounterClockwise)

  /** The call shapes the published pages show, compiled as written. Each of these was documented in a form that did not
    * compile — `spacer()`, `Style.Default.fg(Color.Cyan)`, `dataTable(columns, rows, state)` — and the pages were
    * corrected to these; the test is what keeps the prose and the API from drifting apart a second time. The keyed
    * `DataTableState` is here for the same reason: its Scaladoc used to show `DataTableState[String]()`, which the
    * companion's parameterless `apply` cannot mean.
    */
  test("the call shapes the published pages show compile from the one import"):
    val gap: Element                   = spacer                       // component-state.md, layout-and-style.md
    assert(gap.props.constraint.isEmpty)
    val cyan: Style                    = Style.fg(Color.Cyan)         // widgets.md
    assert(cyan.fg.contains(Color.Cyan))
    val tableState                     = DataTableState()             // tables-and-selection.md
    val table                          = DataTable(
      columns = Seq("name", "state"),
      rows = Seq(KeyedRow(0, Seq("api", "ready"))),
      widths = Seq(Constraint.Fill(1), Constraint.Length(6)),
    )
    val grid: Element                  = dataTable(table, tableState) // mouse.md: build the `DataTable` first
    assert(grid.props.focusable)
    val byName: DataTableState[String] = DataTableState.keyed[String] // R1-05: the keyed form has a factory
    assert(byName.selected.isEmpty)

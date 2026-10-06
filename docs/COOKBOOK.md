# glyphora cookbook

> The canonical cookbook is [`website/docs/cookbook.md`](../website/docs/cookbook.md).
> It is published from the same Markdown source to the
> [styled documentation](https://oleksandr-balyshyn.github.io/glyphora/cookbook)
> and the [GitHub Wiki](https://github.com/oleksandr-balyshyn/glyphora/wiki/Cookbook).

Short recipes for the common shapes. Every snippet assumes `import io.worxbend.tui.dsl.*`;
the [examples](../examples/README.md) are complete runnable versions of these patterns.

## A minimal app

```scala
object Hello extends TuiApp:
  def view(using ReactiveScope, Theme): Element =
    panel("Hello")(text("Welcome!").bold.fg(Color.Cyan)).rounded
  override def bindings = KeyBindings(binding("q", "quit")(quit()))
```

## State: signals, not threading

State lives in `Signal`s; any signal the view *reads* re-renders it when set.

```scala
val count = Signal(0)
def view(using ReactiveScope, Theme) = text(s"count: ${count.get}")
// in a binding/handler (render thread): count.update(_ + 1)
```

## The app shell

```scala
scaffold(
  topBar = Some(topBar("myapp", tabs = Seq("Files", "Logs"), selectedTab = tab.get)),
  sidebar = Some(sidebar(directoryTree(treeState), width = 28)),
  statusBar = Some(statusBar(bindings)),
)(content)
```

`Theme.Dark`/`Light`/`HighContrast` ship with the library. `view` takes the app's theme
as a `using` parameter — `def view(using ReactiveScope, Theme)` — so `panel`, `rule`,
`dialog`, `markdown`, `statusBar` and every other themed helper it calls pick up an
overridden `TuiApp.theme` with no `given` to declare. Helpers written as separate
methods need `(using Theme)` of their own. To switch at runtime, back `theme` with a
`Signal` and read that signal inside `view`.

## Keys once, everywhere

```scala
override def bindings = KeyBindings(
  binding("ctrl+s", "save")(save()),
  binding("?", "help")(pushScreen(Screen(helpOverlay(bindings)))),
  binding("esc", "quit")(quit()),
)
```

One declaration drives dispatch, the status-bar hints, the `?` overlay, and the
`Ctrl+P` command palette (fuzzy-filtered, Enter runs the command).

## Navigation, dialogs, toasts

```scala
pushScreen(Screen { centered(40, 7)(panel("Confirm")(...)) }) // modal: base loses focus
pushScreen(Screen.full(settingsView))                         // replaces the view
popScreen()
notify("Saved", NoticeLevel.Success)                           // needs config.tickRate
```

## Splash & animation

```scala
override def splash = Some(SplashScreen(
  centered(36, 5)(bigText("MYAPP").fg(Color.Cyan)),
  effect = Effect.coalesce(800.millis),
))
runEffect(Effect.parallel(Effect.fadeIn(300.millis), Effect.sweepIn(300.millis)))
```

Effects are post-render buffer transforms; compose with `sequence`/`parallel`/
`delay`/`repeat` and `Easing`. `Tween` animates plain values from `onTick`.

## Forms with compile-time derivation

```scala
import io.worxbend.tui.macros.deriveForm

final case class Signup(username: String, age: Int, subscribe: Boolean)
val spec = deriveForm[Signup]
val form = FormState.of(
  spec,
  spec.field(_.age).validate(_ >= 18, "must be 18+"),
)
// view: Form(form)   submit: form.submit()   result: form.result / form.errors
```

The spec retains its original parsers. Submission parses all controls, constructs a
candidate only on parse success, then runs rejection-only typed checks; no check
replaces a value. See [Forms & validation](../website/docs/forms-and-validation.md).

## Testing headlessly

```scala
Pilot.using(Size(60, 16))(backend => app.runWith(backend)) { pilot =>
  pilot.waitForIdle().typeText("hi").press("enter").waitForIdle()
  assert(pilot.screenText.contains("hi"))
}
```

## Charts

```scala
chart(Seq(Dataset("cpu", points)), xBounds = Bounds(0, 60), yBounds = Bounds(0, 100))
  // smoother: Chart(datasets, Bounds(0, 60), Bounds(0, 100), ChartOptions(resolution = CanvasResolution.Braille, showLabels = true))
pieChart(Seq("a" -> 3.0, "b" -> 1.0)); heatmap(grid); stackedBarChart(series)
image(Image.fromFile(path).toOption.get)  // half-block raster
```

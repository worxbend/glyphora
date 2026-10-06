# examples

Ten runnable example apps — also the primary "how do I use this"
documentation and the GraalVM native-image compile targets. Each has headless `Pilot`
end-to-end tests in its `test` submodule.

| Example | Run | Shows |
|---|---|---|
| `hello-world` | `./mill examples.hello-world.run` | static panel/text through the DSL, and the raw `onKeyEvent` escape hatch |
| `counter` | `./mill examples.counter.run` | declared `KeyBindings` + `scaffold` + `statusBar`, and the signal update → re-render cycle |
| `todo-list` | `./mill examples.todo-list.run` | `input` + `list`, Tab focus switching |
| `dashboard` | `./mill examples.dashboard.run` | `gauge`/`sparkline`/`chart`, tick-rate animation |
| `form-demo` | `./mill examples.form-demo.run` | `deriveForm` + spec-owned `spec.field(...).validate(...)` typed checks |
| `weather` | `./mill examples.weather.run` | live public HTTP API call bridged into `Signal` via `Async.runCatching` |
| `showcase` | `./mill examples.showcase.run` | the app-chrome tour: scaffold, themes, palette, screens, toasts, splash — the manual PTY test bed |
| `procmon` | `./mill examples.procmon.run` | a sortable/filterable table over refreshing data, with a selection that survives the refresh |
| `airsensor` | `./mill examples.airsensor.run` | polling on a timer, threshold bands, trend arrows, a loading/ready/error state machine |
| `loadtest` | `./mill examples.loadtest.run` | concurrent background work streamed back to the render thread, with a live histogram and percentiles |

`counter` is the idiom to copy for anything real: bindings declared once drive dispatch, the status-bar hints, the
help overlay and the `Ctrl+P` command palette together. `hello-world` deliberately stays one level lower, on a local
`onKeyEvent` handler, which is the escape hatch for a key that belongs to a single element.

Each of `procmon`, `airsensor` and `loadtest` has a step-by-step guide that builds it from
nothing: see [Build a real app](../website/docs/build-a-process-monitor.md) in the docs.

Native binaries: `./mill show examples.<name>.nativeImage` (GraalVM community 23.0.1,
`--no-fallback`, no reflect-config needed). CI builds every one of them and runs each binary
headless. The example list is *derived* from the directories on disk, so adding
`examples/<name>/package.mill` is the only step needed to get the new example compiled, tested
and built as a native binary. The three that use `java.net.http` — `weather`, `airsensor` and
`loadtest` — need no extra native configuration on this toolchain.

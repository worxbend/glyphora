---
title: Async work & timers
description: Safely connect HTTP, disk work, polling, timers, and animations to glyphora's render thread.
---

# Async work & timers

Terminal apps still fetch data, read files, poll services, and animate. glyphora
keeps those operations predictable with one rule: **do slow work off-thread; mutate
UI signals on the render thread**.

The `Async` helpers enforce that handoff for you.

## Model loading explicitly

A small state enum produces a clearer UI than parallel `loading`, `error`, and
`value` flags:

```scala
private enum LoadState[+A]:
  case Idle
  case Loading
  case Ready(value: A)
  case Failed(message: String)

private val users = Signal[LoadState[Vector[User]]](LoadState.Idle)
```

Render each state in one place:

```scala
def usersView(using ReactiveScope, Theme): Element = users.get match
  case LoadState.Idle            => text("Press r to load users.").dim
  case LoadState.Loading         => spinner("loading…")
  case LoadState.Ready(values)   => list(values.map(_.name), userList)
  case LoadState.Failed(message) => text(s"Load failed: $message").fg(Color.Red)
```

## Run blocking work safely

`Async.runCatching` executes work on a daemon worker and always delivers its result
back on the render thread:

```scala
private def reload(): Unit =
  users.set(LoadState.Loading)
  Async.runCatching(api.fetchUsers()) {
    case Right(value) => users.set(LoadState.Ready(value))
    case Left(error)  => users.set(LoadState.Failed(error.getMessage))
  }
```

Because the callback is already on the render thread, setting `users` is safe and
automatically schedules the next render.

Use `Async.run` when your API already models errors in its result:

```scala
Async.run(api.fetchReport()) { result =>
  report.set(result)
}
```

If the work itself throws, `Async.run` hands the throwable back to the render loop
that armed the call. The runner absorbs it the way it absorbs any failing queued
body — the app keeps running — and reports it when the app exits, as
`RunnerError.QueuedTask` carrying the count and the stack traces. Take reporting over
by putting an `AsyncErrorHandler` in scope:

```scala
given AsyncErrorHandler = AsyncErrorHandler.onRenderThread(error => failure.set(Some(error.getMessage)))
```

`onRenderThread` and `toRenderThread()` can be long-lived givens: `Async.run`
binds their destination anew for each invocation, on the thread that arms it.
Results and errors stay with that invocation's runner, even when two runners share
the policy. A late delivery to a stopped owner is dropped, not rerouted to a surviving
runner. Start the call from `onStart`, a handler, or another owner-thread callback.

Direct `policy.handle(error)` calls outside `Async.run` do not get this per-invocation
binding: `toRenderThread()` uses the loop captured when the policy was constructed,
while `onRenderThread` uses ordinary `RenderThread.runLater` routing at the call.
Custom worker-side handlers, `ignore`, and `rethrow` keep their own behavior.

`AsyncErrorHandler.rethrow` throws on the worker thread instead. Avoid it in a
terminal app: the JVM prints the stack trace to standard error, which is the tty the
UI is drawn on, so the trace lands on top of the alternate screen and the frame diff
never repaints over it.

## Schedule one-shot and repeating work

```scala
import scala.concurrent.duration.*

val dismiss: Cancelable = Async.after(2.seconds) {
  notice.set(None)
}

val poller: Cancelable = Async.every(10.seconds) {
  reload()
}

// Stop work when the owning screen closes.
poller.cancel()
```

Both callbacks run on the render thread. Returned `Cancelable` handles make
lifecycle ownership explicit and prevent a screen that no longer exists from
continuing to update app state.

## Use the app tick for frame-oriented work

Set `RunnerConfig.tickRate` when the whole application benefits from a regular
heartbeat:

```scala
import scala.concurrent.duration.*

override def config = RunnerConfig(tickRate = Some(100.millis))

private val frame = Signal(0)

override def onTick(): Unit =
  frame.update(_ + 1)
```

Key, mouse, and tick handlers already execute on the render thread, as do `onStart`
and `onStop`. A tick that changes no signal causes no dependent computation to
change; keep expensive work out of `onTick` itself.

## Stopwatches and countdowns

`Stopwatch` and `Timer` are caller-owned utilities advanced by the tick loop:

```scala
import io.worxbend.tui.runtime.Timer
import scala.concurrent.duration.*

private val timer = Timer(30.seconds)

override def config = RunnerConfig(tickRate = Some(100.millis))

override def onTick(): Unit =
  timer.tick(100.millis)
  if timer.justExpired() then notify("Time is up", NoticeLevel.Warning)

def timerView: Element = text(timer.formatted)
```

`justExpired()` returns `true` once, on the transition to zero, so side effects do
not repeat on every later tick.

## Integrate an existing callback API

If a library owns its worker thread, marshal the callback manually:

```scala
client.onMessage { message =>
  RenderThread.runOnRenderThread {
    messages.update(_ :+ message)
  }
}
```

`runOnRenderThread` runs immediately when already on the UI thread and queues
otherwise. `runLater` always queues for the next loop iteration.

## When a continuation throws

A queued continuation is your code, and your code throws — `result.toOption.get` on a
failed request is enough. That no longer takes the app down: the render loop catches a
non-fatal throwable from a queued body, reports it, and keeps draining, so the bodies
queued behind it (a timer tick, a second in-flight request) still run in order.

By default the runner accumulates these failures and returns them once the app has
exited, as `Left(RunnerError.QueuedTask(QueuedTaskFailures(first, count)))`: `count` is
how many bodies failed in total — a continuation that throws on every tick reports as
the flood it was, not as one incident — and the later throwables are attached to
`first` as suppressed exceptions, up to a bounded number, so their stack traces survive
without a long-lived app hoarding them. If the backend then fails and ends the loop,
`Left(RunnerError.Backend(error, queuedTasks))` carries the same report rather than
erasing it.

Install a handler to see each failure as it happens instead — reporting is then
entirely yours, and `run` returns `Right` unless the backend itself failed. Keep the
handler total: a handler that throws is not isolated, and takes the loop down with it.

```scala
override def config: RunnerConfig =
  RunnerConfig(onTaskError = Some(error => lastError.set(Some(error.getMessage))))
```

The handler runs on the render thread, inside the drain, so it may set signals
directly. Fatal errors (anything outside `scala.util.control.NonFatal`) still
propagate and end the loop.

## Failure and lifecycle checklist

- Model loading and failure in state; do not let worker exceptions vanish.
- Cancel scheduled work when its screen or app feature is no longer active.
- Never block in `view`, a key handler, `onTick`, or an `Async` completion callback.
- Capture plain data in worker closures; terminal widgets and buffers belong to the
  render path.
- Keep daemon work idempotent when retry is possible.

The complete HTTP example is
[`examples/weather`](https://github.com/oleksandr-balyshyn/glyphora/tree/main/examples/weather).
Run it with `./mill examples.weather.run`.

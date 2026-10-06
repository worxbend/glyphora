# tui-test

The headless test harness, published as `io.worxbend::tui-test` for use as a **test-only**
dependency. The repository directory is `test-support/`; the Scala package is
`io.worxbend.tui.testsupport`.

- **`Pilot`** — the headless end-to-end driver: starts an app over a `HeadlessBackend`
  on a background thread, then
  `press` / `pressKey` / `typeText` / `click` / `mouseDown` / `mouseUp` / `mouseMove` / `drag` /
  `scrollUp` / `scrollDown` / `resize` / `waitForIdle` / `waitUntil` / `waitForDraws` /
  `screenLines` / `awaitTermination` from the test.
- **`BufferAssertions`** — render-to-`Buffer` helpers: `rendered(widget, w, h)`,
  `rendered(statefulWidget, state, w, h)`, `renderedInto(widget, area, w, h)`,
  `lines` / `trimmedLines` / `text` / `line` (wide-grapheme continuation cells skipped, so
  expected strings read like the terminal).
- **`GoldenFrames`** — whole-frame snapshots: `assertMatches(name, buffer)` compares against
  `golden/<name>.txt` on the test classpath, and `GLYPHORA_GOLDEN_UPDATE=<dir>` records
  instead of comparing. The fixture holds glyphs and layout only — styling is not in it, so
  pin colours and modifiers with cell assertions alongside.

```scala
Pilot.using(Size(40, 10))(backend => app.runWith(backend)) { pilot =>
  pilot.waitForIdle().typeText("hi").press("enter").waitForIdle()
  assert(pilot.screenText.contains("hi"))
}
```

`using` always requests non-consumable runner cancellation, including after a failing
assertion. `close()` waits at most 2 seconds; `close(timeout)` accepts a custom finite
deadline. A test-body failure stays primary if shutdown also fails. Use
`readOnRenderThread` while the pilot is live for thread-confined state; it targets
only that pilot's owner and rejects reads after termination. See the
[testing guide](../website/docs/testing.md).

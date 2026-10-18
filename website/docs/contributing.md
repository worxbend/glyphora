---
title: Contributing
description: Set up glyphora locally, understand quality gates, add a widget, improve docs, and prepare a focused pull request.
---

# Contributing

Contributions are welcome across runtime behavior, widgets, examples, tests,
documentation, and design. The project favors small typed layers, visible ownership,
and tests that exercise the same buffers users see.

:::tip In a hurry?

[`CONTRIBUTING.md`](https://github.com/worxbend/glyphora/blob/main/CONTRIBUTING.md)
at the repository root is the short version: the exact command block to run before
pushing, and the six checks that gate a merge. This page is the long version — the
architecture rules a change has to respect, how to add a widget end to end, and how to
write the pull request.

:::

## Set up the repository

Needs JDK 25 or newer — every published artifact declares `-release:25`. `./mill` pins
its own build process to `temurin:25` via `.mill-jvm-version` regardless of what is on
`PATH`, so a mismatched ambient JDK will not silently produce 21-compatible class files,
but a real JDK 25+ install is still required to run the `./mill` launcher itself.
Building an example's native image additionally needs GraalVM `graalvm-community:25.0.2`
(Mill downloads it), which has no macOS x64 build past 25.0.1 — override
`TuiExampleModule.jvmVersion` in `build.mill` locally on an Intel Mac.

```bash
git clone git@github.com:worxbend/glyphora.git
cd glyphora

./mill __.compile

# tests, one module at a time — the sequence CI runs
./mill core.test
./mill terminal.test
./mill widgets.test
./mill runtime.test
./mill macros.test
./mill dsl.test
./mill test-support.test
```

Every `examples/<name>` module has a suite too (`./mill examples.showcase.test`, and so
on); CI runs one job per example, discovered from the `examples/` directory.

Run the tests per module rather than as `./mill __.test`. That is what CI does, and
for a reason worth knowing: a single combined step only ever reports "still running",
so a module that hangs cannot be told apart from a module that is merely slow. One
command per module names the culprit immediately.

The build uses Mill. Every Scala module has a `package.mill`; shared Scala version,
strict compiler flags, ScalaTest wiring, and publication metadata live in
`build.mill`. Three of its five shared traits carry the repetition: `TuiSupportTests` is a test
submodule with `test-support` on its classpath, `TuiPilotTests` is the same shape under
a name that says it drives a whole app through `Pilot`, and `TuiExampleModule` is
everything an `examples/<name>` module needs — the GraalVM pin, `--no-fallback`, and
that test submodule — so an example's own `package.mill` declares only its
`moduleDeps` and its `mainClass`.

Useful development commands:

```bash
# Apply Scala formatting
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources

# Verify formatting without changing files
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources

# Run one module or example suite
./mill widgets.test
./mill examples.todo-list.test

# Manual terminal test bed
./mill examples.showcase.run

# Render-loop sanity check
./mill widgets.test.runMain io.worxbend.tui.widgets.RenderLoopBench
```

## Workflow and release checks

Workflow tooling needs Python 3 and the pinned YAML parser. Keep these dependencies
in a local environment rather than changing the system Python:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/requirements-ci.txt
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s scripts/tests -v
bash scripts/check-workflows.sh
python scripts/verify-packaged-consumer.py
```

The workflow check downloads a checksum-verified actionlint binary for Linux x64 and
checks action pins, timeouts, permissions, and the complete exact-tag publication gate.
The packaged-consumer check resolves all seven staged artifacts outside the source
module graph. Neither command publishes remotely. `python scripts/ci-examples.py discover`
lists the validated examples; `smoke-native` checks their built binaries with a bounded
non-TTY run.

## Know the quality rules

CI enforces constraints that protect the design:

- no `java.lang.reflect` or `Class.forName` in main Scala sources — the examples' sources
  included, since those are what people copy from;
- no `String.substring` for layout math outside `CharWidth` (the weather example's JSON
  parser is excluded by name: it indexes offsets in an ASCII wire format, which is not
  layout math);
- warnings are errors (`-deprecation -feature -unchecked -Wunused:all -Werror`);
- Scalafmt owns formatting;
- all tests run headlessly on Linux, which is the only platform CI covers;
- every example compiles with GraalVM `--no-fallback`, and every binary is then run with
  no TTY and has to exit `1` printing `terminal not supported` — the CI list is derived
  from `examples/*/package.mill`, so adding a module is enough.

For general Scala conventions, read
[`SCALA_CODE_STYLE.md`](https://github.com/worxbend/glyphora/blob/main/SCALA_CODE_STYLE.md).

## Add a widget

1. **Choose state ownership.** A stateless widget implements `Widget`; an interactive
   or scrollable renderer uses `StatefulWidget[S]`, with state owned by the caller.
2. **Order the constructor parameters the same way every other widget does:** required
   data first, then `style`, then any specialised styles, then glyph/symbol overrides.
   A reader can then tell content from appearance at the call site without opening the
   widget.
3. **Implement in `widgets/`, in its own file named after the widget.** Depend only on
   `tui-core`. Render inside the supplied `Rect`, clip safely, and route all
   visible-width logic through `CharWidth`. A render rule two widgets share belongs in a
   `private[widgets]` object named for the concept, not copied into both.
4. **Test the buffer.** Cover empty and tiny areas, normal content, truncation,
   Unicode, selection/focus style, and state boundaries with `BufferAssertions`.
5. **Add the DSL element.** Put its retained data and built-in key/mouse behavior in the
   node-family file it belongs to (`LayoutElements.scala`, `ChoiceElements.scala`, and so
   on — `Element.scala` holds only the `Element` trait). Declare `type Self` as the node's
   own type so the fluent builders keep giving it back. Add a factory in
   `ElementFactories.scala` and export it from `dsl.scala`.
6. **Test interaction.** Use `Pilot` for focus, keys, mouse behavior, resize, and
   redraw when the widget is interactive.
7. **Document it.** Add it to [Widget catalog](./widgets), provide a short realistic
   snippet, and use it in an example when it introduces a new pattern.

Keep user handlers ahead of built-in behavior and return `false` when an event should
continue bubbling.

## Add or change a public API

- Put the API in the lowest layer that can own it without creating an upward
  dependency.
- Prefer data types, sealed ADTs, and direct calls over runtime discovery.
- Write Scaladoc that explains ownership, thread constraints, and edge behavior—not
  only parameter names.
- Add tests at the owning layer and an integration test when multiple layers must
  cooperate.
- Update the task guide and API usage examples in the same pull request.

## Improve the documentation

Canonical guide Markdown lives in `website/docs/`. Docusaurus publishes it to
GitHub Pages and `scripts/export-wiki.mjs` turns the same pages/navigation into the
GitHub Wiki.

```bash
cd website
npm ci
npm run build

cd ..
node scripts/export-wiki.mjs --output build/wiki
```

Follow [`docs/STYLE_GUIDE.md`](https://github.com/worxbend/glyphora/blob/main/docs/STYLE_GUIDE.md):
lead with the reader's outcome, explain why, include a verified snippet, name the
terminal-specific pitfalls, and point to the next useful guide. Assets shared by the
README, Pages, and Wiki belong in `docs/assets/`.

When adding a page, add its ID to `website/docs-navigation.mjs`; that one navigation
tree drives both Docusaurus and the generated Wiki sidebar.

## Prepare a pull request

- Keep the change focused and explain the user-visible outcome first.
- Include commands you ran and any terminal/emulator used for manual verification.
- Add screenshots only when the change is genuinely visual; prefer text/buffer
  assertions for behavior.
- Call out public API and native-image implications explicitly.
- Do not commit generated `website/build` or `build/wiki` output.

Before pushing:

```bash
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources
./mill __.fix --check          # main *and* test sources
./mill __.compile
./mill core.test
./mill terminal.test
./mill widgets.test
./mill runtime.test
./mill macros.test
./mill dsl.test
./mill test-support.test
(cd website && npm run build)
node scripts/export-wiki.mjs --output build/wiki
```

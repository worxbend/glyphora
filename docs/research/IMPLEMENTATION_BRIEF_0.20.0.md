# glyphora 0.20.0 — implementation brief for the research backlog

Date: 2026-10-18. Source: [`TUI_LANDSCAPE_AND_IDEAS.md`](TUI_LANDSCAPE_AND_IDEAS.md) and its appendix. This brief is
the convergent half of the idea-refinement pass: it turns the catalogue into a scoped release, names what is not being
built and why, and fixes the design decisions every ticket shares. The ticket graph that implements it lives outside
the repository (scratch, `issues/*.md`); its results land on the `integration/0.20.0` branch.

## Problem statement

How might we make glyphora the toolkit a JVM developer reaches for both for a full-screen app *and* for an ordinary
CLI, closing the correctness gaps the review found, without giving up the four things that already set it apart:
reflection-free native images, fine-grained signals, exact headless testing, and Unicode correctness?

## Who this is for

- **App authors** on the JVM building dashboards, monitors, editors and AI-coding CLIs: they need grid layout,
  a real editor, key sequences, diff/Markdown rendering, and a cursor screen readers can follow.
- **CLI authors** who will never write a full-screen app: they need `Console.print`, prompts, progress columns, and a
  defined answer to "stdout is a pipe".
- **Maintainers and contributors**: they need SVG export so docs screenshots cannot drift, an inspector, and a
  `doctor` to answer "my keys don't work" without a debugging session.

Success for the release: every bug in section 3 of the research doc fixed with a regression test; every idea in
sections 4–5 either shipped or listed under "Not doing" below with a reason; all seven modules and every example green
under the full CI gate, including `--no-fallback` native builds; one "Migrating to 0.20.0" section that covers every
behaviour change.

## Recommended direction

Ship the catalogue as one minor release, grouped into vertical tickets that each cut through core → widgets → dsl →
docs → tests. The order follows the roadmap in section 7 of the research doc: bugs and foundations first (per-node
bounds recording, the hardware caret, `each` identity), then the reach features (export, print mode, protocols,
key sequences, Markdown, editor), then composition and layout (grid, alignment, `Constraint.Auto`, custom containers,
component styles), then the larger bets that fit in the release (pixel images, mouse selection, virtual lists).

Breaking changes are allowed by the pre-1.0 policy and each one is recorded in the migration notes. The ones decided
up front:

- **`each` scopes hooks** (E2): `each(items)(key)(render)` also enters `keyed(key)`; code that used both keeps working.
- **`style` means content style everywhere** (E8): containers get `frameStyle` for their border. `panel(...).style(s)`
  moves to `.frameStyle(s)` where it meant the border.
- **`Theme.auto`** becomes the default theme of `TuiApp` when the terminal reports a background colour; apps that set
  `theme` explicitly see no change.
- **Undo** in `TextArea` stores coalesced diffs; `TextAreaState` keeps its public methods.

## Shared design decisions (binding for every ticket)

1. **Bounds recording** is one opt-in `NodeBounds` recorder filled during paint by the DSL host; every consumer
   (click-outside, inspector, Pilot-by-key, `useArea`, element-scoped effects, text selection) reads it. Nobody adds a
   second bounds mechanism.
2. **`test-support` cannot see `dsl`.** Pilot-by-key is `TuiApp`/`ElementHost` exposing `boundsOf(key): Option[Rect]`
   plus `Pilot.click(rect: Rect)`; no dsl import in `tui-test`.
3. **No new published module** in this release. Everything lands in the existing seven artifacts.
4. **Encoders stay in `terminal`**; print mode and export call them from `dsl`/`runtime`/`core` as the tier allows
   (`BufferExport` is pure and lives in `core`).
5. **Macros derive, never reflect**: `Codec` derivation for persisted signals and `Pretty` derivation for the
   inspect widget live in `tui-macros` with their own typeclass traits; glue that needs both a macro and a runtime
   type lives in `dsl`.
6. **Options bundles** for every new widget past eight defaulted knobs, from day one.
7. **Docs with the code**: every new factory is documented on `website/docs/widgets.md` (or the page the ticket names)
   in the same commit, because `DocumentedFactorySpec` fails `dsl.test` otherwise.

## Key assumptions to validate

- [x] A paint-time bounds recorder costs under 5% of frame time on `RenderLoopBench` — measure in the ticket.
  **Did not hold; decision recorded.** Measured on `ElementHostBench` (`RenderLoopBench` has no element tree to
  record): +6–9% at 80x24, +4–5% at 200x50, +8–10% on a 2 000-row tree, about 0.08 µs per node, after the
  recorder stopped copying its buffer per publish. Recording stays opt-in, switched on only by a consumer that reads
  it; the numbers and the list of consumers are in `website/docs/performance.md`.
- [ ] OSC 11 replies can be routed to a probe without re-opening the Alt+`]` decoder trade-off — ticket 05 tests it.
- [ ] Kitty/iTerm2 graphics work through `Backend.writeRaw` + `DiffDirective.Skip` as designed — ticket 25 verifies
  with a `HeadlessBackend` capture of the raw bytes.
- [ ] `java.util.logging.Handler` is enough as the log bridge under native image (no `ServiceLoader`).

## Not doing (and why)

| Idea | Reason |
|---|---|
| C8 SSH serving (`tui-ssh`) | New module with Apache MINA SSHD whose `--no-fallback` native build is unverified; a release of its own. |
| C9 browser backend, M2 Scala.js / Scala Native cross-build | Needs a cross-build of `core`/`widgets` under `-release:25` and JDK-only APIs; a build-system project, not a feature. |
| M3 Windows | Needs a Windows CI runner and manual terminal testing; cannot be verified from this environment. |
| K5 `tui-cats-effect` / `tui-zio` / `tui-ox` | New artifacts with third-party runtime dependencies; an ecosystem decision for a later release. |
| A7 slim `tui-print` artifact | Splitting the encoder out of `tui-terminal` changes the published module set and that module's API. |
| A6 SLF4J artifact | Same reason; the `java.util.logging` bridge and `logConsole` ship, SLF4J users attach `jul-to-slf4j`. |
| J10 bidirectional text | Low value for the effort; changes how every line is composed. |
| Embedded terminal widget | Needs a VT parser and a PTY; a project of its own. |
| Section 6 of the research doc (CSS engine, live reload, z-index, annotation processor, Elm layer) | Rejected by the research itself. |
| C7 Sixel | Kitty and iTerm2 cover the terminals that matter; Sixel's palette quantisation is a later addition. |

## Open questions carried into tickets

- Whether `Markdown` stays "a viewer, not an engine": decided yes for tables, nested lists, task lists and streaming;
  no for images and raw HTML.
- Whether `Prompt.*` reads from `/dev/tty` when stdin is a pipe: ticket 21 tests the JLine `.system(true)` path and
  documents the answer in `website/docs/faq.md`.

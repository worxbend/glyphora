---
title: FAQ
description: Answers about glyphora's scope, modules, terminals, state model, testing, Unicode, accessibility, reflection, and release status.
---

# Frequently asked questions

Short answers to architectural and adoption questions. For symptoms and fixes, use
[Troubleshooting](./troubleshooting).

## What kinds of applications is glyphora for?

Interactive full-screen or region-based terminal programs: dashboards, deployment
tools, file browsers, forms, monitors, database clients, developer utilities, and
focused CLI companions. It is not a line-oriented argument parser or shell-command
framework; pair it with one if your program also has non-interactive commands.

## Is glyphora a Scala port of another TUI framework?

No. It borrows proven ideas—immutable descriptions, buffer rendering, reactive
state, and backend isolation—but its API is designed around Scala 3 capabilities,
compile-time derivation, and GraalVM constraints.

## Which module should an application depend on?

Use `tui-dsl` for normal applications. It transitively includes core, widgets,
runtime, terminal integration, and macros. Depend on a lower layer only when building
custom infrastructure such as a backend or widget library without the reactive DSL.

## Can I use widgets without signals or TuiApp?

Yes. `tui-widgets` depends only on `tui-core`. Render a `Widget` or
`StatefulWidget[S]` directly into a `Buffer` and drive it with your own backend or
loop. The DSL wraps the same renderers; it is not a parallel implementation.

## Which terminals are supported?

**The assumed minimum is an xterm-compatible terminal** that understands the DEC
private modes glyphora sets — `1049` (alternate screen), `25` (cursor visibility),
`2004` (bracketed paste), `1004` (focus reporting), `1006`/`1002`/`1000` (mouse) — plus
SGR colours. Escape sequences are hardcoded rather than read from terminfo, with one
exception: the alternate screen is gated on terminfo's `smcup`, so a terminal without
one (the Linux console) fails loudly at startup instead of painting over your
scrollback.

Output is always written as UTF-8, regardless of the process locale. Every border and
glyph in `tui-widgets` is non-ASCII, and there is no ASCII fallback border set.

This table records what was **actually verified**, not what is expected to work. Empty
cells mean untested — contributions welcome.

**Pending re-verification after the 0.14.0 JLine 3 → 4 bump.** JLine 4.1.3 changed what
entering raw mode does to signal delivery (see `terminal/package.mill`'s dependency
comment); the fix keeps this backend's *observable* behavior the same by pinning
`org.jline.terminal.softwareSignals=false` explicitly, but that has only been checked
by automated tests against `HeadlessBackend`, which does not construct a real JLine
`Terminal` at all. The ✅ cells below for **Restore on Ctrl+C** were captured against
JLine 3 and have not yet been re-run on a real PTY against JLine 4.

| Environment | Renders | Alt screen | Restore on exit | Restore on Ctrl+C | Truecolor | UTF-8 |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Linux PTY, `TERM=xterm-256color` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Linux PTY, `TERM=linux` | ✅ | n/a — refused, no `smcup` | ✅ | | | ✅ |
| Linux PTY, unknown `TERM` | ✅ | ✅ | ✅ | | | ✅ |
| Linux PTY, no terminfo database | ✅ | ✅ | ✅ | | | ✅ |
| Linux PTY, `LC_ALL=C` | ✅ | ✅ | ✅ | | | ✅ |
| GraalVM native binary, Linux PTY | ✅ | ✅ | ✅ | | ✅ | ✅ |
| xterm / kitty / WezTerm / Alacritty | | | | | | |
| tmux / screen | | | | | | |
| macOS Terminal.app / iTerm2 | | | | | | |
| Windows Terminal / conhost | | | | | | |

Colour depth, hyperlink (OSC 8), clipboard (OSC 52), focus reporting and emoji width
all remain emulator- and font-dependent. glyphora detects a missing controlling TTY —
piped output, `TERM=dumb`, a backgrounded process group — and exits with a message
rather than writing escape sequences into your log file.

## Does it work on Windows?

Unknown — glyphora targets Linux and CI no longer builds or tests on Windows. Nothing
in the toolkit is deliberately Linux-only, and JLine supports Windows, so it may well
work; but nobody is checking, so treat it as unverified rather than supported. If you
run it there, a report either way is welcome.

## Can widgets be tested without image snapshots?

Yes. Widgets render into a `Buffer`; string helpers expose normalized lines and
text. `Pilot` covers full event/render loops and produces deterministic text screens
for ordinary ScalaTest assertions.

`Pilot`, `BufferAssertions` and `GoldenFrames` ship as `com.worxbend::tui-test`, so add
it in the test configuration only. `HeadlessBackend` is part of the terminal module and
can be driven directly downstream.

## Why are Signal writes restricted to the render thread?

A single writer makes rendering deterministic and avoids locks throughout widget
code. Background work can run anywhere; only its final UI state mutation hops back
to the render thread.

## How do I perform HTTP or disk work?

Use `Async.run` or `Async.runCatching`. Work runs on a daemon worker; its completion
is marshalled onto the render thread where it can safely update signals. For a
third-party callback, use `RenderThread.runOnRenderThread`. See
[Async work & timers](./async-and-timers).

## Does Unicode support include emoji and CJK?

Yes. Width calculation accounts for combining marks, East Asian wide characters,
variation selectors, flags, and emoji ZWJ sequences. Custom widgets must use
`CharWidth` rather than `String.length` for layout math.

## What accessibility support exists?

Every built-in interactive widget has a keyboard path, focus is explicit and
themeable, `Theme.HighContrast` ships with the DSL, and `Form.accessible` spells out
field position, checkbox state, and errors. Terminal screen-reader behavior varies,
so applications should avoid color-only status and keep command flows simple. See
[Unicode & accessibility](./unicode-and-accessibility).

## Is runtime reflection required?

No. Runtime reflection is forbidden by CI. Form and action derivation happens at
compile time through Scala 3 macros, keeping native-image builds free of reflection
configuration.

## Can I style every widget through the DSL?

Style-aware elements support fluent modifiers and semantic themes. Raw `widget(...)`
leaves and raster images own their rendering and intentionally ignore inherited
element styles. Use their constructor's style parameters at that level.

## Is glyphora stable?

The project is pre-1.0. Patch releases preserve APIs; minor releases may break them.
Core types are treated as the stability anchor, but applications should pin an exact
version and read release notes before a minor upgrade. See [Versioning](./versioning).

## Where is the complete API reference?

The [Scaladoc API](pathname:///api/) is generated for every published module and
bundled into the GitHub Pages deployment. This guide explains concepts and tasks;
Scaladoc is the source for exact signatures.

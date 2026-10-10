---
title: Versioning & releases
description: Understand glyphora's pre-1.0 compatibility policy, synchronized module versions, Maven coordinates, and release process.
---

# Versioning & releases

glyphora is pre-1.0. Patch releases preserve public APIs; minor releases may make
breaking changes as the application layer matures. `tui-core` is the stability
anchor and has received additive changes only since `0.2`.

Pin an exact version in applications and review release notes before moving between
minor versions.

## Current coordinates

All published modules share one synchronized version under `io.worxbend`:

```scala
// Mill
def mvnDeps = Seq(mvn"io.worxbend::tui-dsl:0.17.0")
```

```scala
// sbt
libraryDependencies += "io.worxbend" %% "tui-dsl" % "0.17.0"
```

Applications normally need only `tui-dsl`. Lower-tier artifacts are `tui-core`,
`tui-terminal`, `tui-widgets`, `tui-runtime`, and `tui-macros`; `tui-test` carries the
headless test harness and belongs in the test configuration only.

**Nothing is on Maven Central yet.** A source release tag does not make artifacts
available on Maven Central. Until `0.17.0` is published there, the coordinates above
resolve only after `./mill __.publishLocal` has put them in your local Ivy cache — see
[Getting started](./getting-started#1-add-glyphora). `0.17.0` is the synchronized release
version. Check [Maven Central](https://search.maven.org/search?q=g:io.worxbend)
and the [release tags](https://github.com/oleksandr-balyshyn/glyphora/tags) separately
before choosing a version.

## Migrating to 0.17.0

- Directory trees and file pickers no longer acquire filesystem data while painting.
  Load the initial listing explicitly; acquire background snapshots with `DirectoryListing.load`
  and install them on the owning render thread. See [Widgets](./widgets).
- Reactive graphs belong to one live render loop. Capture `RenderThread.capture()` before
  registering an external callback, then dispatch mutations through `owner.execute`.
  See [State and signals](./state-and-signals).
- Render queues and shutdown drains have finite callback budgets. Do not depend on an
  arbitrarily large backlog completing during shutdown. Optional `Async.scope()` groups
  task cancellation, but does not interrupt blocking I/O. See [Async and timers](./async-and-timers).

This release also fixes deferred keyed state, navigation focus identity, cross-span
Unicode rendering, enhanced keyboard restoration, and async error visibility. It adds
source-aware table caching and a custom interactive-widget adapter.

## Compatibility policy

| Change | Patch release | Minor release before 1.0 |
|---|---:|---:|
| bug fix preserving signatures | yes | yes |
| additive widget or method | yes, when low risk | yes |
| source-breaking rename/removal | no | possible, documented |
| behavior change with migration work | no | possible, documented |
| raised minimum JDK / toolchain requirement | no | possible, documented |
| binary compatibility guarantee | not yet | not yet |

`0.14.0` raises the minimum JDK from 21 to 25: every published artifact declares `-release:25`, so a class
file built by this version will not load on JDK 21–24 (`UnsupportedClassVersionError`). Consumers pinned to
an older JDK should stay on `0.13.0` until they can move.

MiMa binary-compatibility gates are planned once a first published baseline is
selected. Until then, recompile downstream code on upgrade even when moving between
patch versions.

### Types that will break when they grow

Adding a field to a `case class` changes the signatures of `apply`, `copy` and
`unapply`, so downstream code compiled against the old shape fails with
`NoSuchMethodError` — recompiling fixes it, but only if you know to. These public
types are the ones most likely to gain fields, and are the reason the table above says
"not yet" rather than "yes":

| Type | Module | Why it will grow |
|---|---|---|
| `Style` | `tui-core` | new text attributes as terminals gain them |
| `ElementProps` | `tui-dsl` | every new element modifier lands here |
| `Theme` | `tui-dsl` | new semantic roles |
| `RunnerConfig` | `tui-runtime` | new loop options |
| `Layout` | `tui-core` | new constraint or flex behaviour |

Before 1.0 these should move to a `final class` with a private constructor plus `with*`
builders — the builder style `Style` already uses — so a new field is additive. The
genuinely closed value types (`Position`, `Size`, `Rect`, `Cell`, `KeyEvent`,
`MouseEvent`) are expected to stay `case class`es: their shape is fixed by what a
terminal cell and a coordinate are.

## Release process

Releases are Git tags named `vX.Y.Z`. Pushing a tag runs the Publish workflow. Before
signing credentials are made available, it calls the same read-only CI workflow used
for main and pull requests on the tag's exact commit. Compilation, module and example
tests, formatting/lints, discipline checks, native images, the site build, and packaged
consumer validation must all pass. Publication checks out the validated SHA and refuses
to upload if it differs from the tag commit or if the tag version differs from `build.mill`.

The packaged-consumer gate stages all seven artifacts in a temporary Maven repository,
checks their coordinates and exact production dependency edges, rejects leaked test
libraries, and builds/runs a consumer outside the source module graph. That consumer
resolves `tui-dsl` in production and `tui-test` only in its test configuration, exercises
external derivation and DSL imports, and checks the resolved jars against the staged
artifacts. Run it locally with `python3 scripts/verify-packaged-consumer.py`; it does not
publish remotely or modify the normal local Maven/Ivy repository.

Only after these gates does Publish upload every `TuiPublishModule` to Maven Central
with the synchronized version and signed POM metadata. The separate Autofix workflow
remains limited to same-repository pull requests; release validation never rewrites the
commit being validated. After Autofix pushes with `GITHUB_TOKEN`, maintainers must
approve the token-generated full CI run for the resulting PR commit before merging.
The Autofix job's library tests and checks from the previous SHA are not substitutes
for full validation of that commit. If no run appears, create an ordinary maintainer
push to trigger it; do not merge based only on the pre-formatting checks.

Before a tag, maintainers should verify:

- formatter, compile, and complete test suite;
- public guide and Scaladoc changes;
- example JVM runs and native-image CI;
- module version in `build.mill`;
- migration notes for any source or behavior change.

## License

[MIT](https://github.com/oleksandr-balyshyn/glyphora/blob/main/LICENSE) — use glyphora
in open-source or commercial software, keeping the copyright and license notice.

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
def repositories = Seq("https://maven.pkg.github.com/worxbend/glyphora")
def mvnDeps = Seq(mvn"io.worxbend::tui-dsl:0.18.1")
```

```scala
// sbt
libraryDependencies += "io.worxbend" %% "tui-dsl" % "0.18.1"
```

Applications normally need only `tui-dsl`. Lower-tier artifacts are `tui-core`,
`tui-terminal`, `tui-widgets`, `tui-runtime`, and `tui-macros`; `tui-test` carries the
headless test harness and belongs in the test configuration only.

The seven libraries are distributed through
[GitHub Packages](https://github.com/worxbend/glyphora/packages) at
`https://maven.pkg.github.com/worxbend/glyphora`. Every consuming machine needs a
classic personal access token with `read:packages`; see
[Getting started](./getting-started#1-add-glyphora) for Mill and sbt authentication.
Maven Central publication is separate. `0.18.1` is the synchronized release version.
Local installation with `./mill __.publishLocal` also works.

## Migrating to 0.18.0

- Async submissions reserve continuation capacity before accepting work. Saturated worker,
  owner, or runnerless queues throw `RejectedExecutionException` synchronously instead of
  accumulating an unbounded backlog. Catch overload at the submission boundary and reduce
  demand; do not retry in a tight loop. Cancellation suppresses unadmitted callbacks, not
  blocking I/O. Repeating timers coalesce ticks while a callback is pending or running.
  See [Async and timers](./async-and-timers).
- Builders beneath the last full-screen presentation are no longer evaluated. Keep resource
  acquisition in lifecycle hooks, not view builders. Eager covered-screen hook state is retained;
  responsive subtrees that disappear keep their existing reset semantics. See [App shell](./app-shell).
- `RunnerConfig` adds opt-in `onFrameTiming`. Recompile consumers against the updated case-class
  shape. `LatestFrameObserver` offers bounded, serial, latest-frame sampling for slow observers:
  it drops superseded pending frames and is not a lossless recorder. See [Performance](./performance)
  and [Testing](./testing).

Paragraphs now produce wrapped rows incrementally and reuse stable measurements. Tree
flattening caches are caller-owned, invalidate on source/expansion changes, and do not force
collapsed offscreen children. Procmon reuses its table projection until the immutable sample
changes. These remove repeated work; they are not claims of an application-wide speedup.

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

The **Publish GitHub Packages** workflow (`.github/workflows/publish-packages.yml`)
runs on `v*` tag pushes and can also be started manually with a `version` input.
For a manual run, select the ref to publish and supply the version declared by all
seven modules at that ref; the workflow publishes that ref's validated commit.
It calls the complete read-only CI workflow, checks out only its validated SHA,
and verifies every module version before exposing the built-in `GITHUB_TOKEN`.
Only the publishing job receives `packages: write`; no additional repository secret
or GPG signing key is needed for GitHub Packages.

The publisher uploads `tui-core`, `tui-terminal`, `tui-widgets`, `tui-runtime`,
`tui-macros`, `tui-dsl`, and `tui-test` with their POMs, JARs, sources and Scaladoc.
After upload, it downloads all 28 files, checks their hashes against the validated
build, and runs the isolated DSL/macro and test-support consumer using a fresh
Coursier cache and the remote resolver. Missing artifacts or incorrect transitive
dependencies fail the release workflow.

To publish the current main commit manually:

```sh
gh workflow run publish-packages.yml --ref main -f version=0.18.1
```

Treat released versions as immutable. Increment the shared `publishVersion`
before another release; a manual run is for a version that has not yet been uploaded.
If an upload partially succeeds, inspect the package versions and workflow logs
before retrying.

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

[MIT](https://github.com/worxbend/glyphora/blob/main/LICENSE) — use glyphora
in open-source or commercial software, keeping the copyright and license notice.

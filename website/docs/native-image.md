---
title: Native binaries
description: Compile glyphora applications into self-contained GraalVM executables with no fallback image and no reflection configuration.
---

# Ship a native binary

glyphora treats GraalVM compatibility as an architecture rule, not a late packaging
experiment. The runtime does not scan classes or use reflection; Scala 3 macros
generate direct form and action wiring during compilation.

The result is a self-contained terminal executable built with `--no-fallback` and no
`reflect-config.json`.

## Build an example

```bash
./mill examples.showcase.nativeImage
```

The example module declares only what is specific to it:

```scala title="examples/showcase/package.mill"
package build.examples.showcase

import mill.*

// Everything an example has in common — the GraalVM pin, `--no-fallback`, and a Pilot-capable
// test submodule — lives in `TuiExampleModule` in build.mill.
object `package` extends build.TuiExampleModule {

  def moduleDeps = Seq(build.dsl)

  def mainClass = Some("io.worxbend.tui.examples.showcase.Main")
}
```

The GraalVM pin (`jvmVersion = "graalvm-community:25.0.2"`) and `nativeImageOptions =
Seq("--no-fallback")` are not repeated in each example: they live once in the shared
`build.TuiExampleModule` trait in `build.mill`, so bumping the toolchain is a single edit
that cannot be half-applied across ten files. `build.dsl` is the only dependency an example
names, because Mill's `moduleDeps` are transitive and `tui-dsl` already brings `tui-core`,
`tui-terminal`, `tui-widgets`, `tui-runtime` and `tui-macros`.

Mill resolves the configured GraalVM toolchain and writes the executable under the
module's `out/.../nativeImage.dest/` directory.

## Add native-image to your module

```scala title="build.mill"
import mill.*, scalalib.*, javalib.NativeImageModule

object app extends ScalaModule with NativeImageModule:
  def scalaVersion = "3.9.0"
  def mvnDeps = Seq(mvn"io.worxbend::tui-dsl:0.17.0")
  def mainClass = Some("example.Main")
  def jvmVersion = "graalvm-community:25.0.2"
  def nativeImageOptions = Seq(
    "--no-fallback",
    // Only if you hand the binary to other machines — see "Build a binary other people can run".
    "-march=compatibility",
    "-Os",
  )
```

Then build:

```bash
./mill app.nativeImage
```

Keep `--no-fallback`: without it, native-image may silently produce a launcher that
still needs a JVM, hiding compatibility problems until release.

## Build a binary other people can run

Two flags decide whether the executable starts at all on someone else's CPU. Neither is
on by default, and the failure they prevent looks nothing like a bug in your program.

### `-march=compatibility`

On AMD64, GraalVM defaults to `-march=x86-64-v3`. That microarchitecture *level* is a
named bundle of instruction-set extensions — AVX, AVX2, BMI1, BMI2, FMA and others — that
Intel shipped with Haswell (2013) and AMD with Excavator (2015). Compiling for it lets the
optimizer emit those instructions directly, which is faster, and also means the binary
cannot run without them.

A user on an older or cut-down CPU does not get a slow program or a helpful message. The
binary refuses to start and prints a wall of missing feature names:

```text
The current machine does not support all of the following CPU features that are required
by the image: [CX8, CMOV, FXSR, MMX, SSE, SSE2, SSE3, SSSE3, SSE4_1, SSE4_2, POPCNT,
LZCNT, AVX, AVX2, BMI1, BMI2, FMA, F16C].
Please rebuild the executable with an appropriate setting of the -march option.
```

The exact list is whichever features the level you built for requires; the point is that
it names instructions, not anything about your application, so a bug report for it will
arrive describing your program as "broken on my machine".

`-march=compatibility` targets the baseline every AMD64 CPU supports instead. The binary
gives up some throughput and runs everywhere. For a terminal UI — which spends its time
waiting on a human and writing a few kilobytes of ANSI per frame — that trade is free in
practice.

To see the levels this toolchain knows about:

```bash
native-image -march=list
```

### `-Os`

`-Os` optimizes for size rather than peak speed. Adding both flags used to take the
`hello-world` example from 19.54 MB to 18.11 MB; that comparison predates the 0.14.0
toolchain bump (GraalVM 25.0.2, JLine 4) and has not been re-measured against it — the
default build alone (neither flag) is 22.37 MB total / 21.11 MB on disk under the new
toolchain, for scale. `-march=compatibility`/`-Os` are still worth having when the
artifact is something people download, and unimportant when it is not.

Neither flag is set on this repository's own examples. CI builds and runs them on the same
runner, so the default `-march` is correct there and the size never leaves the machine.
Add both when you are shipping.

## Why zero reflection matters

Closed-world compilation must know every reachable class and method. Reflection and
dynamic class loading conceal that graph, which usually leads to hand-maintained
configuration files that drift as code changes.

glyphora avoids the problem:

- `deriveForm[A]` uses Scala 3 `Mirror` at compile time and emits direct calls;
- event, widget, and state types are ordinary sealed/data structures;
- service discovery and runtime classpath scanning are absent;
- CI rejects `java.lang.reflect` and `Class.forName` in main Scala sources.

This rule also improves JVM behavior: fewer hidden code paths, clearer dependencies,
and errors that appear during compilation instead of at runtime.

## Test the executable without a TTY

CI does not keep a list of examples to build. It discovers them — every directory under
`examples/` that contains a `package.mill` — and builds a native executable for each, so
an example added without touching the workflow is still covered. It then launches every
binary in a headless job and requires two things of each: an exit status of `1`, and the
message `BackendError.UnsupportedTerminal` renders. A hang, a stack trace, or a
corrupt raw-mode setup fails all of them.

```bash
./out/examples/showcase/nativeImage.dest/native-executable
# no TTY: prints "glyphora: terminal not supported: dumb terminal (no TTY attached)"
# and exits 1
```

For behavior tests, keep using `HeadlessBackend` on the JVM. Native CI is a packaging
and reachability gate; the headless suite provides fast interaction coverage.

## Diagnose build failures

1. Confirm the JVM build and tests first:

   ```bash
   ./mill app.compile
   ./mill app.test
   ```

2. Build the smallest failing application with `--no-fallback` still enabled.
3. Inspect your dependencies for runtime reflection, JNI, dynamic proxies, resource
   lookup, or classpath scanning.
4. Prefer direct registration or compile-time derivation over adding broad native
   configuration.
5. If a third-party library truly needs GraalVM metadata, keep that metadata beside
   the application and test it in CI.

## Release checklist

- build on the same OS and architecture you distribute, and pin the *microarchitecture
  level* too: pass `-march=compatibility` so the binary does not require the AVX2/BMI2
  instructions GraalVM assumes by default (see [Build a binary other people can
  run](#build-a-binary-other-people-can-run)) — building on your own AMD64 machine is not
  enough to make it run on every AMD64 machine;
- add `-Os` if download size matters more than peak throughput;
- run `--help` and a headless startup smoke test;
- run the binary in a real terminal and verify resize, mouse, paste, and cleanup;
- compare any filesystem/resource lookup with the JVM version;
- preserve license notices for bundled dependencies;
- publish a checksum with downloadable artifacts.

Read [Architecture](./architecture#tui-macros) for the compile-time layer and
[Troubleshooting](./troubleshooting#native-image-compilation-fails) for failure
triage.

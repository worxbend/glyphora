# Contributing to glyphora

Thanks for looking. Contributions are welcome across runtime behaviour, widgets,
examples, tests, documentation, and design.

**The full guide lives at [website/docs/contributing.md](website/docs/contributing.md)**
(published at <https://worxbend.github.io/glyphora/contributing>). It covers
setting the repository up, the architecture rules a change has to respect, how to add a
widget end to end, and how to write a pull request.

This file exists so that GitHub can find it — GitHub only looks for `CONTRIBUTING.md`
at the repository root, in `.github/`, or in `docs/` — and so that the checks that
actually gate a merge are one click away rather than something you discover from a red
CI run.

## Run this before you push

```bash
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources
./mill __.fix --check
./mill __.compile
./mill core.test
./mill terminal.test
./mill widgets.test
./mill runtime.test
./mill macros.test
./mill dsl.test
./mill test-support.test
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/requirements-ci.txt
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s scripts/tests -v
bash scripts/check-workflows.sh
python scripts/verify-packaged-consumer.py
(cd website && npm ci && npm run build)
node scripts/export-wiki.mjs --output build/wiki
```

Run the tests **one module at a time**, not as `./mill __.test`. That is what CI does,
and for a reason worth knowing: a single combined step only ever reports "still
running", so a module that hangs cannot be told apart from a module that is merely
slow. One command per module names the culprit immediately.

Each `examples/<name>` module has a suite too — `./mill examples.showcase.test`, and so
on. CI runs one job per example, discovered from the `examples/` directory, so adding
`examples/<name>/package.mill` is all it takes to get a new example compiled, tested,
and built as a native binary.

If formatting or lints are the only thing failing, `./mill
mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources` and `./mill __.fix` apply
them for you. On a pull request opened from a branch in this repository, CI applies
both and pushes the result itself; forks have to run them locally.

## The six gates a merge has to pass

These are unusual enough to be worth stating up front. Every one of them fails the
build rather than producing a warning.

1. **No runtime reflection in main sources.** `java.lang.reflect` and `Class.forName`
   are grepped out of every module's `src/main/scala` *and* every example's. This is
   what keeps the GraalVM native images reflect-config-free. Anything that needs to
   reach user-defined code goes through `tui-macros`, which derives it at compile time.
2. **No `String.substring` in main sources**, outside `core/CharWidth.scala`. All
   display-width, truncation, and layout arithmetic goes through `CharWidth`, which
   understands grapheme clusters, CJK width, emoji ZWJ sequences, and combining marks.
   A `substring` gets those wrong silently. One further file is excluded by name —
   `examples/weather/.../Json.scala`, a hand-written JSON parser indexing offsets in an
   ASCII wire format that is never drawn. A new exemption needs an argument of that
   shape, written at the call site.
3. **Scalafmt** — `.scalafmt.conf`, 120 columns, Scala 3 dialect.
4. **Scalafix** — `.scalafix.conf`, syntactic rules, applied to test sources as well as
   main ones. A genuine exception carries an inline `// scalafix:ok DisableSyntax; <why>`
   on the offending line saying why.
5. **Warnings are errors** — `-deprecation -feature -unchecked -Wunused:all -Werror`.
   An unused import fails the build.
6. **Every example builds as a GraalVM native image** with `--no-fallback`, and every
   binary is then run with no TTY and has to exit `1` after printing
   `terminal not supported`. Both halves are checked: a binary that prints the message
   and then hangs, and one that exits 1 for some unrelated reason, each fail the job.

## Code of conduct

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

# Repository quality review

## Executive summary

- Two medium-priority structural findings are eligible for behavior-preserving work: duplicated histogram classification and scattered runner outcome bookkeeping. Characterization must pass on the original implementation before either refactor.
- Potential correctness/security fixes are proposals only. In particular, stacked-chart numeric overflow, authenticated cross-origin redirects, and graphics bookkeeping on failed output must not be mixed into this pass.
- Dependency direction, caller-owned widget state, compile-time derivation, Unicode width ownership, and render-thread confinement are deliberate strengths; no framework or public-API redesign is warranted.
- Low-priority cleanup outside an accepted batch is deferred, as requested. Generated sources, third-party assets/launcher, lockfiles and build outputs are excluded from edits.
- Fresh baseline and final evidence are recorded below. No coverage percentage or Sonar complexity score is claimed: this checkout configures neither coverage instrumentation nor Sonar analysis.

## Scope, isolation and method

Review date: 2026-10-22. Original branch: `main`. Start commit: `e80bb81ab4e59bf1eb0563cf00b82e5682145ab1` (tag `v0.21.0`). The starting tree was clean. Work is isolated on local branch `refactor/quality-pass-20261022`; no pushing, rebasing, release/version changes, or history rewriting is authorized.

Inventory: 1,348 tracked files, 1,337 included text/configuration/fixture paths, 11 explicitly excluded tracked paths. Exclusions are `core/.../WidthTable.scala`, `widgets/.../WorldTable.scala`, the four generated `docs/assets/gallery/*.svg` screenshots, the third-party `mill` launcher, `website/package-lock.json`, and three vendored font files under `website/static/api/fonts/`. Ignored outputs (`out/`, `.bloop/`, `.bsp/`, `.scala-build/`, `build/wiki/`, caches, virtual environments, etc.) are not tracked review inputs. No applied database migrations were found. Generators themselves are handwritten review inputs; their generated Scala is not. Existing golden fixtures are test oracles, not regeneration targets.

Five read-only audit tracks covered core/terminal, runtime/DSL, widgets, macros/test-support/all examples, and scripts/tools/site/docs/build configuration. Detailed source inspection was targeted by history and ownership boundaries, supplemented by whole-scope inventory, configured static checks and tests. This is not an assertion that every line or every input combination was exhaustively reviewed. Findings below are independently checked against the cited source; unexecuted reproductions are labeled.

### Toolchain and commands

- Scala 3.9.0, Mill 1.1.9, ScalaTest 3.2.20, JDK release floor 25; `.mill-jvm-version` pins Mill to Temurin 25 and example builds use GraalVM CE 25.0.2.
- Compiler flags: `-deprecation -feature -unchecked -Wunused:all -Werror -release:25` (`build.mill:25-32`).
- Scalafmt 3.11.5; configured Scalafix rules are `DisableSyntax` and `NoValInForComprehension`. Deliberately disabled style-guide rules are not findings.
- Python tooling uses standard `unittest` and the existing PyYAML dependency. `/usr/bin/python3` is Python 3.14.4 with PyYAML 6.0.3; PATH's other Python lacks PyYAML. No dependency was added.
- Available interactive Node/npm are v26.7.0/11.19.0. The gate prepends `/usr/bin` for the declared Python dependency; effective gate Node/npm were also v26.7.0/11.19.0, verified explicitly.
- CI includes compile/test, format/lint/meta-build format, reflection/width discipline, workflow/action policy, Scala CLI, packaged consumer, examples/gallery/native smoke, and site tests/audit/build. No ADR directory was found. Read README, CONTRIBUTING, CLAUDE, relevant style/contribution/versioning guides, build manifests and workflows.

To bound memory and avoid stale daemon state, all direct Mill gates use this external wrapper, not a repository/build change:

    JAVA_TOOL_OPTIONS='-Xmx2g -XX:+UseSerialGC' ./mill --no-server -j 2 <selectors>

`GLYPHORA_GOLDEN_UPDATE` is unset. Scratch artifacts live under `/home/worxbend/.hermes/cache/scratch/glyphora-quality-20261022/` (subject to normal scratch retention). Exact argv, cwd, exit code, duration and full logs are saved by `gate.py` in each run's `results.json`.

## Phase 0 baseline

Completed baseline: **43/44 gate steps passed**. Compilation, formatting, Scalafix, all other module/example suites, Python/Node tests, workflow validation, quickstart, packaged consumer, gallery drift, site audit/build, Wiki export and all twelve native builds/smokes passed. The only failing step was `dsl.test`; this is not a green whole-repository baseline.

Exact failing set:

- `io.worxbend.tui.dsl.PixelImageElementSpec` — `drawn once, silent while idle, taken down under a dialog and put back after it`; `PixelImageElementSpec.scala:33`, `0 did not equal 1`.

DSL will not be refactored. Runtime's 319 tests and loadtest's six tests passed; those two scopes are eligible. The full baseline ran 7,464 Scala tests (one failed), 94 Python tests and three Node tests. Each module's Scala totals: core 3,286; terminal 595; widgets 1,686; runtime 319; macros 69; DSL 1,335; test-support 124. The twelve examples ran 50 tests in total. These numbers exclude the deliberate second gallery drift invocation.

`npm audit --audit-level=critical --json` exited 0, reporting 48 affected-package entries: 31 high, 15 moderate, two low, zero critical. Counts include affected parents and are not independent exploitable paths. No dependency/lockfile change was made.

Compiler warnings remain errors and compilation passed. Captured logs contain 2,495 lines matching the word warning, overwhelmingly repeated JVM/toolchain warnings across test-class forks; this is a raw diagnostic-text measure, not 2,495 source defects. Scalafix separately emitted two existing UnusedScalafixSuppression warnings (`core/.../CharWidth.scala:139:24`, `dsl/.../DeferredViewStateSpec.scala:159:81`). They do not fail the configured check and remain untouched. Coverage instrumentation is absent, so statement/branch coverage is unavailable rather than 0% or an invented percentage.

All 1,337 inventoried included files remained hash-identical through baseline execution; only this new, untracked review was being authored. Effective gate Node/npm were v26.7.0/11.19.0; this is not CI Node24 coverage. Evidence: `<scratch>/baseline/{results.json,summary.json,concise.json,source-end.json}` and per-step logs.

Commands derived from CI/build configuration:

    ./mill --no-server -j 2 __.compile + __.test.compile
    ./mill --no-server -j 2 mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources
    ./mill --no-server -j 2 __.fix --check
    ./mill --no-server -j 2 <module>.test
    ./mill --no-server -j 2 examples.<discovered-example>.test
    PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 -m unittest discover -s scripts/tests -v
    PATH=/usr/bin:$PATH bash scripts/check-workflows.sh
    /usr/bin/python3 scripts/verify-scala-cli.py
    /usr/bin/python3 scripts/verify-packaged-consumer.py
    MILL=<external-wrapper> bash scripts/render-gallery.sh --check
    (cd website && npm test && npm audit --audit-level=critical --json && npm run build)
    node scripts/export-wiki.mjs --output <scratch>/baseline/wiki
    ./mill --no-server -j 2 examples.<discovered-example>.nativeImage
    /usr/bin/python3 scripts/ci-examples.py smoke-native

Each library module is tested separately: core, terminal, widgets, runtime, macros, dsl, test-support. Examples are derived from `scripts/ci-examples.py discover`, not a hand-maintained subset. Twelve example suites and twelve native-image tasks are included. Full test failures, if any, remain baseline failures; their owning modules will not be refactored.

Additional executed checks: native Scalafmt 3.11.5 `--check build.mill <20 tracked package.mill files>` passed; 13 Python AST parses, four `bash -n` checks and six non-JSX `node --check` checks passed. The CI-equivalent main-source reflection and substring-boundary scans found zero violations. Syntax checks are not semantic/security analysis. ShellCheck/Pyflakes are intentionally disabled by the actionlint wrapper. Ruff and sonar-scanner are unavailable; SONAR_HOST_URL/SONAR_TOKEN are unset. No Python/JS semantic linter or coverage task is configured. No tools were added to the build.

### Hotspots

`git log --since=12.months --format= --name-only` was counted programmatically over included tracked paths. The proxy below multiplies commits mentioning the path by lexical branch-token occurrences in the file. It includes comments/strings and is not cognitive complexity, cyclomatic complexity, a Sonar score, or a measured performance cost. It ranks inspection, not automatic violations.

| Path | Commits | Lines | Branch-token proxy | Product |
| --- | ---: | ---: | ---: | ---: |
| `dsl/.../TuiApp.scala` | 98 | 1264 | 213 | 20874 |
| `terminal/.../InputDecoder.scala` | 42 | 1194 | 328 | 13776 |
| `dsl/.../ElementFactories.scala` | 91 | 1418 | 106 | 9646 |
| `terminal/.../JLine3Backend.scala` | 65 | 914 | 143 | 9295 |
| `dsl/.../Focus.scala` | 53 | 718 | 172 | 9116 |
| `core/.../Buffer.scala` | 50 | 891 | 141 | 7050 |
| `runtime/.../TerminalRunner.scala` | 39 | 736 | 158 | 6162 |
| `examples/loadtest/.../RunStats.scala` | 2 | 156 | 25 | 50 |

Large node-family/export files and protocol decoders were examined, but line count or exhaustive sealed matching alone is not an actionable smell. The two accepted medium findings have concrete policy-ownership costs independent of heuristic thresholds.

## Architecture sketch

Production module dependencies (verified against `moduleDeps`, excluding test-only edges):

    core: no glyphora dependency
    macros: no glyphora dependency
    terminal -> core
    widgets -> core
    runtime -> core, terminal
    test-support -> core, terminal, runtime
    dsl -> core, terminal, widgets, runtime, macros
    each Mill example -> dsl

The Scala CLI quickstart consumes the DSL artifact without becoming a Mill module. Runtime owns runner/thread/async lifecycle; terminal owns backend I/O/protocols; core owns geometry/text/events/buffer and pure layout; widgets render only into Buffer. Compile-time macro expansion is intentionally independent of core. The website is a static Docusaurus/React build, not a deployed Node application server. Scripts implement workflow policy, release verification, docs/assets export and generation.

No production upward dependency or module cycle was established. Test-only helper dependencies do not imply a production cycle. No boundary needs a new runtime dependency or public interface for this pass. Entry points include `TuiApp.main`, example `Main` objects, the Scala CLI `Main`, script CLIs and website pages.

## Phase 1 findings

Location line numbers below refer to the starting commit unless a final location is supplied in Phase 4. `partial` means related assertions exist but the exact maintenance boundary lacks direct characterization; it is not a coverage percentage. `manual` means source/test inspection, not an analyzer-reported rule. Severity reflects the observed consequence and confidence; approval-only concerns are not silently promoted to proven exploits.

| ID | Severity | Category | Location | Evidence / consequence | Principle | Proposed refactoring or proposal | Risk | Effort | Test coverage | Source |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R01 | Medium | Maintainability / tests | `examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala:78-92,144-156` | Incremental and rebuilt histograms duplicate the exact bucket-index expression at lines 88 and 151, despite the equivalence promise at 73-76. App tests never compare bucket counts/edges. Arithmetic changes have two owners and no direct oracle. | Fowler Duplicated Code; Beck no duplication; Feathers characterization | Characterize current arithmetic/partition behavior, then Extract Function into the existing Histogram owner with non-public visibility. Preserve operand order, rounding, overflow/invalid-input behavior. | Low | S | partial | manual |
| R02 | Medium | Maintainability / lifecycle | `runtime/src/main/scala/io/worxbend/tui/runtime/TerminalRunner.scala:69-92,123-157` | Primary/escaping/cleanup failure policy is spread across local state, catch arms and the backend-close resource block. Maintaining release sequencing requires simultaneously reconstructing three error-classification branches. | Fowler Extract Class; Martin separation of concerns; Feathers seams | Extract a private run-owned outcome helper; keep acquisition, release ordering, final drain and public RunnerError unchanged. | Medium | M | partial | manual |
| L01 | Low | Duplication | `core/src/main/scala/io/worxbend/tui/core/Buffer.scala:610-616,635-641` | snapshot and copyFrom separately enumerate the same cell/continuation/directive/placement planes. A new plane must be added twice. Current copies agree. | Fowler Extract Method | Defer private copy-state helper; strengthen all-plane/nonzero-origin characterization first. | Low | S | partial | manual |
| L02 | Low | Long function | `core/src/main/scala/io/worxbend/tui/core/GridLayout.scala:116-156` | Sparse slot search is nested in reservation/cursor bookkeeping. The widened row and next-row-prefix rule are easy to disturb. No current placement bug alleged. | Fowler Extract Method | Defer naming the private next-slot search; keep arithmetic and iteration intact. | Medium | S | partial | manual |
| L03 | Low | Duplication | `dsl/src/main/scala/io/worxbend/tui/dsl/Hooks.scala:62-67`; `dsl/src/main/scala/io/worxbend/tui/dsl/ViewState.scala:335-343` | useMemo and memoIfActive duplicate MemoCell refresh policy; strict admission and plain-builder fallback deliberately differ. | Fowler Extract Method | Defer a shared private memo update after exception-order characterization; do not make writes transactional. | Medium | S | partial | manual |
| L04 | Low | Long function | `dsl/src/main/scala/io/worxbend/tui/dsl/ElementHost.scala:447-505` | Press observers, focus transfer and capture delivery are interleaved. Their evaluation order is observable; fold must still notify every observer. | Fowler Extract Method | Defer two private named dispatch stages, not a middleware framework. | Medium | S | partial | manual |
| L05 | Low | Duplication | `widgets/src/main/scala/io/worxbend/tui/widgets/GroupedBarChart.scala:126-129`; `widgets/src/main/scala/io/worxbend/tui/widgets/ColumnChart.scala:49-52` | Grouped label painter repeats the existing Unicode truncation/centering helper already used by other charts. | Fowler Substitute Algorithm / reuse helper | Defer replacing private duplicate with existing ColumnChart painter after Cell-level characterization. | Low | S | partial | manual |
| L06 | Low | Duplication | `widgets/src/main/scala/io/worxbend/tui/widgets/DataTable.scala:635-637,651-653` | Flat and multiline row paths repeat highlight gutter policy; continuation rows differ deliberately. | Fowler Extract Method | Defer private gutter helper; preserve callbacks, source indexing, style layering and footer behavior. | Low | S | partial | manual |
| L07 | Low | Allocation / intention | `examples/gallery/src/main/scala/io/worxbend/tui/examples/gallery/Main.scala:49-54` | Immutable page sidebar labels are reconstructed on each view. This is source-visible repeated work, not a measured bottleneck. | Hoist Invariant Computation | Defer private cached label collection; never cache page views or mutable element state. | Low | S | partial | manual |
| L08 | Low | Duplication / diagnostics | `test-support/src/main/scala/io/worxbend/tui/testsupport/GoldenFrames.scala:148-156` | Styled mismatch parses the expected ANSI buffer twice. Only failing diagnostics pay the cost. | Fowler Introduce Explaining Variable | Defer reusing the parsed expected buffer; preserve report/error formatting. | Low | S | yes | manual |
| P01 | Medium | Conditional security | `scripts/verify-packaged-consumer.py:41-53` | Default urllib redirects retain explicitly supplied Authorization across hosts. An unexpected redirect from the trusted package service could forward credentials. No live leak observed. | Security trust boundary | Approval-only origin-scoped redirect policy, with same-origin/cross-origin/downgrade tests. | Medium | S | partial | manual; offline standard-library behavior |
| P02 | Critical | Reliability / numeric bounds | `widgets/src/main/scala/io/worxbend/tui/widgets/StackedBarChart.scala:24,54-64` | Positive Long totals can overflow; narrowing a saturated scaled Long to Int can become negative and move the next segment outside the widget's owned Rect. Source-derived small-input counterexample; no Scala widget reproduction yet. | Reliability; bounded rendering ownership | Approval-only overflow-safe totals and height clamping before narrowing. Do not alter chart output here. | Medium | S | partial | manual |
| P03 | Medium | Reliability / failure state | `terminal/src/main/scala/io/worxbend/tui/terminal/GraphicsLayer.scala:52-69`; `terminal/src/main/scala/io/worxbend/tui/terminal/JLine3Backend.scala:202-212,233-239` | Graphics shown state advances during compose before successful output; text baseline commits after output. Recoverable ordinary graphics-write failure could suppress retry bytes. Normal runner may terminate and a PrintWriter may latch failure, limiting exposure. | Prepare/commit boundary | Approval-only recoverable writer reproduction and graphics accounting/redraw policy. | Medium | M | partial | manual |
| P04 | Medium | Tooling reliability | `scripts/render-tapes.sh:17-18,28-32`; `test-support/src/main/scala/io/worxbend/tui/testsupport/Tape.scala:63-64` | Relative tape output resolves in the forked test sandbox, not necessarily the checkout directory announced by the script. Odd trailing arguments are also silently ignored. Static call-chain evidence, not end-to-end reproduction. | Explicit filesystem boundary | Approval-only absolute export path and argument validation, each covered by isolated shell tests. | Low | S | partial | manual |
| P05 | Medium | Deployment input boundary | `.github/workflows/docs.yml:8-24`; `website/docusaurus.config.js:16-17` | Pages serves docs/assets but asset-only commits do not match its trigger paths; site can stay stale. | Consistent build/deploy inputs | Approval-only docs/assets trigger and policy mutation test; CI changes forbidden here. | Low | S | no | manual |
| P06 | Low | Published content drift | `website/src/pages/index.js:153-155`; `build.mill:121` | Prominent homepage dependency says 0.18.3; synchronized release is 0.21.0. Existing Markdown version gate does not inspect JSX. | Single source of truth | Approval-only content/version-check correction; no global historical-version replacement. | Low | S | no | manual |
| P07 | Medium | Test reliability / uncertain completion | `examples/weather/src/test/scala/io/worxbend/tui/examples/weather/WeatherAppSpec.scala:66-87` | A fixed 750ms sleep is not proof the older request's Async continuation was delivered under load. No flake reproduced. | FIRST; observable synchronization | Propose a controlled client plus a real queued-completion observation; do not replace negative assertion with waitForIdle or add a production hook. | Medium | M | partial | manual |
| P08 | Low | CI-policy duplication | `scripts/validate-workflows.py:111-144` | Central and Packages validation repeat the same SHA/gate/version-order policy, with distinct Packages-only conditions. | Fowler Extract Function | Proposal only: even behavior-preserving CI-policy-tool changes require approval under this task. Pin exact ordered diagnostics first. | Medium | S | partial | manual |
| P09 | Low | Boundary test gaps | `scripts/export-wiki.mjs:27-65,85-108,138-155`; `scripts/style-scaladoc.mjs:28-76`; `tools/generate-width-table.py:32-63`; `tools/generate-world-table.py:72-106` | Existing integration/property tests do not directly characterize malformed frontmatter/title collisions, bridge storage failures or generator parsing/quantization inputs. No production failure established. | Feathers characterization | Defer independent dependency-free test-only follow-ups; do not change generated data or accepted formats. | Low | M | partial | manual |
| P11 | Medium | Existing example arithmetic behavior | `examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala:88,122,151,155` | Extreme latency/bucket arithmetic can wrap edges and summary sums or produce a negative array index. R01 characterization intentionally pins these current outcomes; changing them is not refactoring. Scala execution is pending. | Explicit numeric contract | Approval-only overflow/validation policy with a separate expected-safe-output regression. | Medium | S | partial | manual |
| P12 | Medium | Baseline test failure | `dsl/src/test/scala/io/worxbend/tui/dsl/PixelImageElementSpec.scala:22-33` | Fresh baseline failed the first kitty-draw assertion: 0 did not equal 1. The test waits for loop idle before an asynchronous encode, while a later case explicitly waits for encoded output. No production cause or repeatability is established. | FIRST; observable async completion | Record exact failing set; DSL is excluded from refactoring. A separate investigation must distinguish timing from graphics delivery before any approved correction. | Medium | S | yes | tool:ScalaTest |
| P10 | Low | Example boundary test gaps | `examples/weather/src/main/scala/io/worxbend/tui/examples/weather/Json.scala:34-183`; `examples/airsensor/src/main/scala/io/worxbend/tui/examples/airsensor/Sensor.scala:106-120`; `examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/ProcessSource.scala:55-79` | App tests inject clients/sources or permit fallback; they do not fully characterize real parser/HTTP/process boundaries. No concrete production bug established. | Feathers seams; tests at I/O boundary | Defer per-example characterization, without a shared HTTP/parser framework, new dependency or stricter accepted data. | Low | M | partial | manual |

Additional tool findings: existing unused suppression warnings are **L09 / Low / tool:Scalafix**, at `core/src/main/scala/io/worxbend/tui/core/CharWidth.scala:139:24` and `dsl/src/test/scala/io/worxbend/tui/dsl/DeferredViewStateSpec.scala:159:81`. Removing them is deferred because neither file belongs to an accepted batch and DSL failed baseline. Existing npm advisories are **P13 / Medium / security / tool:npm audit**; location `website/package.json:17-58` and the excluded lockfile's resolved graph. Concrete evidence and risk boundaries are recorded above and in `website/DEPENDENCY_SECURITY.md:19-35`. Dependency remediation requires approval and compatibility/browser verification, not an autonomous forced upgrade.

### Leave as-is

- Exhaustive sealed-ADT matches, immutable records and caller-owned `*State` are idiomatic Scala, not automatic Repeated Switches/Data Class smells.
- Local render/decoder/Fenwick loops and private mutable arrays encode measured or bounded hot paths. Do not replace them with allocation-heavy pipelines for appearance.
- Unicode segmentation across spans, continuation/directive planes, frame delta versus absolute ANSI export, and styled-fixture versus streaming ANSI parsing have different contracts. Similar loops do not justify merging them.
- EventPump wake/registration order, stdin/stdout descriptor ownership, terminal dressing/release sequencing and cleanup aggregation must remain explicit. Closing every wrapper or combining cleanup policies would change behavior.
- Derivation stays compile-time only. Macro expansion helpers that appear unused locally are consumed by generated user code and are not dead code.
- Node-family files, factory exports, example-local clients and small copyable demos need no speculative framework or new abstraction.
- TextArea shares caret/paint geometry and uses a wrapped-line index; DiffView already bounds word-emphasis work; PNG decoding already checks expansion budgets and releases inflater resources. Old, already-repaired findings are not re-reported as new defects.
- LineRenderer and buffer width math remain CharWidth-owned. No reflection, substring exemption or lint suppression is added.
- Test suites use independent forks for process-global render/clock state. Do not weaken tests, regenerate goldens or change fork policy to obtain green checks.
- Website dependency risk must be judged against its documented static-site/build boundary. No forced audit upgrade, lockfile edit or new runtime dependency is authorized.

### Open questions

1. Should fatal teardown errors escape even when an earlier RunnerError exists? Current recordCleanup preserves the earlier Left and attaches the fatal secondary, while fatal final-drain errors escape. R02 must pin and preserve that distinction, not decide policy.
2. Which authenticated artifact redirect destinations, if any, are intentionally supported? P01 needs that trust decision before a fix.
3. Which overflow-safe scale semantics should stacked charts promise at Long extrema? P02 requires explicit behavior approval.
4. Is failed backend output recoverable on a given handle, and should graphics accounting support retries independently of PrintWriter's error latch? P03 needs a targeted failure model.
5. Which existing observable boundary can prove weather Async completion without sleeps or a new production/public test hook? P07 is not safely addressed by merely changing timing.
6. No live kitty/IME/screen-reader validation, Windows/macOS verification, browser visual pass or JDK26 run is implied by the local Linux/JDK25 gate.

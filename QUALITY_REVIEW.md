# Repository quality review

## Executive summary

- Approval-only risks remain: stacked-chart numeric overflow/rectangle containment, conditional authenticated cross-origin redirect forwarding, and graphics accounting after failed output. None was silently fixed or presented as a proven remote exploit.
- Final verification passed **44/44 steps and 7,480 Scala tests**. The original baseline had one DSL test failure; it did not recur, but DSL was untouched and no fix is claimed.
- Completed both safely addressable medium structural findings: one histogram classifier and a private runner-outcome owner, each behind separately committed, passing characterization tests. Sixteen tests were added.
- Public library APIs, dependencies, wire formats, build/CI configuration, generated sources, fixtures and the original main branch remain unchanged. Low-priority unrelated cleanup and approval-only corrections are deferred.
- Coverage and Sonar complexity are unavailable, not estimated. Existing lint/dependency/Scaladoc warnings and local-platform verification limits are explicitly reported below.

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

`GLYPHORA_GOLDEN_UPDATE` is unset. Scratch artifacts live under `/home/worxbend/.hermes/cache/scratch/glyphora-quality-20261022/` (subject to normal scratch retention). For full gates, exact argv, cwd, exit code, duration and log paths are saved by `gate.py` in each run's `results.json`; full logs are sibling files. Batch `results.json` records argv, exit, duration, test totals and failure lines, with logs as siblings and the repository root as cwd.

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
| P11 | Medium | Existing example arithmetic behavior | `examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala:88,122,151,155` | Extreme latency/bucket arithmetic can wrap edges and summary sums or produce a negative array index. R01 characterization intentionally pins these current outcomes; changing them is not refactoring. Scala characterization now passes before and after R01; these outcomes were preserved, not repaired. | Explicit numeric contract | Approval-only overflow/validation policy with a separate expected-safe-output regression. | Medium | S | partial | manual |
| P12 | Medium | Baseline test failure | `dsl/src/test/scala/io/worxbend/tui/dsl/PixelImageElementSpec.scala:22-33` | Fresh baseline failed the first kitty-draw assertion: 0 did not equal 1. The test waits for loop idle before an asynchronous encode, while a later case explicitly waits for encoded output. No production cause or repeatability is established. | FIRST; observable async completion | Record exact failing set; DSL is excluded from refactoring. A separate investigation must distinguish timing from graphics delivery before any approved correction. | Medium | S | yes | tool:ScalaTest |
| L09 | Low | Existing lint diagnostics | `core/src/main/scala/io/worxbend/tui/core/CharWidth.scala:139:24`; `dsl/src/test/scala/io/worxbend/tui/dsl/DeferredViewStateSpec.scala:159:81` | Configured Scalafix emits two UnusedScalafixSuppression warnings. Their owners are outside accepted batches; DSL failed baseline. | Clear intentional exceptions | Defer unused-suppression cleanup; no suppression was added or removed here. | Low | S | yes | tool:Scalafix |
| L10 | Low | API documentation links | `runtime/src/main/scala/io/worxbend/tui/runtime/Runner.scala:403,419,616`; `runtime/src/main/scala/io/worxbend/tui/runtime/TerminalRunner.scala:28`; `dsl/src/main/scala/io/worxbend/tui/dsl/AnimationClock.scala:38` | Final packaged-consumer regeneration surfaced 142 Scaladoc warnings, predominantly unresolved member-link queries. Baseline reused cached docs and did not emit them. The cited source links are unchanged; no runtime/compile failure resulted. | Accurate generated documentation | Defer a focused documentation-link pass. No build flags, published APIs or broad cross-module docs were changed to quiet this output. | Low | M | partial | tool:Scaladoc |
| P13 | Medium | Dependency security boundary | `website/package.json:17-58`; `website/DEPENDENCY_SECURITY.md:19-35`; excluded resolved lockfile | Both actual npm audits report 48 affected-package entries (31 high, 15 moderate, two low), zero critical. Static-site/build boundary narrows exposure; this is not proof of zero vulnerabilities. | Explicit dependency trust boundary | Approval-only compatible dependency remediation with consumer, build and browser checks; never force upgrades or edit the excluded lockfile in this pass. | Medium | M | partial | tool:npm audit |
| P10 | Low | Example boundary test gaps | `examples/weather/src/main/scala/io/worxbend/tui/examples/weather/Json.scala:34-183`; `examples/airsensor/src/main/scala/io/worxbend/tui/examples/airsensor/Sensor.scala:106-120`; `examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/ProcessSource.scala:55-79` | App tests inject clients/sources or permit fallback; they do not fully characterize real parser/HTTP/process boundaries. No concrete production bug established. | Feathers seams; tests at I/O boundary | Defer per-example characterization, without a shared HTTP/parser framework, new dependency or stricter accepted data. | Low | M | partial | manual |

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

## Phase 2 plan

### Decisions and order

Only R01 and R02 are accepted implementation findings. They are the safely addressable medium structural findings, and both owning baselines passed. No high-priority structural blocker was established. The lower-risk R01 literal extraction precedes the higher-churn R02 lifecycle extraction. Each theme touches two files at most. Test and refactor commits remain separate; this review/plan is committed separately from code.

All L01-L10 findings remain deferred: they are low-priority changes in files not otherwise touched by an accepted batch. P08 is additionally CI-policy tooling, which needs approval even for a structural change. P07 cannot be made deterministic merely by changing sleeps or assuming idle proves background completion; the missing observation strategy is a real safety boundary. No DSL changes are permitted after its baseline failure.

### Batch A — R01: single owner for histogram bucket classification

Files:

- `examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala`
- new `examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/RunStatsSpec.scala`

First add direct characterization for empty-batch identity, failed-batch tally/histogram retention, duplicate-preserving sorted merge, extrema/bucket changes, equal-value spans, explicit ordinary bucket/summary expectations, and a deterministic bounded contiguous-partition matrix against full rebuilds. Pin existing wrapping/overflow exceptions rather than correcting them. Run against unchanged production code, format only the new test, compile and run the complete loadtest suite, then commit tests alone.

Named production refactoring: Extract Function. Both exact classification expressions call a non-public `Histogram.bucketIndex`. Preserve operand types/order, division/narrowing, edge construction, count updates, and invalid input behavior. No new validation or performance claim. Build + run the full loadtest suite, then format-check and Scalafix-check its main/test sources before the refactor commit.

Exact command families (all Mill calls use the documented bounded external wrapper):

    ./mill --no-server -j 2 examples.loadtest.compile + examples.loadtest.test.compile
    ./mill --no-server -j 2 examples.loadtest.test
    scalafmt-native --check <the two batch files>
    ./mill --no-server -j 2 examples.loadtest.fix --check
    ./mill --no-server -j 2 examples.loadtest.test.fix --check

### Batch B — R02: keep failure policy out of resource orchestration

Files:

- `runtime/src/main/scala/io/worxbend/tui/runtime/TerminalRunner.scala`
- `runtime/src/test/scala/io/worxbend/tui/runtime/RunnerLifecycleSpec.scala`

First characterize three prior-primary cases (handler/backend/queued task) followed by fatal onStop and aggregated backend-close failure; separately pin fatal onStop after clean quit. Assert primary identity, exact suppression order/nesting, terminal/stream restoration and owner retirement. Tests must pass on the original production code and be committed alone. Existing tests retain coverage for nonfatal teardown, shared throwable deduplication, reporter escape, setup failures and fatal final-drain escape.

Named production refactoring: Extract Class. A private run-owned outcome helper owns only result/escaping-error/cleanup/backend-close classification. Keep both final-drain report assignments, recorder/acquisition boundaries, every release call and their evaluation order, final rethrow after Console scoping, and public RunnerError unchanged. Build + run the complete runtime suite after extraction, then format/lint the owning main/test sources before committing. Do not change fatal policy to match preferred wording.

    ./mill --no-server -j 2 runtime.compile + runtime.test.compile
    ./mill --no-server -j 2 runtime.test
    scalafmt-native --check <the two batch files>
    ./mill --no-server -j 2 runtime.fix --check
    ./mill --no-server -j 2 runtime.test.fix --check

At most two fix-forward attempts per step; if still failing, reverse only that step's own edits and document the deferral. Never delete/skip/weaken tests or add lint suppression. A static independent review of both proposed patch pairs found no blocking drift, but is not a substitute for actual test-first execution.

### Proposals requiring approval

These are intentionally unimplemented. Proposed regression descriptions are future tests, not claims that such tests already exist or have failed.

| Proposal | Required decision / prohibited change | Regression that would prove the desired correction |
| --- | --- | --- |
| P01 | Network/security behavior: accepted redirect origins and credential stripping/rejection policy | In `scripts/tests/test_release_validation.py`, exercise the real redirect handler with non-secret fixture headers: same-origin, other-host and HTTPS downgrade; assert no Authorization reaches a disallowed origin. Offline current-handler probe retained the header; no real credentials/network used. |
| P02 | Widget output/numeric scale policy | Extend `StackedBarChartSpec` with `Seq(Long.MaxValue, 1L)`, pinned max 1, one-column `Rect(0,2,1,2)` in a taller sentinel buffer; assert row 4 untouched. Add automatic-scale positive stacks whose Long sum wraps. Current source-derived arithmetic paints rows 3 and 4 and wraps the automatic total to -2; Scala widget reproduction is still needed. |
| P03 | Backend failure/retry semantics | Extend `GraphicsLayerSpec` / `JLineOutputFailureSpec` using a recoverable writer seam: first successful image frame, changed ordinary image frame whose write fails, identical retry; assert placement bytes are re-emitted and deletes aren't forgotten. Retain separate real PrintWriter latch coverage. |
| P04 | Tape path/CLI behavior | New isolated shell characterization with fake Mill for default/relative/absolute/spaced export paths and odd argument counts; desired regression asserts an absolute exported directory and explicit invalid-argv failure. Then run a separately approved real recording check. |
| P05 | CI deployment trigger behavior | Policy mutation test in `scripts/tests/test_ci_safeguards.py` requiring the static `docs/assets/**` input in Docs push paths; removing it must fail. No YAML edits in this pass. |
| P06 | Published content/version discipline | Homepage coordinate consistency test against synchronized `publishVersion`; current JSX literal differs. Approval precedes content or discipline-boundary edits. |
| P07 | Test synchronization strategy, with no production hook unless separately approved | Controlled old/new weather responses plus an actual Async-continuation delivery barrier; prove the stale result has been processed before asserting it cannot replace the new result. No longer sleep or weaker idle assertion. |
| P08 | CI-policy-tool structure, despite unchanged intended semantics | Characterize Central/Packages mutations and simultaneous failures with exact ordered error strings, then extract only their shared validation checks. |
| P11 | Overflow/validation semantics for example statistics | Existing new characterization pins current wrap/exception behavior; a separate desired-output test must follow an agreed large-latency/mean/bucket policy. Do not silently widen arithmetic in R01. |
| P12 | Baseline DSL failure investigation and any resulting fix | Existing PixelImageElementSpec first case already failed at line 33 in this baseline. Establish encoder/delivery completion under load before proposing a production fix or stronger asynchronous test boundary. DSL remains untouched here. |
| P13 | Dependency remediation and any tightened security threshold | Proposed strict audit regression (`npm audit --audit-level=high`, not run in this pass) would reject the observed high advisories; an approved compatible update must also pass existing Node compatibility tests, production build and real diagram/browser checks. Do not force overrides or edit the excluded lockfile autonomously. |

No public-API change, new runtime dependency, schema/serialization/wire-format change, build/framework migration or excluded-path edit is proposed for implementation. P09/P10 remain low-priority characterization follow-ups, not authorization to change malformed-input handling, generation formats or I/O semantics. Existing dependency advisories are reported from the actual npm audit; dependency/lockfile changes are outside this pass.

### Final verification and stopping rule

Re-run the complete baseline command list, native smoke, independent syntax/discipline scans and meta-build format check. Compare named failures to baseline, not a green-looking subset. Record before/after test counts, warning-text counts, coverage unavailability and local function-span/duplicate-expression measures for touched production files. Review the final diff independently for API/ordering/behavior drift. Commit the completed report with exact batch commit hashes and deferred risks; never push. Stop once both medium structural findings are verified, or a stated stop condition requires deferral.

## Phase 3 execution and Phase 4 verification

### Completed batches and commit evidence

| Theme | Test-first commit | Production commit | Actual result |
| --- | --- | --- | --- |
| R01 — Extract Function for histogram classification | `c2114263` — 12 direct characterizations, production unchanged | `13726806` — both literal expressions call non-public `Histogram.bucketIndex` | Full loadtest suite **18/18 passed before and after**; compile, scoped format/check and main/test Scalafix all passed. |
| R02 — Extract Class for run outcome bookkeeping | `39e8159b` — four fatal/mixed teardown characterizations, production unchanged | `66facb88` — private run-owned `RunOutcome` | Full runtime suite **323/323 passed before and after**; compile, scoped format/check and main/test Scalafix all passed. |

Review-only commit: `e2abee3d` (`docs: add quality review`). Plan-only commit: `a4cc8a50`. The commit containing this completed section is the separate final-report commit; it does not modify source code. Both implementation themes changed two files overall, with one file per test/production commit. No fix-forward attempt, rollback, test weakening, suppression, skipped test or dependency addition was needed.

R01's tests pin current integer means/percentile indexing, equal-value/zero-width buckets, failed-only histogram retention, empty identity, partition parity and wrapping/negative-index behavior. P11 is now executed Scala evidence, not merely a static hypothesis; it remains an approval-only correction. The extracted expression is exactly the original Long/Int operation sequence, not a new algorithm.

R02's tests pin primary identity and suppression flattening/nesting across handler/backend/queued failures, fatal onStop, later backend failures, emergency restoration, closed/unregistered owners and restored process streams. Existing fatal final-drain escape and reporter-failure tests remain intact. `backend.close()` still runs inside by-name release; both final-drain report assignments and every acquisition/release boundary remain in place. The helper does not own resources or redefine RunnerError.

All **24/24 batch checks** passed. Exact batch commands and exits follow. `M` expands to the bounded external Mill wrapper documented above; `F` is the downloaded pinned Scalafmt 3.11.5 binary. Paths are repository-relative here; the saved argv uses absolute formatter/file paths.

| Batch stage | Executed command | Exit |
| --- | --- | ---: |
| histogram-test/format | `F examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/RunStatsSpec.scala` | 0 |
| histogram-test/compile | `M examples.loadtest.compile + examples.loadtest.test.compile` | 0 |
| histogram-test/tests | `M examples.loadtest.test` | 0 |
| histogram-test/format-check | `F --check examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/RunStatsSpec.scala` | 0 |
| histogram-test/lint-main | `M examples.loadtest.fix --check` | 0 |
| histogram-test/lint-test | `M examples.loadtest.test.fix --check` | 0 |
| histogram-refactor/format | `F examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/RunStatsSpec.scala examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala` | 0 |
| histogram-refactor/compile | `M examples.loadtest.compile + examples.loadtest.test.compile` | 0 |
| histogram-refactor/tests | `M examples.loadtest.test` | 0 |
| histogram-refactor/format-check | `F --check examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/RunStatsSpec.scala examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala` | 0 |
| histogram-refactor/lint-main | `M examples.loadtest.fix --check` | 0 |
| histogram-refactor/lint-test | `M examples.loadtest.test.fix --check` | 0 |
| runner-test/format | `F runtime/src/test/scala/io/worxbend/tui/runtime/RunnerLifecycleSpec.scala` | 0 |
| runner-test/compile | `M runtime.compile + runtime.test.compile` | 0 |
| runner-test/tests | `M runtime.test` | 0 |
| runner-test/format-check | `F --check runtime/src/test/scala/io/worxbend/tui/runtime/RunnerLifecycleSpec.scala` | 0 |
| runner-test/lint-main | `M runtime.fix --check` | 0 |
| runner-test/lint-test | `M runtime.test.fix --check` | 0 |
| runner-refactor/format | `F runtime/src/test/scala/io/worxbend/tui/runtime/RunnerLifecycleSpec.scala runtime/src/main/scala/io/worxbend/tui/runtime/TerminalRunner.scala` | 0 |
| runner-refactor/compile | `M runtime.compile + runtime.test.compile` | 0 |
| runner-refactor/tests | `M runtime.test` | 0 |
| runner-refactor/format-check | `F --check runtime/src/test/scala/io/worxbend/tui/runtime/RunnerLifecycleSpec.scala runtime/src/main/scala/io/worxbend/tui/runtime/TerminalRunner.scala` | 0 |
| runner-refactor/lint-main | `M runtime.fix --check` | 0 |
| runner-refactor/lint-test | `M runtime.test.fix --check` | 0 |

### Before / after results

| Check or measure | Baseline | Final |
| --- | --- | --- |
| Full-gate steps | 43/44 passed; DSL step failed | **44/44 passed** |
| Main and test compilation | passed, warnings-as-errors enforced | passed, same flags |
| Scala tests | 7,464 run; 7,463 passed, one failed | **7,480 run and passed** |
| Scala suites | 569 completed, zero aborted | 570 completed, zero aborted |
| Canceled / ignored / pending tests | 0 / 0 / 0 | 0 / 0 / 0 |
| Runtime suite | 319 passed | 323 passed |
| Loadtest suite | 6 passed | 18 passed |
| Python tests | 94 passed | 94 passed |
| Node compatibility tests | 3 passed | 3 passed |
| Native-image tasks / no-TTY smoke | 12 passed / passed | 12 passed / passed |
| Scala format / Scalafix | passed / passed | passed / passed |
| Meta-build formatting | 21 Mill files passed | same 21 files passed |
| Independent syntax / discipline scans | 23 syntax checks; zero violations | same 23 checks; zero violations |
| Workflow policy/actionlint | passed | passed |
| Scala CLI / isolated packaged consumer | passed / passed | passed / passed |
| Gallery drift / site / Wiki export | passed / passed / passed | passed / passed / passed |
| npm critical-threshold audit | passed; 48 affected entries, zero critical | same counts and exit 0 |
| Raw lines matching `warning` | 2,495 | 2,649 |
| Unused Scalafix suppression diagnostics | 2 | same 2 |
| Coverage | not configured; unavailable | unchanged; no percentage claimed |

**Failure-set comparison:** baseline contained exactly PixelImageElementSpec's first kitty-graphics test, assertion line 33 (`0 did not equal 1`). Final contained **no failing/aborted tests**, and no new named failure. DSL source/tests were not changed. The failure's disappearance is consistent with intermittent behavior but does not establish its root cause or a fix; P12 remains open.

**Warnings are not silently cleaned up:** the final packaged-consumer run regenerated documentation that baseline reused from cache. It reported 142 Scaladoc warnings (18 runtime, 95 DSL, 29 test-support), predominantly unresolved links; example diagnostics refer to unchanged Runner.scala links (`AwaitingIdle`, `Fail`, `BackendError.message`). This is newly observed output, not evidence that these refactors introduced those links. The raw warning-line counter also includes JVM/native-access warnings, summary lines and test titles containing “warning”; it is not a count of source defects. Compilation still passes the unchanged `-Werror` gate. No flags or suppressions were relaxed to hide output. L09/L10 record remaining diagnostics rather than a false warning-free claim.

### Structural measurements and trade-offs

Measurements use physical declaration/body line spans including comments/blank lines until the next same-indent declaration/comment, and exact expression occurrence counts; they are not cognitive complexity or a performance benchmark.

| Production measure | Before | After |
| --- | ---: | ---: |
| TerminalRunner.scala file lines | 736 | 754 |
| runRegistered span | 117 (lines 55-171) | 76 (lines 55-130) |
| Literal bucket classifier copies | 2 | 1 |
| RunStats.scala file lines | 156 | 159 |
| Whole-file lexical branch tokens: TerminalRunner | 158 | 159 |
| Whole-file lexical branch tokens: RunStats | 25 | 25 |

The outcome helper adds named structure rather than deleting failure policy. Its principal operations are 8-line recordEscaping (`TerminalRunner.scala:311`), 14-line recordCleanup (`:323`), and 15-line recordBackendClose (`:338`). Final histogram calls are at `RunStats.scala:88,154`; canonical classifier at `:139-140`. No global complexity reduction, speedup, allocation improvement or coverage gain is inferred from these local measures.

### Independent review, scope and source integrity

- Two independent final reviewers found no concrete residual behavior drift, scope violation or commit-order problem. They checked actual base/head source and test diffs, not only draft patches. `clean-code-guard: clean`.
- Review specifically checked literal arithmetic, failure/throwable identity, suppression order/nesting, by-name backend close, final-drain report replacement, release order, Console-scoping rethrow and private visibility. Static review is not an all-input or binary-compatibility proof.
- The full final gate ran with all tracked source hashes unchanged. The final report was updated only after that frozen run. Baseline-to-final source scope is exactly two production files, their two characterization test files, and this review. Original `main` remains at the starting commit.
- No public library API, serialization/wire contract, new runtime dependency, build/CI configuration, generated source, asset, lockfile or existing golden changed. No branch was pushed; no history was rewritten.

### Findings disposition and residual risks

R01/R02 are completed and verified. L01-L10 remain deferred low-priority findings; they do not justify unrelated churn. P01-P13 remain approval-only proposals or explicitly scoped test/coverage questions; no bug fix was smuggled into a refactor. P09/P10 are future characterization work, not established production defects. P07 needs an observable Async completion design; P12's baseline DSL failure remains recorded even though final passed.

Highest remaining risks are conditional credential forwarding on redirected artifact downloads (P01), stacked-chart numeric/rectangle corruption at extreme values (P02), and failed-output graphics accounting (P03), plus the intermittent baseline DSL test (P12). P02's Critical label follows the supplied bug-priority rubric for a concrete bounds-contract counterexample; it does not assert remote exploitability, broad production exposure or data loss. Its chart evidence remains arithmetic/source analysis, not an executed Scala widget reproduction. P03/P04 likewise still need targeted end-to-end failure/path reproductions. Existing website advisories remain within their documented static-site/build boundary, not an assertion of zero risk.

Not verified: Windows/macOS, JDK26, CI Node24, real terminal/kitty/IME/screen-reader interaction, browser visual rendering, exhaustive public input combinations, binary compatibility via a dedicated ABI checker, performance benchmarks or instrumented statement/branch coverage. None is replaced by synthetic success claims. No toolchain/service/secret blocker prevented the local Linux/JDK25 baseline or final gate.

### Evidence locations and retention

Primary durable evidence is this report: exact command exits, named baseline failure, before/after totals, accepted/deferred findings, preservation constraints and commit hashes. Detailed logs, hashes, original five audit reports, patch drafts, independent reviews, metadata and structural metrics are under `/home/worxbend/.hermes/cache/scratch/glyphora-quality-20261022/`. In particular: `baseline/`, `final/`, the four batch directories, `comparison.json`, `structural-metrics.json`, `approval-probes.json` and `final-review/`. Scratch has normal retention limits; the committed report does not depend on those files remaining forever.

### Full command-by-command comparison
`M` below is the actual external wrapper, equivalent to `JAVA_TOOL_OPTIONS="-Xmx2g -XX:+UseSerialGC" ./mill --no-server -j 2` from the repository root. Python uses `/usr/bin/python3`; website commands run in `website/`. Full-gate environment also unsets golden updates, sets scratch TMPDIR, and prepends `/usr/bin` for PyYAML.
| Step | Executed command | Before exit | After exit |
| --- | --- | ---: | ---: |
| compile | `M __.compile + __.test.compile` | 0 | 0 |
| format | `M mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources` | 0 | 0 |
| scalafix | `M __.fix --check` | 0 | 0 |
| test-core | `M core.test` | 0 | 0 |
| test-terminal | `M terminal.test` | 0 | 0 |
| test-widgets | `M widgets.test` | 0 | 0 |
| test-runtime | `M runtime.test` | 0 | 0 |
| test-macros | `M macros.test` | 0 | 0 |
| test-dsl | `M dsl.test` | 1 | 0 |
| test-test-support | `M test-support.test` | 0 | 0 |
| example-airsensor | `M examples.airsensor.test` | 0 | 0 |
| example-counter | `M examples.counter.test` | 0 | 0 |
| example-dashboard | `M examples.dashboard.test` | 0 | 0 |
| example-doctor | `M examples.doctor.test` | 0 | 0 |
| example-form-demo | `M examples.form-demo.test` | 0 | 0 |
| example-gallery | `M examples.gallery.test` | 0 | 0 |
| example-hello-world | `M examples.hello-world.test` | 0 | 0 |
| example-loadtest | `M examples.loadtest.test` | 0 | 0 |
| example-procmon | `M examples.procmon.test` | 0 | 0 |
| example-showcase | `M examples.showcase.test` | 0 | 0 |
| example-todo-list | `M examples.todo-list.test` | 0 | 0 |
| example-weather | `M examples.weather.test` | 0 | 0 |
| python-tests | `/usr/bin/python3 -m unittest discover -s scripts/tests -v` | 0 | 0 |
| workflows | `bash scripts/check-workflows.sh` | 0 | 0 |
| quickstart | `/usr/bin/python3 scripts/verify-scala-cli.py` | 0 | 0 |
| packaged-consumer | `/usr/bin/python3 scripts/verify-packaged-consumer.py` | 0 | 0 |
| gallery | `bash scripts/render-gallery.sh --check` | 0 | 0 |
| website-tests | `npm test` | 0 | 0 |
| website-audit | `npm audit --audit-level=critical --json` | 0 | 0 |
| website-build | `npm run build` | 0 | 0 |
| wiki | `node scripts/export-wiki.mjs --output <scratch>/final/wiki` | 0 | 0 |
| native-airsensor | `M examples.airsensor.nativeImage` | 0 | 0 |
| native-counter | `M examples.counter.nativeImage` | 0 | 0 |
| native-dashboard | `M examples.dashboard.nativeImage` | 0 | 0 |
| native-doctor | `M examples.doctor.nativeImage` | 0 | 0 |
| native-form-demo | `M examples.form-demo.nativeImage` | 0 | 0 |
| native-gallery | `M examples.gallery.nativeImage` | 0 | 0 |
| native-hello-world | `M examples.hello-world.nativeImage` | 0 | 0 |
| native-loadtest | `M examples.loadtest.nativeImage` | 0 | 0 |
| native-procmon | `M examples.procmon.nativeImage` | 0 | 0 |
| native-showcase | `M examples.showcase.nativeImage` | 0 | 0 |
| native-todo-list | `M examples.todo-list.nativeImage` | 0 | 0 |
| native-weather | `M examples.weather.nativeImage` | 0 | 0 |
| native-smoke | `/usr/bin/python3 scripts/ci-examples.py smoke-native` | 0 | 0 |

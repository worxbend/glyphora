# Approved quality improvements — follow-up

## Authorization and boundaries

The user approved all remaining proposals from `QUALITY_REVIEW.md`, including the previously deferred bug fixes, CI/content changes, compatible dependency remediation and low-priority refactorings. This is a continuation on local branch `refactor/quality-pass-20261022`, starting at `e03a40e71f310a28e28fc50a2e9c65adf7adc401`. The starting working tree was clean. No pushing, publication, rebasing, history rewriting, speculative public-API migration or unrelated framework change is authorized.

The prior review remains historical evidence, including its exclusions and baseline failure. Those approval-only restrictions are lifted for the specifically listed improvements below. In particular, necessary website lockfile edits for compatible dependency remediation are now in scope; generated Scala tables, vendored fonts, existing golden outputs and unrelated generated assets remain untouched. The previously failing DSL graphics test can now be investigated and corrected rather than merely fenced off.

## Baseline and implementation discipline

The immediately preceding verified source state passed 44/44 full-gate steps, 7,480 Scala tests, 94 Python tests, three Node tests and twelve native-image tasks/no-TTY smoke checks. The only changes between that frozen source verification and this follow-up base were the final review document. This reuses that observed baseline; it is not a claim that another fresh full gate has already run.

Seven isolated detached worktrees share that exact base. The integration checkout is modified only by the coordinator. Workers create coherent verified local commits for integration; each batch has one theme and at most five files. Refactoring preserves existing behavior behind passing characterization; approved bug fixes retain actual RED/GREEN evidence and explicitly state their corrected contract. No suppression, weakened assertion or forced dependency upgrade substitutes for verification.

Mill builds use a shared three-slot semaphore, two jobs per build, 2GB heaps and SerialGC. Golden updates are unset. Final acceptance includes independent source review, exact-ID disposition validation, affected and full repository gates, generated-output drift, packaged/quickstart consumers, site checks, native images and real browser diagram evidence for browser-facing dependency changes. Unavailable compatible security patches remain explicit blockers with evidence, not falsely resolved entries.

## Acceptance ledger

| ID | Improvement | Owner | Status |
| --- | --- | --- | --- |
| L01 | Consolidate Buffer state copying across all frame planes | core | completed |
| L02 | Extract sparse grid-slot search without changing placement arithmetic | core | completed |
| L03 | Share memo refresh policy while retaining strict/fallback admission and exception ordering | dsl | completed |
| L04 | Name mouse press-observer and focus stages without changing delivery order | dsl | completed |
| L05 | Reuse the existing grouped-chart centered label painter | widgets | completed |
| L06 | Extract DataTable highlight-gutter policy | widgets | completed |
| L07 | Cache invariant gallery sidebar labels | examples-support | completed |
| L08 | Parse styled-golden expected diagnostics once | examples-support | completed |
| L09 | Remove verified unused suppressions, without adding suppressions | core + dsl | completed |
| L10 | Correct actual Scaladoc link-query warnings in focused batches | docs-deps | completed |
| P01 | Scope authenticated artifact redirects to approved HTTPS origin | tooling | completed |
| P02 | Bound stacked-chart heights and prevent automatic-total overflow | widgets | completed |
| P03 | Commit graphics accounting only after successful output, including retries/deletions | terminal | completed |
| P04 | Normalize tape export paths and reject incomplete CLI argument pairs | tooling | completed |
| P05 | Include served asset changes in Pages deployment triggers | tooling | completed |
| P06 | Synchronize homepage install coordinate and cover version drift | tooling | completed |
| P07 | Make stale weather completion coverage deterministic | examples-support | completed |
| P08 | Consolidate shared publication-policy checks with exact diagnostic characterization | tooling | completed |
| P09 | Add focused Wiki, Scaladoc bridge and generator boundary tests | tooling | completed |
| P10 | Add weather, airsensor and procmon I/O/parser boundary tests | examples-support | completed |
| P11 | Define overflow-safe loadtest histogram and mean arithmetic | examples-support | completed |
| P12 | Correct graphics test synchronization using observed encode/draw completion | dsl | completed |
| P13 | Apply compatible website dependency security remediation and exercise real consumers/browser | docs-deps | partial — upstream braces blocker |

All 23 IDs have an executed-evidence-backed final disposition: 22 completed, one partially remediated with an upstream blocker. R01/R02 were already completed in the preceding pass and are not counted again. Cross-track L09 contributions are one finding, not duplicate completions.

## Checkpoints

Detailed worker logs, ordered commits, reports and machine-readable acceptance board:

`/home/worxbend/.hermes/cache/scratch/glyphora-quality-followup-20261022/`

Raw scratch logs have normal retention limits. Durable outcomes, exact executed commands and exits, ordered integration commits, per-ID dispositions, artifact hashes and verification limits are preserved in `QUALITY_VERIFICATION.json` alongside this report.

## Final outcome

**22 completed; P13 partially remediated, not closed.** All 23 original IDs were reconciled programmatically, including both L09 owners, all three P09 tooling subparts and all three P10 example boundaries. R01/R02 remain completed historical work and are not counted again.

The verified source commit is `def7734b340e314f24f239dcefa84e9a640d30fe`. It contains 79 local follow-up commits after the base named above; every commit touches at most five files. The final acceptance batch adds only this report update and `QUALITY_VERIFICATION.json`. No executable, build, dependency, fixture or site input changes after that verified commit.

Original `main` remains `e80bb81ab4e59bf1eb0563cf00b82e5682145ab1`. Nothing in this follow-up was pushed, published, rebased or committed on main. Generated width/world tables, vendored fonts, tracked gallery assets and existing test resources are unchanged. The website lockfile is the explicitly authorized exclusion exception.

## Delivered behavior and structure

- **L01–L09:** all-plane Buffer copying, sparse grid search, memo refresh, mouse-dispatch stages, grouped-chart labels and DataTable gutters have one policy owner behind characterization. Gallery labels and styled-golden diagnostics avoid repeated invariant work. Only the confirmed unused core/DSL suppressions were removed; no new suppression was added.
- **P01–P06:** authenticated redirects stay within the approved HTTPS origin; stacked bars use overflow-safe bounded arithmetic; graphics accounting commits after confirmed writes and retains uncertain deletion obligations. Failed delivered deletion or scrolling requests full repaint before the next ordinary draw. Tape paths/arguments, asset deployment triggers and homepage coordinates have executable guards.
- **P07–P12:** stale weather completions are observed through the owning queue, not sleeps; shared publication validation retains exact diagnostics; Wiki, the actual injected Scaladoc bridge and both generators have boundary tests. Weather, AirGradient and procmon retain their characterized input grammars. Loadtest arithmetic widens before overflow. Graphics tests wait for actual encoding/draw completion without weakening once/idle/modal assertions.
- **L10:** all observed unresolved member queries are repaired. Cross-module classpath mappings and a tracked, narrowly scoped normalizer preserve real destinations without a toolchain/API migration. Warning-free enum queries that silently rendered dead spans use verified entry links. Private helpers and `Line.alignment`, which lacks a rendered declaration ID, have documented code-voice exceptions. The final Codec-to-Persisted link uses a verified URL rather than adding an upward module dependency.
- **P13:** targeted consumer-scoped dependency repairs include KaTeX 0.18.2 under Mermaid 11.16.1 with matching CSS/fonts. Both CI and Pages use script-disabled installs; policy mutation tests reject re-enabling install scripts. Docusaurus remains 3.10.2. The remaining braces finding is detailed below.

## Final executed verification

`tool:Mill`, `tool:ScalaTest`, `tool:unittest`, `tool:Node`, `tool:npm` and the repository validation scripts passed **57/57 acceptance steps** against the frozen verified source. The before/after tracked-source manifests are identical, as are HEAD and the original main reference throughout the run. The exact command argv, working directories, exits, per-command test totals, duration and raw-log paths are retained in `QUALITY_VERIFICATION.json`.

| Check | Observed result |
| --- | --- |
| All main/test compilation | pass |
| Scala formatting, pinned build-file formatting and main/test Scalafix | pass |
| Seven library suites plus all twelve example suites | 7,526 Scala tests passed |
| Python tooling discovery | 142 tests passed |
| Workflow syntax/publication/timeout/permission policy | pass |
| Current-checkout Scala CLI quickstart and isolated packaged consumer | pass |
| Tracked gallery output comparison | pass; no fixture regeneration |
| Node 24.22.0 / npm 11.19.0 script-disabled clean install and complete dependency tree | pass |
| Website consumer/security tests | 12 passed; zero failed/skipped |
| Production website and Wiki export | pass |
| Forced-fresh API generation for all seven published modules | pass |
| Unresolved member-query diagnostics in fresh generation logs | zero |
| Strict generated mapped-link corpus | 3,748 valid occurrences, 829 HTML pages, zero invalid destinations |
| All four focused cross-module navigation examples | preserved and valid, including destination fragment IDs |
| Twelve native-image targets and all twelve no-TTY smoke checks | pass |
| Critical-threshold npm audit | pass; not a clean plain audit |

Mill invocations used the checked-in wrapper with `--no-server -j 2`, `JAVA_TOOL_OPTIONS='-Xmx2g -XX:+UseSerialGC'`, a three-slot semaphore and a 2,400-second per-command bound. `GLYPHORA_GOLDEN_UPDATE` was unset. The fresh-documentation step explicitly cleaned `{core,terminal,widgets,runtime,dsl,macros,test-support}.scalaDocGenerated` before the packaged-consumer/doc-jar checks; cached jars were not treated as proof of warning removal.

Actual RED/GREEN evidence is retained for approved behavior corrections. In particular, four delivered-delete/delivered-scroll regressions failed before the final P03 repair and the complete 606-test terminal suite passed afterward. The stricter documentation guard rejected 68 remaining member-query diagnostics and subsequently the last unresolved Codec/Persisted query; the final run reports zero. The earlier 56/57 acceptance attempt remains recorded as a failure, not relabeled as a pass.

## Independent review and browser evidence

`manual`: separate security/numeric/terminal, behavior/tooling, Standards and Spec reviews were followed by reviews of the generated-link normalizer, the 49-file comment delta and the final one-file Codec link. The residual scroll recovery defect was fixed with real regressions. Final reviews established no remaining required source/scope findings. Comment-delta checks preserve literal bytes and significant nonblank runtime code, not merely a whitespace-erased token approximation.

`tool:Chromium`: retained real Node 24/Chromium 145 evidence covers both existing Mermaid pages (nine/six nodes), native MathML, forced legacy math with matching CSS/fonts, nonmath labels and rejected unsafe math links. Browser-facing dependency/CSS inputs are unchanged from that exercised source. This is not a cross-browser or accessibility claim.

## Remaining blocker and explicit limits

**P13 — partial, upstream blocker.** `tool:npm`: the closure-time plain audit exits **1**, with **29 high affected-package entries** tracing to one advisory, [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm). There are zero low, moderate or critical entries. A fresh registry query still reports braces **3.0.3** as latest; no supported fixed release was found. See `website/DEPENDENCY_SECURITY.md:93–136`, especially line 97. The high entries include inherited parents, not 29 independent vulnerabilities. Static deployment and bounded, least-privilege builds mitigate exposure but do not repair braces. No audit suppression, invented patched version, unsupported fork, forced framework upgrade or blanket override was used. A supported upstream patch and its consumer verification remain the closure condition.

`manual`: procmon reads stdout before its timed wait (`examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/ProcessSource.scala:67–69`). P10 proves post-EOF forced termination, not a total sampling deadline; this preserved behavior is not presented as fixed.

Other verification boundaries:

- The strict API gate covers mapped known-module pages/fragments. Rewritten relative links and the core/terminal relative corpus were checked separately; unrelated vendor/external links and deployed-site availability are not universally validated.
- Four repaired source references sit in owner comments Scala 3.9 omits entirely. Their destinations exist; their owner prose is not claimed rendered.
- Generated JAR payload preservation is not byte-identical ZIP metadata: Python supplies default permissions where an original entry has zero `external_attr`.
- Classpath-option, JVM Unsafe and native-access tool warnings remain unsuppressed. Zero warnings above means unresolved member queries, not every kind of tool diagnostic.
- Coverage and Sonar instrumentation are not configured; no coverage percentage or complexity score is invented. This Linux/JDK25 verification does not establish Windows/macOS/JDK26 or live graphical-kitty acceptance.
- Raw logs and detailed worker probes remain under the scratch checkpoint path above and may expire. Durable command/result summaries, source/artifact hashes, commit ancestry and all dispositions are committed with this report.

`clean-code-guard: clean` for the final reviewed source; the documented upstream security blocker is not reclassified as clean dependency health.

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
| L01 | Consolidate Buffer state copying across all frame planes | core | in progress |
| L02 | Extract sparse grid-slot search without changing placement arithmetic | core | in progress |
| L03 | Share memo refresh policy while retaining strict/fallback admission and exception ordering | dsl | in progress |
| L04 | Name mouse press-observer and focus stages without changing delivery order | dsl | in progress |
| L05 | Reuse the existing grouped-chart centered label painter | widgets | in progress |
| L06 | Extract DataTable highlight-gutter policy | widgets | in progress |
| L07 | Cache invariant gallery sidebar labels | examples-support | in progress |
| L08 | Parse styled-golden expected diagnostics once | examples-support | in progress |
| L09 | Remove verified unused suppressions, without adding suppressions | core + dsl | in progress |
| L10 | Correct actual Scaladoc link-query warnings in focused batches | docs-deps | in progress |
| P01 | Scope authenticated artifact redirects to approved HTTPS origin | tooling | in progress |
| P02 | Bound stacked-chart heights and prevent automatic-total overflow | widgets | in progress |
| P03 | Commit graphics accounting only after successful output, including retries/deletions | terminal | in progress |
| P04 | Normalize tape export paths and reject incomplete CLI argument pairs | tooling | in progress |
| P05 | Include served asset changes in Pages deployment triggers | tooling | in progress |
| P06 | Synchronize homepage install coordinate and cover version drift | tooling | in progress |
| P07 | Make stale weather completion coverage deterministic | examples-support | in progress |
| P08 | Consolidate shared publication-policy checks with exact diagnostic characterization | tooling | in progress |
| P09 | Add focused Wiki, Scaladoc bridge and generator boundary tests | tooling | in progress |
| P10 | Add weather, airsensor and procmon I/O/parser boundary tests | examples-support | in progress |
| P11 | Define overflow-safe loadtest histogram and mean arithmetic | examples-support | in progress |
| P12 | Correct graphics test synchronization using observed encode/draw completion | dsl | in progress |
| P13 | Apply compatible website dependency security remediation and exercise real consumers/browser | docs-deps | in progress |

Each ID will receive an executed-evidence-backed final disposition. R01/R02 were already completed in the preceding pass and are not counted again. Cross-track L09 contributions are one finding, not duplicate completions.

## Checkpoints

Detailed worker logs, ordered commits, reports and machine-readable acceptance board:

`/home/worxbend/.hermes/cache/scratch/glyphora-quality-followup-20261022/`

Scratch has normal retention limits; the completed report will preserve durable outcomes, commit identifiers, remaining blockers and verification limits here.

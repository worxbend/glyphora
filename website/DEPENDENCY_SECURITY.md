# Website dependency security

The website is a static Docusaurus build, not a public Node server. Source Markdown,
CSS, configuration, and Mermaid diagrams are repository-authored. Do not expose
`npm start` to untrusted networks or render arbitrary user-supplied diagrams.
Build-time vulnerabilities can still affect CI; static deployment is not a claim
of zero risk. `dependencies` includes build tooling, so `npm audit --omit=dev`
is not a measure of deployed browser exposure.

## Checks

From `website/`, run `npm ci --ignore-scripts`, `npm ls --all`, `npm test`,
`npm audit --json`, and `npm run build`. Review lifecycle scripts before enabling
them; the verified build does not need install scripts. CI and Pages deployment
reject critical findings with `npm audit --audit-level=critical`. This threshold
is not a claim that the dependency graph has no high-severity advisories.

## Compatible repairs verified on 2026-10-22

Docusaurus remains synchronized at 3.10.2, Mermaid at 11.16.1, and the Node floor
at 24. Existing Tinypool 2.1.2 and serialize-javascript 7.0.5 overrides remain.
The authoritative lockfile receives only targeted changes, not a framework
migration or a broad `npm audit fix --force` refresh.

- **follow-redirects 1.16.0 → 1.16.1** and **launch-editor 2.14.1 → 2.14.2**
  satisfy their existing parent ranges. The patch releases fix
  [GHSA-q3jh-wrc6-jgq8](https://github.com/advisories/GHSA-q3jh-wrc6-jgq8),
  [GHSA-8r9p-f939-6h3c](https://github.com/advisories/GHSA-8r9p-f939-6h3c),
  [GHSA-698v-4r3x-3vcv](https://github.com/advisories/GHSA-698v-4r3x-3vcv),
  [GHSA-r825-w8mm-5pxw](https://github.com/advisories/GHSA-r825-w8mm-5pxw), and
  [GHSA-587h-7wmj-h5pm](https://github.com/advisories/GHSA-587h-7wmj-h5pm).
  Upstream changes: [redirects](https://github.com/follow-redirects/follow-redirects/compare/v1.16.0...v1.16.1)
  and [editor](https://github.com/vitejs/launch-editor/compare/v2.14.1...v2.14.2).
- **postcss-selector-parser 6.1.4 → 7.1.7**, scoped to the six remaining 6.x
  consumers: postcss-calc, postcss-discard-unused, postcss-merge-rules,
  postcss-minify-selectors, postcss-unique-selectors, and stylehacks. Their original
  ranges are `^6.0.11` (calc) or `^6.0.16` (the other five).
  [The 7.0 breaking change](https://github.com/postcss/postcss-selector-parser/releases/tag/v7.0.0)
  changes insertion-during-iteration semantics; it does not remove the CJS
  processor/node APIs these consumers use. Version 7.1.6 fixes
  [GHSA-rj75-hqrm-r3gf](https://github.com/advisories/GHSA-rj75-hqrm-r3gf).
  Tests resolve the parser through each actual plugin and exercise selector
  calculations, namespace pruning, pseudo-selector merging/normalization,
  deduplication, and legacy-hack removal. The production CSS build also passes.
- **uuid 8.3.2 → 11.1.1**, scoped to SockJS 0.3.24 (original range `^8.3.2`).
  [Upstream changelog](https://github.com/uuidjs/uuid/blob/v11.1.1/CHANGELOG.md):
  11.1.1 backports [GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq).
  The intervening deep-import/UMD removals, Node-floor changes, and v1/v7 state
  changes do not affect SockJS's sole call, `require('uuid').v4()` without options
  or an output buffer. A real loopback SockJS HTTP transport test verifies the
  connection's generated v4 ID, open response, and close/destroy lifecycle.
  Other consumers' UUID ranges are not overridden.

Verification used Node 24.22.0: fresh script-disabled install, complete dependency
tree, ten real consumer tests, and production build. Both existing Mermaid pages
(`/intro` and `/architecture`) were loaded in isolated Chromium: actual hydrated
SVG nodes/labels and resource statuses were recorded, with no observed page
errors or console warnings. This is not a cross-browser/accessibility audit.

The fresh audit changed from **48 affected-package entries** (31 high, 15 moderate,
two low) to **31** (29 high, two low). The independent advisory URL set fell from
nine to two; inherited parent entries are not independent vulnerabilities or
attack paths. Plain `npm audit` still exits 1. **P13 is partially remediated, not
fully resolved.**

## Remaining upstream blockers

| Dependency | Consequence and boundary | Constraint, alternatives, and next action |
| --- | --- | --- |
| braces 3.0.3 — high | Deeply nested glob patterns can exhaust the build/development process stack. Not shipped browser logic. [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm). | The registry's latest remains 3.0.3, which is affected: there is **no published patch**, not merely an old lockfile. Micromatch 4.x requires `^3.0.3`; Chokidar 3.x requires `~3.0.2`. No override to a nonexistent patched version is possible. Replacing the glob/watch pipeline is a separate dependency migration. Keep build/glob inputs reviewed, builds time-bounded, and dev servers loopback-only; follow an upstream patch. |
| katex 0.16.47 — low | Browser-side trust restrictions can be bypassed if prototype pollution already exists. No such pollution path was established here; diagrams are repository-authored. [GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7). | Mermaid 11.16.1 requires `^0.16.45`; the patch starts at 0.18.2, with no patched 0.16.x release. [KaTeX 0.17/0.18](https://github.com/KaTeX/KaTeX/blob/v0.18.2/CHANGELOG.md) changes the internal function API and prefixes internal CSS classes. A blind override would not prove compatibility of math labels, sanitization, or CSS. Mermaid 12 is an alternative, but its [breaking release](https://github.com/mermaid-js/mermaid/releases/tag/mermaid%4012.0.0) changes the default layout/theme, removes renderer options, and raises browser requirements to ES2024/Safari 17.4+. Do not silently migrate the framework/diagram rendering contract. Follow a compatible Mermaid backport or separately review a math/CSS/browser migration; continue disallowing untrusted diagrams. |

Re-check registry/advisory status before changing these notes. Compatible updates
must retain the lockfile and pass consumer tests, a production build, and real
browser hydration checks; a passing critical-threshold audit alone is insufficient.

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

## Independent residual-advisory review (2026-10-22)

**KaTeX: repaired.** Mermaid remains 11.16.1. Its KaTeX dependency is overridden
only under `@docusaurus/theme-mermaid > mermaid` to **0.18.2**, fixing
[GHSA-238p-pmpm-9mq7](https://github.com/advisories/GHSA-238p-pmpm-9mq7).
The original `^0.16.45` range excludes the patch, but this is not an API blocker:
Mermaid's actual `dist/mermaid.core.mjs` consumer dynamically imports `katex` and
calls public `renderToString` with `throwOnError`, `displayMode`, and `output`.
It does not call `__defineFunction`, the internal API changed in 0.17.
[KaTeX 0.18's class-prefix change](https://github.com/KaTeX/KaTeX/blob/v0.18.2/CHANGELOG.md)
is handled by importing `katex/dist/katex.min.css` from the same resolved package,
including its fonts, rather than retaining 0.16 CSS or a remote CDN stylesheet.
Mermaid's `.node .katex path` selector still targets the unchanged outer class.
No Mermaid 12/framework migration or browser-target change is needed.

Node 24.22.0 verification: fresh `npm ci --ignore-scripts`, `npm ls --all`, twelve
consumer tests, and production build pass. Tests cover both KaTeX output modes,
matching CSS/font resolution, and rejection of inherited `trust` options, unsafe
links, and external images. Actual Chromium 145 Docusaurus hydration additionally
renders temporary repository-authored Mermaid fixtures for native MathML
(fraction/radical), `forceLegacyMathML` (sum with matching loaded KaTeX fonts),
nonmath labels, and an untrusted `javascript:` math link. The unsafe link remains
noninteractive; no observed errors/warnings or failed resources. Fixtures are not
published. Both final existing pages still hydrate (intro: nine nodes;
architecture: six). This evidence is not a cross-browser/accessibility audit.

Fresh audit now has **29 high affected-package entries, one independent advisory
URL, no low/moderate/critical findings**. Plain audit still exits 1; critical-only
audit policy is unchanged. These counts supersede the earlier 31-entry snapshot.

**braces: patch blocked; exposure mitigated, not repaired.**
[GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)
still lists `<=3.0.3` and `first_patched_version: null`; the registry's latest is
3.0.3. Its recursion guards only character length, not AST nesting depth.
Independent Node 24.22.0 probes of `compile(parse(pattern))` and
`expand(parse(pattern))` at 4,999 nested braces / 9,999 characters completed;
this does **not** invalidate the advisory's Node 26 reproducer or prove safety
for other AST shapes/stack contexts.

- Latest Micromatch 4.0.8 still requires `braces: ^3.0.3`. It is independently
  reached through Docusaurus utils (`micromatch: ^4.0.5`), fast-glob (`^4.0.8`),
  and http-proxy-middleware (`^4.0.2`). Therefore changing only the watcher cannot
  eliminate the finding.
- Docusaurus core requires Chokidar `^3.5.3`; webpack-dev-server requires
  `^3.6.0`; Chokidar 3.6.0 requires `braces: ~3.0.2`. Chokidar 6 removes braces
  but is outside both ranges and drops glob support; it is not a compatible
  isolated override, and would still leave Micromatch's braces copy.
- Upstream [issue 70](https://github.com/micromatch/braces/issues/70) and open
  PRs 78/79/82/87/88 contain proposed depth guards, not a maintainer-published
  fixed artifact. Inspected PR 88 at `a795ee83ecd011131b2d618fe754ac5a8e124dae`
  changes parsing and all recursive walkers; its combined commit status was
  `pending`, and it was unmerged. No verified supported patched artifact was
  established. Do not vendor a proposed patch or mislabel a Git snapshot as a
  patched registry version merely to clear audit.
- Existing controls limit exposure: no public Node service is deployed; patterns
  come from repository configuration, not HTTP requests. Pages builds run only
  on main/manual dispatch, with read-only build permissions and a separate deploy
  job; website build steps have 15-minute (CI) / 30-minute (Pages) bounds.
  These bounds contain runaway execution, not immediate stack crashes. PR code
  is still potentially hostile and must remain isolated from secrets/deploy.
- Keep glob/watch configuration reviewed, reject any future feature accepting
  arbitrary external patterns unless it enforces a small nesting/length limit
  **before** all glob consumers, and keep development servers loopback-only
  (`npm start -- --host 127.0.0.1`). Do not claim the upstream `rangeLimit` setting
  limits nesting or globally monkey-patch transitive consumers. Recheck the
  registry and upstream depth-guard work before a targeted update.

**P13 remains partially remediated.** No audit suppression, fabricated patch
version, broad lockfile refresh, new runtime dependency, or framework migration
was used. Re-run full install/tree/test/build and hydrated math checks when either
the scoped override or its parents change.

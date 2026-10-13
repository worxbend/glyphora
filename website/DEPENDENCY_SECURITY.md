# Website dependency security

The website is a static Docusaurus build, not a public Node server. Source Markdown,
CSS, configuration, and Mermaid diagrams are repository-authored. Do not expose
`npm start` to untrusted networks or render arbitrary user-supplied diagrams.

## Checks

From `website/`, run `npm ci`, `npm test`, `npm audit`, and `npm run build`.
CI and Pages deployment run the compatibility tests and reject critical audit
findings with `npm audit --audit-level=critical`. This threshold is not a claim
that the dependency graph has no high-severity advisories.

The v0.18.0 dependency review removed the reported critical findings with targeted
compatible lockfile updates and scoped overrides. Docusaurus stays synchronized at
3.10.2. Tinypool 2.1.2, serialize-javascript 7.0.5, and Mermaid 11.16.1 are tested
through their actual consumers; the site still requires Node 24 or newer.

## Remaining upstream advisories

The verified audit reports four advisory-bearing dependencies below. Counts from
`npm audit` also include affected parent packages; they are not independent
attack paths. Re-check registry/advisory status before updating these notes.

| Dependency | Consequence and boundary | Why it remains / next action |
| --- | --- | --- |
| braces 3.0.3 | Deeply nested glob patterns can exhaust the build/development process stack. This is a build/development dependency, not deployed browser code. | No patched version was available. Keep build inputs reviewed and CI time-bounded; upgrade when upstream provides a compatible fix. [Advisory](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm). |
| postcss-selector-parser 6.1.4 | Pathological CSS selectors can cause build-time CPU exhaustion. | Parents require the 6.x API; the patch begins at 7.1.6. Upgrade the CSS pipeline together rather than forcing an untested cross-major override. [Advisory](https://github.com/advisories/GHSA-rj75-hqrm-r3gf). |
| uuid 8.3.2 | Its optional-buffer UUID API lacks bounds validation; the dependency is pulled in by the development server's SockJS path. The static site does not run this server. | SockJS requires 8.x; the fix begins at 11.1.1. Follow an upstream development-server upgrade. [Advisory](https://github.com/advisories/GHSA-w5hq-g745-h8pq). |
| katex 0.16.47 | Browser-side trust restrictions can be bypassed if prototype pollution already exists. No prototype-pollution path was established in this repository; diagrams are repository-authored. | Mermaid requires the 0.16.x line; the fix begins at 0.18.2. Follow a compatible Mermaid/KaTeX upgrade and retain diagram browser checks. [Advisory](https://github.com/advisories/GHSA-238p-pmpm-9mq7). |

Do not run `npm audit fix --force` blindly: it can replace parent APIs or move
Docusaurus/Mermaid across major versions without preserving the site. Dependency
updates must pass the compatibility tests, the production build, and browser
rendering of both existing Mermaid diagrams.

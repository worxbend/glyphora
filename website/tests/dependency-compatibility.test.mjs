import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {createRequire} from 'node:module';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import test from 'node:test';
import {runInNewContext} from 'node:vm';

const require = createRequire(import.meta.url);
const manifest = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'));

// The theme's upstream >=11.6.0 Mermaid range also accepts future major releases.
// Keep the security patch on the current Mermaid major and Docusaurus synchronized.
test('Docusaurus packages and the Mermaid override stay aligned', async () => {
  const versions = new Set();
  for (const name of [
    '@docusaurus/core',
    '@docusaurus/preset-classic',
    '@docusaurus/theme-mermaid',
    '@docusaurus/module-type-aliases',
    '@docusaurus/types',
  ]) {
    const requested = manifest.dependencies[name] ?? manifest.devDependencies[name];
    const installed = JSON.parse(await readFile(
      new URL(`../node_modules/${name}/package.json`, import.meta.url), 'utf8',
    )).version;
    assert.equal(installed, requested, `${name} must be pinned to its installed version`);
    versions.add(installed);
  }
  assert.equal(versions.size, 1);
  const themeRequire = createRequire(require.resolve('@docusaurus/theme-mermaid'));
  const mermaidPackage = JSON.parse(await readFile(themeRequire.resolve('mermaid/package.json'), 'utf8'));
  assert.equal(mermaidPackage.version, manifest.overrides['@docusaurus/theme-mermaid'].mermaid['.']);
  assert.match(mermaidPackage.version, /^11\./);
});

// Mermaid 11 calls only the public renderToString API, not __defineFunction.
// KaTeX 0.18 prefixes internal HTML classes; ship CSS/fonts from that same copy.
// Actual hydrated Mermaid math/native and legacy rendering is also browser-tested.
// https://github.com/KaTeX/KaTeX/blob/v0.18.2/CHANGELOG.md
const mermaidRequire = createRequire(require.resolve('mermaid'));
const katex = mermaidRequire('katex');
test('Mermaid resolves patched KaTeX with matching HTML CSS and font assets', async () => {
  assert.equal(katex.version, manifest.overrides['@docusaurus/theme-mermaid'].mermaid.katex);
  assert.equal(require.resolve('katex/dist/katex.min.css'), mermaidRequire.resolve('katex/dist/katex.min.css'));
  const css = await readFile(mermaidRequire.resolve('katex/dist/katex.min.css'), 'utf8');
  const customCss = await readFile(new URL('../src/css/custom.css', import.meta.url), 'utf8');
  assert.match(customCss, /@import 'katex\/dist\/katex.min.css'/);
  const formula = String.raw`\frac{1}{2}+\sqrt{x^2}+\sum_{i=1}^{n}i`;
  const mathml = katex.renderToString(formula, {throwOnError: true, displayMode: true, output: 'mathml'});
  assert.match(mathml, /<mfrac>/);
  assert.match(mathml, /<msqrt>/);
  const html = katex.renderToString(formula, {throwOnError: true, displayMode: true, output: 'htmlAndMathml'});
  assert.match(html, /katex-html/);
  assert.match(html, /katex-base/);
  assert.ok(css.includes('.katex-base'));
  const fonts = [...css.matchAll(/url\((?:["']?)(fonts\/[^)"']+)/g)].map(match => match[1]);
  assert.ok(fonts.length > 0);
  for (const font of new Set(fonts)) {
    assert.ok((await readFile(mermaidRequire.resolve(`katex/dist/${font}`))).length > 0);
  }
});

test('patched KaTeX ignores inherited trust and blocks untrusted link/image output', () => {
  // Use an options prototype rather than polluting the shared test process.
  const options = Object.assign(Object.create({trust: true}), {throwOnError: true, output: 'htmlAndMathml'});
  for (const formula of [String.raw`\href{javascript:alert(1)}{click}`, String.raw`\includegraphics{https://example.invalid/a.png}`]) {
    const html = katex.renderToString(formula, options);
    assert.doesNotMatch(html, /<(?:a|img)\b/);
    assert.doesNotMatch(html, /(?:href|src)="(?:javascript:|https:)/);
  }
});

// Selector parser 7 changes insertion-during-iteration semantics, not the CJS
// processor/node API these six cssnano plugins use. Exercise the consumers, not
// just a separately imported parser. The 7.1.6 patch fixes GHSA-rj75-hqrm-r3gf.
// https://github.com/postcss/postcss-selector-parser/blob/master/CHANGELOG.md
for (const [name, input, expected, options] of [
  ['postcss-calc', 'a[data-size="calc(3*3)"]{width:calc(1px + 2px)}',
    'a[data-size="9"]{width:3px}', {selectors: true}],
  ['postcss-discard-unused', '@namespace svg url(svg);@namespace unused url(unused);svg|a{color:red}',
    '@namespace svg url(svg);svg|a{color:red}'],
  ['postcss-merge-rules', '.a:hover{color:red}.b:hover{color:red}',
    '.a:hover,.b:hover{color:red}'],
  ['postcss-minify-selectors', 'a:nth-child(1), a::before, [x="abc"]{color:red}',
    '[x=abc],a:before,a:first-child{color:red}'],
  ['postcss-unique-selectors', '.b, .a, .a{color:red}', '.a,.b{color:red}'],
  ['stylehacks', '* html .legacy{color:red}.modern{color:blue}', '.modern{color:blue}'],
]) {
  test(`patched selector parser works through ${name}`, async () => {
    const consumerRequire = createRequire(require.resolve(name));
    const parserVersion = consumerRequire('postcss-selector-parser/package.json').version;
    assert.equal(parserVersion, manifest.overrides[name]['postcss-selector-parser']);
    const result = await require('postcss')([require(name)(options)]).process(input, {from: undefined});
    assert.equal(result.css, expected);
    assert.deepEqual(result.warnings(), []);
  });
}

// SockJS calls only require('uuid').v4() without options or an output buffer.
// UUID 9 removes deep imports/UMD and 11 changes v1/v7 state, neither used here.
// https://github.com/uuidjs/uuid/blob/v11.1.1/CHANGELOG.md
// Exercise an actual SockJS HTTP transport, including its connection constructor.
test('patched UUID supports the SockJS connection lifecycle', {timeout: 10000}, async () => {
  const sockjsRequire = createRequire(require.resolve('sockjs'));
  assert.equal(sockjsRequire('uuid/package.json').version, manifest.overrides.sockjs.uuid);
  const sockjs = require('sockjs').createServer({log() {}});
  const server = createServer();
  let connection;
  try {
    sockjs.installHandlers(server, {prefix: '/compatibility'});
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const connected = once(sockjs, 'connection');
    const response = await fetch(`http://127.0.0.1:${server.address().port}/compatibility/000/test/xhr`, {method: 'POST'});
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'o\n');
    [connection] = await connected;
    assert.match(connection.id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(connection.readyState, 1);
    connection.close(1000, 'compatibility test finished');
  } finally {
    connection?.destroy();
    server.closeAllConnections();
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});

// Upstream's v7.0.0 release identifies Node >=20 as the breaking change;
// this site already requires >=24. Both consumers use the same CJS function API.
// https://github.com/yahoo/serialize-javascript/releases/tag/v7.0.0
// GHSA-5c6j-r48x-rmvq and GHSA-qj8w-gfj5-8c6v require a patched 7.x release.
test('both webpack consumers can serialize and safely escape their configuration', () => {
  for (const name of ['copy-webpack-plugin', 'css-minimizer-webpack-plugin']) {
    const consumerRequire = createRequire(require.resolve(name));
    const serialize = consumerRequire('serialize-javascript');
    assert.equal(typeof serialize, 'function');
    assert.equal(
      consumerRequire('serialize-javascript/package.json').version,
      manifest.overrides[name]['serialize-javascript'],
    );
    const serialized = serialize({
      html: '</script><script>alert(1)</script>',
      pattern: /docs/gi,
      date: new Date('2026-01-01T00:00:00.000Z'),
      missing: undefined,
    });
    assert.ok(!serialized.includes('<'), 'inline script serialization must escape HTML delimiters');
    const restored = runInNewContext(`(${serialized})`);
    assert.equal(restored.html, '</script><script>alert(1)</script>');
    assert.equal(restored.pattern.source, 'docs');
    assert.equal(restored.pattern.flags, 'gi');
    assert.equal(restored.date.toISOString(), '2026-01-01T00:00:00.000Z');
    assert.equal(restored.missing, undefined);
  }
});

// Tinypool v2.0.0 only drops Node 18; the options/run/destroy API used by
// Docusaurus 3.10.2 remains intact. v2.1.2 fixes GHSA-85c8-ppgw-ccpr.
// https://github.com/tinylibs/tinypool/releases/tag/v2.0.0
// Exercise real worker threads even though this site's default SSG is inline.
test('patched Tinypool supports the Docusaurus worker lifecycle', {timeout: 10000}, async () => {
  const coreRequire = createRequire(require.resolve('@docusaurus/core/package.json'));
  const {default: Tinypool} = await import(coreRequire.resolve('tinypool'));
  const directory = await mkdtemp(join(tmpdir(), 'glyphora-tinypool-'));
  let pool;
  try {
    const filename = join(directory, 'worker.mjs');
    await writeFile(filename, `
      import {workerData} from 'node:worker_threads';
      export default function(task) {
        return {id: task.id, params: workerData[1].params, workerId: process.__tinypool_state__.workerId};
      }
    `);
    pool = new Tinypool({
      filename,
      minThreads: 2,
      maxThreads: 2,
      concurrentTasksPerWorker: 1,
      runtime: 'worker_threads',
      isolateWorkers: false,
      workerData: {params: {site: 'glyphora'}},
      maxMemoryLimitBeforeRecycle: 0,
      resourceLimits: {},
    });
    const results = await Promise.all([pool.run({id: 1}), pool.run({id: 2})]);
    assert.deepEqual(results.map(result => result.id), [1, 2]);
    for (const result of results) {
      assert.deepEqual(result.params, {site: 'glyphora'});
      assert.ok(Number.isInteger(result.workerId));
    }
  } finally {
    await pool?.destroy();
    await rm(directory, {recursive: true, force: true});
  }
});

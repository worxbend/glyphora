import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
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
  assert.equal(mermaidPackage.version, manifest.overrides['@docusaurus/theme-mermaid'].mermaid);
  assert.match(mermaidPackage.version, /^11\./);
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

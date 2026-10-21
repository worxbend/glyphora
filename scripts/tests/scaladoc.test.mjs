import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import vm from 'node:vm';
import {after, test} from 'node:test';

const directory = mkdtempSync(join(tmpdir(), 'glyphora-scaladoc-fixture-'));
after(() => rmSync(directory, {recursive: true, force: true}));
mkdirSync(join(directory, 'nested'));
writeFileSync(join(directory, 'index.html'), '<html><head><title>Fixture</title></head><body>fixture</body></html>');
writeFileSync(join(directory, 'nested', 'type.html'), '<head></head>');
writeFileSync(join(directory, 'fragment.html'), '<body>no head</body>');
writeFileSync(join(directory, 'existing.html'), '<head><!-- glyphora-api-theme --></head>');
writeFileSync(join(directory, 'ignored.txt'), '<head></head>');
const script = new URL('../style-scaladoc.mjs', import.meta.url);
const first = execFileSync(process.execPath, [script.pathname, directory], {encoding: 'utf8'});
const html = readFileSync(join(directory, 'index.html'), 'utf8');
const bridge = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function runBridge(initial = {}, {dark = false, storageThrows = false, buttonIds = []} = {}) {
  const values = new Map(Object.entries(initial));
  const ready = [];
  const timers = [];
  const clicks = new Map();
  let currentDark = dark;
  const context = {
    localStorage: {
      getItem(key) { if (storageThrows) throw new Error('fixture denied'); return values.get(key) ?? null; },
      setItem(key, value) { if (storageThrows) throw new Error('fixture denied'); values.set(key, value); },
    },
    document: {
      documentElement: {classList: {
        toggle(name, value) { assert.equal(name, 'theme-dark'); currentDark = value; },
        contains(name) { assert.equal(name, 'theme-dark'); return currentDark; },
      }},
      addEventListener(event, callback) { assert.equal(event, 'DOMContentLoaded'); ready.push(callback); },
      getElementById(id) { return buttonIds.includes(id) ? {
        addEventListener(event, callback) { assert.equal(event, 'click'); clicks.set(id, callback); },
      } : null; },
    },
    setTimeout(callback, delay) { assert.equal(delay, 0); timers.push(callback); },
  };
  vm.runInNewContext(bridge, context, {timeout: 1000});
  return {values, ready, timers, clicks, isDark: () => currentDark, setDark: (value) => { currentDark = value; }};
}

test('injects recursively before head close; skips marked, non-HTML and headless files', () => {
  assert.match(first, /to 2 Scaladoc pages/);
  assert.equal((html.match(/glyphora-api-theme/g) ?? []).length, 1);
  assert.match(html, /href="\/glyphora\/api\/glyphora-api.css"/);
  assert.ok(html.indexOf('<script>') < html.indexOf('</head>'));
  assert.match(readFileSync(join(directory, 'nested', 'type.html'), 'utf8'), /glyphora-api-theme/);
  assert.equal(readFileSync(join(directory, 'fragment.html'), 'utf8'), '<body>no head</body>');
  assert.equal(readFileSync(join(directory, 'existing.html'), 'utf8'), '<head><!-- glyphora-api-theme --></head>');
  assert.equal(readFileSync(join(directory, 'ignored.txt'), 'utf8'), '<head></head>');
});

test('second styling pass is byte-idempotent', () => {
  const second = execFileSync(process.execPath, [script.pathname, directory], {encoding: 'utf8'});
  assert.match(second, /to 0 Scaladoc pages/);
  assert.equal(readFileSync(join(directory, 'index.html'), 'utf8'), html);
});

test('changed guide choice controls API before first paint', () => {
  for (const theme of ['dark', 'light']) {
    const run = runBridge({theme, 'glyphora-theme-synced-from-site': 'different'}, {dark: theme !== 'dark'});
    assert.equal(run.isDark(), theme === 'dark');
    assert.equal(run.values.get('use-dark-theme'), String(theme === 'dark'));
    assert.equal(run.values.get('glyphora-theme-synced-from-site'), theme);
  }
});

test('unchanged guide choice preserves API toggle and absent guide does nothing', () => {
  const run = runBridge({theme: 'light', 'glyphora-theme-synced-from-site': 'light', 'use-dark-theme': 'true'}, {dark: true});
  assert.equal(run.isDark(), true);
  assert.equal(run.values.get('use-dark-theme'), 'true');
  const absent = runBridge({}, {dark: true});
  assert.equal(absent.isDark(), true);
  assert.equal(absent.values.size, 0);
});

test('desktop and mobile clicks persist post-toggle theme asynchronously', () => {
  const run = runBridge({}, {buttonIds: ['theme-toggle', 'mobile-theme-toggle']});
  run.ready.forEach((callback) => callback());
  for (const id of ['theme-toggle', 'mobile-theme-toggle']) {
    run.clicks.get(id)();
    assert.equal(run.values.size, id === 'theme-toggle' ? 0 : 2);
    run.setDark(id === 'theme-toggle');
    run.timers.shift()();
    const expected = id === 'theme-toggle' ? 'dark' : 'light';
    assert.equal(run.values.get('theme'), expected);
    assert.equal(run.values.get('glyphora-theme-synced-from-site'), expected);
  }
});

test('missing buttons and denied storage do not throw, even on click', () => {
  const missing = runBridge();
  missing.ready.forEach((callback) => callback());
  assert.equal(missing.clicks.size, 0);
  const denied = runBridge({theme: 'dark'}, {storageThrows: true, buttonIds: ['theme-toggle']});
  denied.ready.forEach((callback) => callback());
  denied.clicks.get('theme-toggle')();
  denied.timers.shift()();
  assert.equal(denied.values.size, 1);
});

test('current non-null invalid site value is interpreted as light, not rejected', () => {
  const run = runBridge({theme: 'fixture-invalid'}, {dark: true});
  assert.equal(run.isDark(), false);
  assert.equal(run.values.get('use-dark-theme'), 'false');
  assert.equal(run.values.get('glyphora-theme-synced-from-site'), 'fixture-invalid');
});

#!/usr/bin/env node

import {mkdir, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

import {docsNavigation} from '../website/docs-navigation.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const docsDir = resolve(root, 'website/docs');
const outputArg = process.argv.indexOf('--output');
const outputDir = resolve(
  root,
  outputArg >= 0 ? process.argv[outputArg + 1] : 'build/wiki',
);
const repoUrl = 'https://github.com/worxbend/glyphora';
const pagesUrl = 'https://worxbend.github.io/glyphora';

function flattenNavigation(items, category = null) {
  return items.flatMap((item) =>
    typeof item === 'string'
      ? [{id: item, category}]
      : flattenNavigation(item.items, item.label),
  );
}

function parseDocument(source, id) {
  const frontmatter = source.match(/^---\n([\s\S]*?)\n---\n/);
  if (!frontmatter) throw new Error(`${id}.md is missing YAML front matter`);
  const title = frontmatter[1].match(/^title:\s*(.+)$/m)?.[1]?.trim();
  if (!title) throw new Error(`${id}.md is missing a front matter title`);
  return {title, body: source.slice(frontmatter[0].length).trim()};
}

function wikiFileName(id, title) {
  if (id === 'intro') return 'Home.md';
  const safeTitle = title
    .replace(/&/g, 'and')
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-|-$/g, '');
  return `${safeTitle}.md`;
}

function rewriteForWiki(markdown, pages) {
  let result = markdown.replace(
    /src="\/glyphora\/banner\.svg"/g,
    `src="https://raw.githubusercontent.com/worxbend/glyphora/main/docs/assets/banner.svg"`,
  );

  result = result.replace(
    /\]\(pathname:\/\/\/api(\/[^)]*)?\)/g,
    (_, path = '/') => `](${pagesUrl}/api${path})`,
  );

  result = result.replace(
    /\]\(\.\/([a-z0-9-]+)(#[^)]+)?\)/g,
    (match, id, anchor = '') => {
      const target = pages.get(id);
      return target ? `](${target.wikiName.replace(/\.md$/, '')}${anchor})` : match;
    },
  );

  return result;
}

const navigation = flattenNavigation(docsNavigation);
const sourceFiles = (await readdir(docsDir))
  .filter((name) => name.endsWith('.md'))
  .map((name) => name.replace(/\.md$/, ''))
  .sort();
const navigationIds = navigation.map(({id}) => id).sort();

if (sourceFiles.join('\n') !== navigationIds.join('\n')) {
  const missing = sourceFiles.filter((id) => !navigationIds.includes(id));
  const stale = navigationIds.filter((id) => !sourceFiles.includes(id));
  throw new Error(
    `Documentation navigation is out of sync.` +
      `\nMissing from navigation: ${missing.join(', ') || 'none'}` +
      `\nMissing source files: ${stale.join(', ') || 'none'}`,
  );
}

const pages = new Map();
for (const {id, category} of navigation) {
  const source = await readFile(resolve(docsDir, `${id}.md`), 'utf8');
  const document = parseDocument(source, id);
  pages.set(id, {
    ...document,
    id,
    category,
    wikiName: wikiFileName(id, document.title),
  });
}

await rm(outputDir, {recursive: true, force: true});
await mkdir(outputDir, {recursive: true});

const generatedNotice =
  '<!-- Generated from website/docs by scripts/export-wiki.mjs. Do not edit in the Wiki. -->';

for (const page of pages.values()) {
  const body = rewriteForWiki(page.body, pages);
  await writeFile(
    resolve(outputDir, page.wikiName),
    `${generatedNotice}\n\n${body}\n`,
  );
}

const sidebar = ['# glyphora', '', `[Home](Home)`];
let currentCategory = Symbol('unset');
for (const page of pages.values()) {
  if (page.id === 'intro') continue;
  const category = page.category ?? 'Start here';
  if (category !== currentCategory) {
    currentCategory = category;
    sidebar.push('', `## ${category}`, '');
  }
  sidebar.push(`- [${page.title}](${page.wikiName.replace(/\.md$/, '')})`);
}
sidebar.push(
  '',
  '## Reference',
  '',
  `- [Scaladoc API](${pagesUrl}/api/)`,
  `- [Styled documentation](${pagesUrl}/)`,
  `- [Source repository](${repoUrl})`,
);
await writeFile(resolve(outputDir, '_Sidebar.md'), `${sidebar.join('\n')}\n`);

const footer = [
  `Documentation is maintained in [\`website/docs\`](${repoUrl}/tree/main/website/docs).`,
  `Read the [styled guide](${pagesUrl}/) · [API reference](${pagesUrl}/api/) · [MIT license](${repoUrl}/blob/main/LICENSE)`,
].join(' ');
await writeFile(resolve(outputDir, '_Footer.md'), `${footer}\n`);

const generatedFiles = new Set(await readdir(outputDir));
const brokenLinks = [];
for (const file of generatedFiles) {
  const markdown = await readFile(resolve(outputDir, file), 'utf8');
  const prose = markdown
    .replace(/```[\s\S]*?```/g, '')
    .replace(/`[^`\n]+`/g, '');
  for (const match of prose.matchAll(/(?<!!)\[[^\]]+\]\(([^)]+)\)/g)) {
    const href = match[1];
    if (/^(?:https?:|mailto:|#)/.test(href)) continue;
    const pageName = href.split('#', 1)[0];
    const target = pageName.endsWith('.md') ? pageName : `${pageName}.md`;
    if (!generatedFiles.has(target)) brokenLinks.push(`${file}: ${href}`);
  }
}

if (brokenLinks.length > 0) {
  throw new Error(`Broken generated Wiki links:\n${brokenLinks.join('\n')}`);
}

console.log(`Exported ${pages.size} documentation pages to ${outputDir}`);

// Static checks on the exported landing page (run after `pnpm build`). No dependencies.
// Fails on placeholder links, unknown destinations, missing anchors, pricing or backend
// integrations, and external links that open a new tab without rel="noopener".
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const html = readFileSync(join(root, 'out', 'index.html'), 'utf8');
const failures = [];
const fail = (msg) => failures.push(msg);

const allowedOrigins = ['https://github.com/Jerophin123/JTrac', 'https://sourceforge.net/projects/j-trac/'];
const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]));
const downloadLinks = new Set();
const anchors = [...html.matchAll(/<a\b[^>]*>/g)].map((m) => m[0]);

for (const tag of anchors) {
  const href = /\shref="([^"]*)"/.exec(tag)?.[1];
  if (href === undefined) fail(`anchor without href: ${tag}`);
  else if (href === '' || href === '#') fail(`placeholder link: ${tag}`);
  else if (href.startsWith('#')) {
    if (!ids.has(href.slice(1))) fail(`in-page link to missing id: ${href}`);
  } else if (!allowedOrigins.some((o) => href.startsWith(o))) fail(`unverified destination: ${href}`);
  else if (href.includes('/releases/download/')) {
    // Direct downloads stay in the tab and must point at a versioned release asset.
    if (!/\/releases\/download\/v\d+\.\d+\.\d+\/[\w.-]+$/.test(href)) fail(`malformed download link: ${href}`);
    downloadLinks.add(href);
  } else if (!/\srel="[^"]*noopener/.test(tag) || !/\starget="_blank"/.test(tag)) fail(`external link without target/rel: ${href}`);
}

if (!['windows-x64.zip', 'linux-x64.tar.gz', 'linux-arm64.tar.gz', 'macos-arm64.tar.gz', 'macos-x64.tar.gz'].every((p) => [...downloadLinks].some((l) => l.endsWith(p))))
  fail('missing a direct download for one of the five packages');

const h1s = html.match(/<h1\b/g)?.length ?? 0;
if (h1s !== 1) fail(`expected exactly one <h1>, found ${h1s}`);
for (const id of ['features', 'product', 'classic', 'technology', 'download', 'docs', 'open-source']) if (!ids.has(id)) fail(`missing section #${id}`);
for (const img of html.match(/<img\b[^>]*>/g) ?? []) if (!/\salt="/.test(img)) fail(`image without alt: ${img}`);

const text = html.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<[^>]+>/g, ' ');
for (const word of [/\bpricing\b/i, /\bsubscri(be|ption)/i, /\bcheckout\b/i, /\bper month\b/i, /\bper seat\b/i, /\bnewsletter\b/i, /\bsign up\b/i, /\$\s?\d/]) {
  if (word.test(text)) fail(`forbidden marketing term on page: ${word}`);
}

// --- SEO -------------------------------------------------------------------------------
const head = html.slice(0, html.indexOf('</head>'));
const meta = (attr, key) => new RegExp(`<meta [^>]*${attr}="${key}"[^>]*content="([^"]*)"`).exec(head)?.[1];
const decode = (s) => s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#x27;|&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
const title = /<title>([^<]*)<\/title>/.exec(head)?.[1];
const description = meta('name', 'description');
if (!/<html [^>]*lang="en"/.test(html)) fail('missing <html lang>');
if (!title || title.length < 30 || title.length > 60) fail(`title should be 30-60 characters: ${String(title?.length)}`);
if (!description || description.length < 70 || description.length > 160) fail(`description should be 70-160 characters: ${String(description?.length)}`);
if (/noindex|nofollow/.test(meta('name', 'robots') ?? '')) fail('page is marked noindex/nofollow');
if (!/<link rel="icon" href="[^"]+"/.test(head)) fail('missing favicon link (search results show it)');
if (!/<link rel="apple-touch-icon"/.test(head)) fail('missing apple-touch-icon link');
for (const key of ['og:title', 'og:description', 'og:type', 'og:site_name']) if (!meta('property', key)) fail(`missing ${key}`);
for (const key of ['twitter:card', 'twitter:title', 'twitter:description']) if (!meta('name', key)) fail(`missing ${key}`);
// URLs only: the FAQ legitimately mentions binding to 127.0.0.1.
if (/https?:\/\/(localhost|127\.0\.0\.1)/.test(html)) fail('a localhost URL leaked into the page');

// Heading outline: one h1 (checked above) and no skipped levels.
let level = 0;
for (const [, n] of html.matchAll(/<h([1-6])\b/g)) {
  if (Number(n) > level + 1) fail(`heading level skips from h${String(level)} to h${n}`);
  level = Number(n);
}

// Structured data must parse and match visible content.
const ld = /<script type="application\/ld\+json">([\s\S]*?)<\/script>/.exec(html);
if (!ld) fail('missing JSON-LD');
else {
  const graph = JSON.parse(ld[1])['@graph'] ?? [];
  const types = graph.map((g) => g['@type']);
  for (const t of ['SoftwareApplication', 'SoftwareSourceCode', 'FAQPage']) if (!types.includes(t)) fail(`JSON-LD missing ${t}`);
  const visible = decode(text);
  for (const q of graph.find((g) => g['@type'] === 'FAQPage')?.mainEntity ?? []) {
    if (!visible.includes(q.name)) fail(`FAQ structured data not visible on page: ${q.name}`);
    if (!visible.includes(q.acceptedAnswer.text)) fail(`FAQ answer not visible on page: ${q.name}`);
  }
}

// Absolute URLs exist only once the site address is set (lib/site.ts siteUrl).
const siteUrl = /export const siteUrl: string \| null = '([^']+)'/.exec(readFileSync(join(root, 'lib', 'site.ts'), 'utf8'))?.[1];
const canonical = /<link rel="canonical" href="([^"]+)"/.exec(head)?.[1];
const robots = readFileSync(join(root, 'out', 'robots.txt'), 'utf8');
const sitemap = readFileSync(join(root, 'out', 'sitemap.xml'), 'utf8');
if (!/User-Agent: \*\s+Allow: \//i.test(robots)) fail('robots.txt does not allow crawling');
if (siteUrl) {
  if (canonical !== siteUrl) fail(`canonical should be ${siteUrl}, is ${String(canonical)}`);
  if (meta('property', 'og:url') !== siteUrl) fail('og:url should be the site URL');
  if (!meta('property', 'og:image')?.startsWith(siteUrl)) fail('og:image should be absolute on the site');
  if (!sitemap.includes(`<loc>${siteUrl}</loc>`)) fail('sitemap.xml does not list the site URL');
  if (!robots.includes(`Sitemap: ${siteUrl}sitemap.xml`)) fail('robots.txt does not reference the sitemap');
} else if (canonical || meta('property', 'og:url')) fail('canonical/og:url emitted without a site URL');

// The Google tag is the only third-party script, and it must be on the page.
const gaId = /export const gaMeasurementId: string \| null = '([^']+)'/.exec(readFileSync(join(root, 'lib', 'site.ts'), 'utf8'))?.[1];
const thirdPartyScripts = [...html.matchAll(/<script[^>]+src="(https?:\/\/[^"]+)"/g)].map((m) => m[1]);
for (const src of thirdPartyScripts) if (!src.startsWith('https://www.googletagmanager.com/gtag/js?id=')) fail(`unexpected third-party script: ${src}`);
if (gaId) {
  const tags = html.split(`googletagmanager.com/gtag/js?id=${gaId}`).length - 1;
  if (tags < 1) fail(`Google tag ${gaId} is not on the page`);
  // The inline gtag('config') call is injected at runtime by next/script (afterInteractive);
  // it is verified in a browser, not in the static HTML.
}

// Source must not talk to any backend (analytics aside, see above).
const sourceDirs = ['app', 'components', 'lib'];
const banned = /supabase|firebase|stripe|\bfetch\(|XMLHttpRequest|axios|process\.env/i;
const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
for (const file of sourceDirs.flatMap((d) => walk(join(root, d)))) {
  if (/\.(tsx?|css)$/.test(file) && banned.test(readFileSync(file, 'utf8'))) fail(`backend/service reference in ${file}`);
}

if (failures.length) {
  console.error(`check-site: ${failures.length} problem(s)\n - ${failures.join('\n - ')}`);
  process.exit(1);
}
console.log(`check-site: OK (${anchors.length} links, ${downloadLinks.size} direct downloads, ${ids.size} ids; SEO: title ${String(title?.length)} chars, description ${String(description?.length)} chars, heading outline, JSON-LD, ${siteUrl ? `canonical ${siteUrl}` : "site URL not set (no canonical yet)"}; no pricing or backend references)`);

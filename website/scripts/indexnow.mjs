// Tells IndexNow search engines (Bing, Yandex, Seznam, Naver...) that every URL in the
// sitemap changed. Run after a deploy: `pnpm indexnow`. Google does not use IndexNow; resubmit
// the sitemap in Search Console instead. Reads the key from public/<key>.txt and the URL
// list from out/sitemap.xml (run `pnpm build` first).
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const keyFile = readdirSync(join(root, 'public')).find((f) => /^[0-9a-f]{32}\.txt$/.test(f));
if (!keyFile) throw new Error('No IndexNow key file (public/<32 hex>.txt)');
const key = keyFile.slice(0, -4);
const urlList = [...readFileSync(join(root, 'out', 'sitemap.xml'), 'utf8').matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);
if (!urlList.length) throw new Error('out/sitemap.xml lists no URLs; set siteUrl and build first');
const { host, origin } = new URL(urlList[0]);

const res = await fetch('https://api.indexnow.org/indexnow', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  body: JSON.stringify({ host, key, keyLocation: `${origin}/${keyFile}`, urlList }),
});
console.log(`IndexNow: HTTP ${String(res.status)} for ${String(urlList.length)} URLs (200/202 = accepted)`);
if (!res.ok) process.exit(1);

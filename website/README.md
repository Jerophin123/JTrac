# Jtrac landing page

Static marketing page for Jtrac, built with Next.js 16 (`output: 'export'`). It has no
backend, API calls, forms or environment variables. Outbound links go only to the GitHub
repository (and SourceForge for the original JTrac). The only third-party script is the
Google Analytics 4 tag (`gaMeasurementId` in `lib/site.ts`, loaded after the page is
interactive); the Jtrac product itself has no telemetry.

```powershell
pnpm install
pnpm dev            # http://localhost:3000
pnpm typecheck; pnpm lint
pnpm build          # static site in out/
pnpm check:site     # link, anchor, heading and content checks on out/index.html
```

## Design: one language with the product

- `app/product-tokens.css` is generated from `../frontend/src/styles/tokens.css` (base +
  dark theme) with the Jtrac logo orange as the accent, the same way the product applies
  an organization brand color.
- `components/product/styles/` holds copies of the product's CSS Modules (each file names
  its source). Only viewport rules were adapted to container queries for the embedded
  replica. Change styles in `frontend/` first, then re-copy.
- `components/product/` renders a working replica of the app (shell, sidebar, Board,
  List, Timeline, issue page with real workflow transitions) from demo data that is
  labelled as such on the page.
- `components/landing.module.css` applies the product's shape language to the page:
  slanted button and nav fills, page-title speed strokes, two-corner containers.

## Downloads

Download buttons link straight to the release assets
(`/releases/download/v<version>/<file>`), never to the Releases page. The hero and nav
buttons detect the visitor's OS (and CPU via client hints) and download that package
directly; when unsure (phones, Macs without client hints) they jump to the `#download`
table. **On every release**, update `latestRelease` and the `sizeMb` values in
`lib/site.ts` from the GitHub release API; `pnpm check:site` fails if any of the five
packages is missing or a download URL is malformed.

## SEO

- **Site address:** `siteUrl` in `lib/site.ts` is `https://www.jtrac.site/` (Vercel). It
  drives the canonical URL, `og:url`, absolute share-image URLs, `sitemap.xml` and the
  `Sitemap:` line in `robots.txt`; `pnpm check:site` requires all of them.
- **Pages:** the home page plus the subpages in `lib/pages.ts` (`/features/`, `/download/`,
  `/technology/`, `/docs/`, `/faq/`, `/open-source/`). Each has its own title, description,
  canonical URL and `h1`, is listed in `sitemap.xml` and linked from the footer. In-page
  `#anchors` are not separate URLs to search engines, so anything that should appear as its
  own search result needs an entry there.
- **Metadata** (`lib/seo.ts`): title (30-60 chars), description (70-160 chars), robots and
  googlebot directives, Open Graph and X cards, author, icons, web manifest.
- **Structured data:** every page carries JSON-LD `WebSite` (its `name` is the site name
  Google shows), `Organization` (logo), `Person`, `SoftwareApplication` (version, OS, features,
  direct download URLs, licence, free), `SoftwareSourceCode` and a `WebPage`; subpages add a
  `BreadcrumbList`. `FAQPage` is on the home page only. The FAQ comes from `lib/faq.ts`, the
  same data the visible FAQ renders, so they cannot drift. No ratings or reviews are marked up:
  there are none to cite.
- **AI search:** `/llms.txt` summarises the pages, downloads, docs and FAQ in plain text.
- **IndexNow:** `public/<key>.txt` is the key file; after a deploy, `pnpm build && pnpm indexnow`
  notifies Bing, Yandex and other IndexNow engines of every sitemap URL. Google does not use
  IndexNow: resubmit the sitemap in Search Console.
- **URLs:** `vercel.json` sets `trailingSlash: true`, so `/features` redirects to `/features/`
  and each page has one URL.
- **Files:** `robots.txt`, `sitemap.xml`, `manifest.webmanifest`, `og-image.png` (1200x630),
  `apple-icon.png`, `favicon.ico` (48x48) and `icons/icon-{48,96,192,512}.png` are generated
  at build time (`lib/mark-icon.tsx`). Google Search shows a favicon only if it is square, a
  multiple of 48px and crawlable; it also requests `/favicon.ico` directly.
- **Outline:** one `h1`, no skipped heading levels. Replica titles are styled `p` elements and
  demo areas carry `data-nosnippet`, so demo data stays out of the outline and search snippets.
- `pnpm check:site` enforces all of the above.
- **Search Console:** `public/googleb41baf20f70d21c3.html` must stay byte-identical. Vercel
  serves exported `.html` files at clean URLs (`/googleb41baf20f70d21c3/`), so `vercel.json`
  rewrites the exact `.html` path Google requests to it.

## Notes

The hero video is loaded only on wide screens without reduced motion or Save-Data;
the CSS backdrop is always present as the fallback.

Hosting: deploy `out/` to any static host at the domain root.

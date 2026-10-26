# JTRAC landing page

Static marketing page for JTRAC, built with Next.js 16 (`output: 'export'`). It has no
backend, API calls, forms or environment variables. Outbound links go only to the GitHub
repository (and SourceForge for the original JTrac). The only third-party script is the
Google Analytics 4 tag (`gaMeasurementId` in `lib/site.ts`, loaded after the page is
interactive); the JTRAC product itself has no telemetry.

```powershell
pnpm install
pnpm dev            # http://localhost:3000
pnpm typecheck; pnpm lint
pnpm build          # static site in out/
pnpm check:site     # link, anchor, heading and content checks on out/index.html
```

## Design: one language with the product

- `app/product-tokens.css` is generated from `../frontend/src/styles/tokens.css` (base +
  dark theme) with the JTRAC logo orange as the accent, the same way the product applies
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
- **Metadata** (`lib/seo.ts`): title (30-60 chars), description (70-160 chars), robots and
  googlebot directives, Open Graph and X cards, author, icons, web manifest.
- **Structured data:** JSON-LD `SoftwareApplication` (version, OS, direct download URLs,
  licence, free), `SoftwareSourceCode` and `FAQPage`. The FAQ comes from `lib/faq.ts`, the same
  data the visible FAQ renders, so they cannot drift.
- **Files:** `robots.txt`, `sitemap.xml`, `manifest.webmanifest`, `og-image.png` (1200x630) and
  `apple-icon.png` are generated at build time.
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

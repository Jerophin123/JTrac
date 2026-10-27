# ADR 0007: Frontend architecture and design system

- Status: Accepted
- Date: 2026-10-24

## Context

JTAC needs a fast, accessible, distinctive UI with one styling system and real backend
data (no placeholder data presented as real).

## Decision

**Stack** (versions in ADR 0003): React 19 + TypeScript (strict, `noUncheckedIndexedAccess`),
Vite, pnpm.

| Concern | Choice |
|---|---|
| Routing | TanStack Router, code-based routes in `src/app/router.tsx`; auth guard in a pathless `authenticated` route (`beforeLoad`), safe internal-only redirects |
| Server state | TanStack Query (the session is query `['session']`; `queryClient.query` revalidates when stale) |
| API client | `openapi-fetch` typed by `openapi-typescript`; CSRF middleware in `src/api/client.ts` |
| Forms / validation | react-hook-form + zod; server `validation-failed` field errors mapped back to fields |
| Styling | **CSS custom-property tokens** (`src/styles/tokens.css`) + **CSS Modules**. No Tailwind or CSS-in-JS: one styling system |
| Icons | lucide-react (single icon family) |
| Fonts | IBM Plex Sans (variable) and IBM Plex Mono, self-hosted via @fontsource (CSP `font-src 'self'`) |
| Tests | Vitest + Testing Library (unit/component), Playwright + axe-core (e2e, light & dark, desktop & mobile) |

**Folder layout**: `src/app` (router, shell, status screens), `src/features/<feature>`
(pages, queries, mutations), `src/api` (generated schema, client, problems, CSRF),
`src/ui` (design-system primitives), `src/styles` (tokens, base), `src/lib` (utilities).

**Visual identity**: warm stone neutrals, one natural accent (clay `#b4532a` light /
`#e07c50` dark), no gradients or glass effects; floating, softly separated sidebar;
4-px spacing grid; radius 6/8/12; borders before shadows; 120–180 ms motion that honours
`prefers-reduced-motion`; status conveyed by shape as well as colour.

**Accessibility rules**: every text/background token pair ≥ 4.5:1 (enforced by axe in
e2e for both themes); visible `:focus-visible` ring; skip link; labelled landmarks;
errors use `role="alert"`, status uses `role="status"`; logical CSS properties for RTL.

**Theme**: light/dark/system stored per device in `localStorage` (`jtac.theme`), applied
before first paint by `public/theme-init.js` (external file: the CSP forbids inline
scripts).

## Consequences

- New UI must consume semantic tokens, never raw colours.
- Feature modules own their queries; cross-feature imports go through `src/ui` or `src/api`.
- Drag-and-drop and command-palette libraries are selected in their milestones (M5),
  against accessibility and performance criteria.
- **Source maps** are not generated for production (`build.sourcemap: false`, decided
  2026-10-27, from 3.0.4). Until 3.0.2 they were generated and served at
  `/assets/*.js.map`, which let anyone with browser developer tools rebuild the original
  `src/` tree. A 2026-10-24 review had found no environment values, credentials or local
  paths in them, but serving them gave no benefit to people running JTRAC. The release
  gate fails a package whose web UI contains a `.map` file or a `sourceMappingURL`
  reference. The Vite dev server still provides source maps for local development.
  `dist/` is never committed.

## Alternatives considered

- React Router 8: viable; TanStack Router + Query give type-safe search params for
  saved views and one coherent toolkit.
- Tailwind CSS: rejected to avoid a second styling vocabulary next to tokens.

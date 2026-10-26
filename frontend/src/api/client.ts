import createClient, { type Middleware } from 'openapi-fetch';
import { CSRF_HEADER, ensureCsrfToken, requiresCsrf } from './csrf';
import type { paths } from './schema';

/** Attaches the CSRF token to unsafe requests (double-submit cookie, see ADR 0005). */
export const csrfMiddleware: Middleware = {
  async onRequest({ request }) {
    if (requiresCsrf(request.method)) {
      const token = await ensureCsrfToken();
      if (token) {
        request.headers.set(CSRF_HEADER, token);
      }
    }
    return request;
  },
};

/**
 * Typed client generated from api/openapi.yaml (regenerate with `pnpm gen:api`).
 * Same-origin only: credentials are the HttpOnly session cookie, sent automatically.
 */
export const api = createClient<paths>({
  // Explicit same-origin base: openapi-fetch builds `Request` objects, which need absolute URLs.
  baseUrl: window.location.origin,
  credentials: 'same-origin',
  headers: { Accept: 'application/json, application/problem+json' },
});

api.use(csrfMiddleware);

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';
const CSRF_ENDPOINT = '/api/v1/auth/csrf';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

export function readCookie(name: string, cookieString: string = document.cookie): string | undefined {
  for (const part of cookieString.split(';')) {
    const [rawKey, ...rest] = part.trim().split('=');
    if (rawKey === name) {
      return decodeURIComponent(rest.join('='));
    }
  }
  return undefined;
}

export function requiresCsrf(method: string): boolean {
  return !SAFE_METHODS.has(method.toUpperCase());
}

/** Asks the server for a fresh CSRF cookie and returns its value. */
export async function refreshCsrfToken(fetchImpl: typeof fetch = fetch): Promise<string | undefined> {
  await fetchImpl(CSRF_ENDPOINT, { credentials: 'same-origin', cache: 'no-store' });
  return readCookie(CSRF_COOKIE);
}

/**
 * Returns the current CSRF token, fetching a fresh cookie when there is none (first
 * visit, or after login/logout, which rotate the token). The token lives only in the
 * cookie the server sets, never in localStorage or sessionStorage.
 */
export async function ensureCsrfToken(fetchImpl: typeof fetch = fetch): Promise<string | undefined> {
  return readCookie(CSRF_COOKIE) ?? refreshCsrfToken(fetchImpl);
}

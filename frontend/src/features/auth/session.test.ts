import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiError } from '../../api/problem';

const problem = (status: number, type: string) =>
  new Response(JSON.stringify({ type, title: 'Problem', status, requestId: 'req-1' }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const user = { id: '0192f4a0-0000-7000-8000-000000000001', email: 'ada@example.com', displayName: 'Ada Lovelace', handle: 'ada' };

/**
 * openapi-fetch captures `fetch` when the client module loads, so stub first, then import.
 * ApiError is taken from the same freshly loaded module graph so `instanceof` is meaningful.
 */
async function loadSession(fetchMock: typeof fetch) {
  vi.stubGlobal('fetch', fetchMock);
  const [sessionModule, problemModule] = await Promise.all([import('./session'), import('../../api/problem')]);
  return { ...sessionModule, ApiError: problemModule.ApiError };
}

beforeEach(() => {
  vi.resetModules();
  document.cookie = 'XSRF-TOKEN=t; path=/';
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchSession', () => {
  it('returns the session when signed in', async () => {
    const { fetchSession } = await loadSession(vi.fn<typeof fetch>(() => Promise.resolve(json({ user }))));
    await expect(fetchSession()).resolves.toEqual({ user });
  });

  it('returns null when the server says unauthenticated', async () => {
    const { fetchSession } = await loadSession(
      vi.fn<typeof fetch>(() => Promise.resolve(problem(401, 'urn:jtac:problem:unauthenticated'))),
    );
    await expect(fetchSession()).resolves.toBeNull();
  });

  it('throws an ApiError carrying the problem for other failures', async () => {
    const { fetchSession, ApiError } = await loadSession(
      vi.fn<typeof fetch>(() => Promise.resolve(problem(500, 'urn:jtac:problem:internal-error'))),
    );
    const error = await fetchSession().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).problem?.requestId).toBe('req-1');
  });
});

describe('login', () => {
  it('refreshes the CSRF token once and retries when the token was rejected', async () => {
    const responses = [problem(403, 'urn:jtac:problem:csrf-rejected'), new Response(null, { status: 204 }), json({ user })];
    const fetchMock = vi.fn<typeof fetch>(() => Promise.resolve(responses.shift() ?? json({}, 500)));
    const { login } = await loadSession(fetchMock);

    await expect(login({ email: 'ada@example.com', password: 'pw' })).resolves.toEqual({ user });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('does not retry other 403 answers such as an unverified email address', async () => {
    const fetchMock = vi.fn<typeof fetch>(() => Promise.resolve(problem(403, 'urn:jtac:problem:email-not-verified')));
    const { login, ApiError } = await loadSession(fetchMock);
    const error = await login({ email: 'ada@example.com', password: 'pw' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).is('urn:jtac:problem:email-not-verified')).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('surfaces invalid credentials as a typed problem', async () => {
    const { login, ApiError } = await loadSession(
      vi.fn<typeof fetch>(() => Promise.resolve(problem(401, 'urn:jtac:problem:invalid-credentials'))),
    );
    const error = await login({ email: 'ada@example.com', password: 'nope' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).is('urn:jtac:problem:invalid-credentials')).toBe(true);
  });
});

describe('safeRedirect', async () => {
  const { safeRedirect } = await import('./session');

  it.each([
    ['/', '/'],
    ['/projects?tab=1', '/projects?tab=1'],
  ])('accepts the internal path %s', (input, expected) => {
    expect(safeRedirect(input)).toBe(expected);
  });

  it.each([
    'https://evil.example',
    '//evil.example',
    '/\\evil.example',
    '/\t/evil.example',
    '/\n/evil.example',
    'javascript:alert(1)',
    '/login',
    '/login?redirect=/',
    42,
    undefined,
  ])('rejects %j', (input) => {
    expect(safeRedirect(input)).toBeUndefined();
  });
});

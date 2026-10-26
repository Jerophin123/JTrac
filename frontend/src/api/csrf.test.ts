import { describe, expect, it, vi } from 'vitest';
import { csrfMiddleware } from './client';
import { CSRF_HEADER, ensureCsrfToken, readCookie, requiresCsrf } from './csrf';

describe('readCookie', () => {
  it('finds a cookie among several and decodes it', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1; XSRF-TOKEN=abc%3D%3D; b=2')).toBe('abc==');
  });

  it('returns undefined when absent', () => {
    expect(readCookie('XSRF-TOKEN', 'a=1')).toBeUndefined();
  });
});

describe('requiresCsrf', () => {
  it.each(['POST', 'put', 'PATCH', 'DELETE'])('requires a token for %s', (method) => {
    expect(requiresCsrf(method)).toBe(true);
  });

  it.each(['GET', 'HEAD', 'OPTIONS'])('does not require a token for %s', (method) => {
    expect(requiresCsrf(method)).toBe(false);
  });
});

describe('ensureCsrfToken', () => {
  it('uses the existing cookie without a network call', async () => {
    document.cookie = 'XSRF-TOKEN=existing; path=/';
    const fetchImpl = vi.fn<typeof fetch>();
    await expect(ensureCsrfToken(fetchImpl)).resolves.toBe('existing');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('fetches a fresh token when the cookie is missing', async () => {
    const fetchImpl = vi.fn<typeof fetch>(() => {
      document.cookie = 'XSRF-TOKEN=fresh; path=/';
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    await expect(ensureCsrfToken(fetchImpl)).resolves.toBe('fresh');
    expect(fetchImpl).toHaveBeenCalledWith('/api/v1/auth/csrf', expect.objectContaining({ credentials: 'same-origin' }));
  });
});

describe('csrfMiddleware', () => {
  const run = (request: Request) =>
    csrfMiddleware.onRequest?.({
      request,
      schemaPath: '/',
      params: {},
      options: {} as never,
      id: 'test',
    });

  it('adds the token header to unsafe requests', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    const request = (await run(new Request('http://localhost/api/v1/auth/logout', { method: 'POST' }))) as Request;
    expect(request.headers.get(CSRF_HEADER)).toBe('token-123');
  });

  it('leaves safe requests untouched', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    const request = (await run(new Request('http://localhost/api/v1/auth/session'))) as Request;
    expect(request.headers.has(CSRF_HEADER)).toBe(false);
  });
});

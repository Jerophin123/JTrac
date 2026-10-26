import { queryOptions, useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { refreshCsrfToken } from '../../api/csrf';
import { call } from '../../api/call';
import { isNetworkError, toApiError } from '../../api/problem';
import type { components } from '../../api/schema';

export type Session = components['schemas']['Session'];
export type LoginRequest = components['schemas']['LoginRequest'];

export const sessionQueryKey = ['session'] as const;

/** The current session, or null when signed out. Other failures are thrown. */
export async function fetchSession(): Promise<Session | null> {
  const { data, error, response } = await api.GET('/api/v1/auth/session');
  if (data) {
    return data;
  }
  if (response.status === 401) {
    return null;
  }
  throw toApiError(response.status, error);
}

export const sessionQueryOptions = queryOptions({
  queryKey: sessionQueryKey,
  queryFn: fetchSession,
  staleTime: 60_000,
  retry: (failureCount, error) => isNetworkError(error) && failureCount < 2,
});

/**
 * Whether the signed-in user may create organizations (server administrators only). This only
 * decides what to show; the server enforces the rule.
 */
export function useCanCreateOrganizations(): boolean {
  return useIsServerAdmin();
}

/** Whether the signed-in user administers this server (shows server settings; enforced by the server). */
export function useIsServerAdmin(): boolean {
  const { data } = useQuery(sessionQueryOptions);
  return data?.user.serverAdmin === true;
}

/** Signs in; a stale CSRF cookie (rotated in another tab) is refreshed and retried once. */
export function login(credentials: LoginRequest): Promise<Session> {
  return call(() => api.POST('/api/v1/auth/login', { body: credentials }));
}

export async function logout(): Promise<void> {
  await call(() => api.POST('/api/v1/auth/logout'));
  // Sign-out rotates the CSRF token; fetch the new one so the next form works immediately.
  await refreshCsrfToken();
}

export function register(body: { email: string; displayName: string; password: string; acceptTerms: boolean }): Promise<void> {
  return call(() => api.POST('/api/v1/auth/register', { body }));
}

export function requestPasswordReset(email: string): Promise<void> {
  return call(() => api.POST('/api/v1/auth/password-reset', { body: { email } }));
}

export function confirmPasswordReset(token: string, password: string): Promise<void> {
  return call(() => api.POST('/api/v1/auth/password-reset/confirm', { body: { token, password } }));
}

export function verifyEmail(token: string): Promise<void> {
  return call(() => api.POST('/api/v1/auth/verify-email', { body: { token } }));
}

export function resendVerification(email: string): Promise<void> {
  return call(() => api.POST('/api/v1/auth/verify-email/resend', { body: { email } }));
}

export const publicConfigQueryOptions = queryOptions({
  queryKey: ['public-config'],
  queryFn: () => call(() => api.GET('/api/v1/config')),
  staleTime: 5 * 60_000,
});

/**
 * Only same-origin paths are honoured (prevents open redirects). The value is resolved
 * with the URL parser, so tricks such as `/\t/evil.example` (browsers strip tabs and
 * newlines) or backslashes cannot change the origin.
 */
export function safeRedirect(value: unknown, origin: string = window.location.origin): string | undefined {
  if (typeof value !== 'string' || !value.startsWith('/') || value.length > 2048) {
    return undefined;
  }
  let url: URL;
  try {
    url = new URL(value, origin);
  } catch {
    return undefined;
  }
  if (url.origin !== origin || url.pathname === '/login') {
    return undefined;
  }
  return `${url.pathname}${url.search}${url.hash}`;
}

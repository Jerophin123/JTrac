import { ApiError, isNetworkError } from '../api/problem';

/** A human sentence for any failure (API problem, network, or unexpected). */
export function describeError(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (isNetworkError(error)) {
    return 'JTRAC can’t be reached. Check your connection and try again.';
  }
  if (error instanceof ApiError) {
    if (error.is('urn:jtac:problem:version-conflict')) {
      return 'Someone else changed this in the meantime. Reload to see their changes, then try again.';
    }
    if (error.is('urn:jtac:problem:rate-limited')) {
      const seconds = error.problem?.retryAfterSeconds ?? 60;
      return `Too many attempts. Try again in ${String(Math.max(1, Math.ceil(seconds / 60)))} minute(s).`;
    }
    if (error.is('urn:jtac:problem:validation-failed')) {
      const first = error.problem?.errors?.[0];
      return first ? first.message : 'Check the highlighted fields.';
    }
    if (error.status === 404) {
      return 'This item no longer exists, or you don’t have access to it.';
    }
    return error.problem?.detail ?? error.message;
  }
  return fallback;
}

/** Field errors of a validation problem, keyed by field path. */
export function fieldErrors(error: unknown): Record<string, string> {
  const result: Record<string, string> = {};
  if (error instanceof ApiError && error.is('urn:jtac:problem:validation-failed')) {
    for (const item of error.problem?.errors ?? []) {
      result[item.field] ??= item.message;
    }
  }
  return result;
}

export function isForbidden(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403;
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

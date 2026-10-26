import { refreshCsrfToken } from './csrf';
import { toApiError } from './problem';

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/**
 * Unwraps an openapi-fetch result: returns the body, or throws an {@link ApiError} built
 * from the problem document. A request rejected only because the CSRF cookie was stale
 * (rotated by sign-in in another tab) is retried once with a fresh token.
 */
export async function call<T>(request: () => Promise<FetchResult<T>>): Promise<T> {
  let result = await request();
  if (result.response.status === 403 && isCsrfRejection(result.error)) {
    await refreshCsrfToken();
    result = await request();
  }
  if (!result.response.ok) {
    throw toApiError(result.response.status, result.error);
  }
  return result.data as T;
}

function isCsrfRejection(error: unknown): boolean {
  return (
    typeof error === 'object' && error !== null && (error as { type?: unknown }).type === 'urn:jtac:problem:csrf-rejected'
  );
}

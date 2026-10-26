import type { components } from './schema';

export type Problem = components['schemas']['Problem'];
export type ProblemType = Problem['type'];

/** An RFC 9457 problem returned by the JTAC API, or a transport failure. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem | undefined;

  constructor(status: number, problem: Problem | undefined) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }

  is(type: ProblemType): boolean {
    return this.problem?.type === type;
  }
}

export function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { type?: unknown }).type === 'string' &&
    typeof (value as { status?: unknown }).status === 'number'
  );
}

export function toApiError(status: number, body: unknown): ApiError {
  return new ApiError(status, isProblem(body) ? body : undefined);
}

/** True for network failures (server down, offline) as opposed to HTTP errors. */
export function isNetworkError(error: unknown): boolean {
  return error instanceof TypeError;
}

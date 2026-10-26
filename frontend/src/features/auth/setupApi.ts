import { queryOptions } from '@tanstack/react-query';
import { call } from '../../api/call';
import { api } from '../../api/client';

export const setupStatusQueryOptions = queryOptions({
  queryKey: ['setup-status'] as const,
  queryFn: () => call(() => api.GET('/api/v1/public/setup')),
  staleTime: 10_000,
});

export function completeSetup(body: {
  setupCode: string;
  displayName: string;
  email: string;
  password: string;
  organizationName: string;
  organizationSlug?: string | null;
  acceptTerms: boolean;
}) {
  return call(() => api.POST('/api/v1/public/setup', { body }));
}


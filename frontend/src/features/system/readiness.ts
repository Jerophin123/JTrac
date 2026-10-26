import { queryOptions } from '@tanstack/react-query';
import { api } from '../../api/client';

export type Readiness = 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN';

/** Live readiness of the JTRAC backend (includes database connectivity). */
export const readinessQueryOptions = queryOptions({
  queryKey: ['system', 'readiness'],
  queryFn: async (): Promise<Readiness> => {
    const { data, error } = await api.GET('/actuator/health/readiness');
    return data?.status ?? error?.status ?? 'UNKNOWN';
  },
  refetchInterval: 30_000,
  retry: false,
});

import { useQuery } from '@tanstack/react-query';
import { readinessQueryOptions } from '../../features/system/readiness';
import styles from './ServiceStatus.module.css';

type Tone = 'ok' | 'warn' | 'down' | 'pending';

/**
 * Live backend readiness (GET /actuator/health/readiness, refreshed every 30 s).
 * Only one instance per page should `announce` changes to assistive technology.
 */
export function ServiceStatus({ announce = false }: { announce?: boolean }) {
  const { data, isPending, isError } = useQuery(readinessQueryOptions);

  let tone: Tone = 'pending';
  let label = 'Checking service…';
  if (isError) {
    tone = 'down';
    label = 'Service unreachable';
  } else if (!isPending) {
    tone = data === 'UP' ? 'ok' : 'warn';
    label = data === 'UP' ? 'Service online' : 'Service degraded';
  }

  return (
    <p className={styles.status} role={announce ? 'status' : undefined}>
      <span className={`${styles.dot ?? ''} ${styles[tone] ?? ''}`} aria-hidden="true" />
      {label}
    </p>
  );
}

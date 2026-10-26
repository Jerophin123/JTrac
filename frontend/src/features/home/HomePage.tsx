import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { FolderKanban, ShieldCheck } from 'lucide-react';
import { orgsQuery } from '../../api/queries';
import { formatOrgRoleDescription } from '../../lib/roles';
import { ServiceStatus } from '../../app/shell/ServiceStatus';
import { EmptyState } from '../../ui/EmptyState';
import { sessionQueryOptions } from '../auth/session';
import styles from './HomePage.module.css';

export function greetingFor(date: Date): string {
  const hour = date.getHours();
  if (hour < 12) {
    return 'Good morning';
  }
  return hour < 18 ? 'Good afternoon' : 'Good evening';
}

export function HomePage() {
  const { data: session } = useQuery(sessionQueryOptions);
  const { data: orgs } = useQuery(orgsQuery);
  if (!session) {
    return null;
  }
  const now = new Date();
  const firstName = session.user.displayName.split(/\s+/)[0] ?? session.user.displayName;
  const userOrgs = orgs ?? [];

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <p className={styles.eyebrow}>
          {now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}
        </p>
        <h1 className={styles.title}>
          {greetingFor(now)}, {firstName}
        </h1>
        <p className={styles.lede}>Your JTRAC workspace foundation is in place.</p>
      </header>

      <div className={styles.grid}>
        <section className={styles.card} aria-labelledby="account-heading">
          <h2 id="account-heading" className={styles.cardTitle}>
            Your account
          </h2>
          <dl className={styles.details}>
            <div>
              <dt>Name</dt>
              <dd>{session.user.displayName}</dd>
            </div>
            <div>
              <dt>Email</dt>
              <dd className="mono">{session.user.email}</dd>
            </div>
            <div>
              <dt>Session</dt>
              <dd className={styles.secure}>
                <ShieldCheck size={14} aria-hidden="true" />
                Active · HttpOnly cookie, CSRF-protected
              </dd>
            </div>
            {userOrgs.length > 0 && (
              <div>
                <dt>Organization</dt>
                <dd>
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 'var(--space-1)' }}>
                    {userOrgs.map((o) => (
                      <li key={o.id}>
                        <Link to="/$org" params={{ org: o.slug }}>
                          <strong>{o.name}</strong>
                        </Link>{' '}
                        · <span className="muted">{formatOrgRoleDescription(o.role)}</span>
                      </li>
                    ))}
                  </ul>
                </dd>
              </div>
            )}
          </dl>
        </section>

        <section className={styles.card} aria-labelledby="service-heading">
          <h2 id="service-heading" className={styles.cardTitle}>
            Service
          </h2>
          <ServiceStatus />
          <p className={styles.cardNote}>Live readiness of the JTRAC API and its database, checked every 30 seconds.</p>
        </section>
      </div>

      <EmptyState icon={<FolderKanban size={20} />} title="No projects yet">
        Projects, issues and boards arrive in the next milestones. This space shows real work only; nothing here is
        sample data.
      </EmptyState>
    </div>
  );
}

import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { CircleCheck, Inbox } from 'lucide-react';
import { useEffect } from 'react';
import { searchQuery } from '../../api/queries';
import { useOrg, useOrgSlug } from '../../app/params';
import { describeError } from '../../lib/errors';
import { rememberOrg } from '../../lib/lastOrg';
import { Alert } from '../../ui/Alert';
import { EmptyState } from '../../ui/EmptyState';
import { Page, PageHeader, Panel, Skeleton } from '../../ui/Page';
import { sessionQueryOptions } from '../auth/session';
import { IssueTable } from '../issues/IssueTable';
import styles from './MyWorkPage.module.css';

export function greetingFor(date: Date): string {
  const hour = date.getHours();
  if (hour < 12) {
    return 'Good morning';
  }
  return hour < 18 ? 'Good afternoon' : 'Good evening';
}

/** The organization home: what needs the signed-in user's attention. */
export function MyWorkPage() {
  const slug = useOrgSlug();
  const org = useOrg();
  const { data: session } = useQuery(sessionQueryOptions);
  const assigned = useQuery(searchQuery(slug, { assignees: ['me'], resolved: false, sort: 'dueDate', direction: 'asc' }, 0, 25));
  const reported = useQuery({
    ...searchQuery(slug, { reporters: [session?.user.id ?? ''], resolved: false, sort: 'updated', direction: 'desc' }, 0, 10),
    enabled: Boolean(session),
  });

  useEffect(() => {
    rememberOrg(slug);
  }, [slug]);

  const now = new Date();
  const firstName = session?.user.displayName.split(/\s+/)[0] ?? '';
  const overdue = (assigned.data?.items ?? []).filter((i) => i.dueDate && !i.resolvedAt && i.dueDate < now.toISOString().slice(0, 10)).length;

  return (
    <Page>
      <PageHeader
        eyebrow={`${org.name} · ${now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}`}
        title={`${greetingFor(now)}${firstName ? `, ${firstName}` : ''}`}
      />
      <dl className={styles.stats}>
        <div>
          <dt>Assigned to you</dt>
          <dd>{assigned.data?.total ?? '–'}</dd>
        </div>
        <div>
          <dt>Overdue</dt>
          <dd className={overdue > 0 ? 'overdue' : undefined}>{assigned.data ? overdue : '–'}</dd>
        </div>
        <div>
          <dt>Reported by you, open</dt>
          <dd>{reported.data?.total ?? '–'}</dd>
        </div>
      </dl>

      <Panel
        title="Assigned to you"
        actions={
          <Link to="/$org/search" params={{ org: slug }} search={{ assignee: 'me' }}>
            View all
          </Link>
        }
        padded={false}
      >
        {assigned.isPending && <div className={styles.pad}><Skeleton lines={5} /></div>}
        {assigned.isError && <div className={styles.pad}><Alert tone="danger">{describeError(assigned.error)}</Alert></div>}
        {assigned.data && (
          <IssueTable
            org={slug}
            caption="Issues assigned to you"
            issues={assigned.data.items}
            columns={['key', 'title', 'status', 'priority', 'dueDate']}
            framed={false}
            empty={
              <div className={styles.pad}>
                <EmptyState icon={<CircleCheck size={20} />} title="Nothing assigned to you">
                  Issues assigned to you across all projects appear here.
                </EmptyState>
              </div>
            }
          />
        )}
      </Panel>

      <Panel title="Recently updated issues you reported" padded={false}>
        {reported.isPending && <div className={styles.pad}><Skeleton lines={3} /></div>}
        {reported.data && (
          <IssueTable
            org={slug}
            caption="Open issues you reported"
            issues={reported.data.items}
            columns={['key', 'title', 'status', 'assignee', 'updated']}
            framed={false}
            empty={
              <div className={styles.pad}>
                <EmptyState icon={<Inbox size={20} />} title="No open issues reported by you">
                  Press <kbd>C</kbd> anywhere to create an issue.
                </EmptyState>
              </div>
            }
          />
        )}
      </Panel>
    </Page>
  );
}

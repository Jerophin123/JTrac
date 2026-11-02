import { useQuery } from '@tanstack/react-query';
import { Link, type LinkProps } from '@tanstack/react-router';
import {
  AlarmClock,
  AtSign,
  Bell,
  CalendarClock,
  CircleCheck,
  CircleDot,
  GitCommitHorizontal,
  Inbox,
  LayoutDashboard,
  ListTodo,
  MessageSquare,
  UserPlus,
} from 'lucide-react';
import { useEffect, type ReactNode } from 'react';
import { dashboardsQuery, projectsQuery, searchQuery } from '../../api/queries';
import { PRIORITIES, type Bucket, type IssueSummary, type Notification } from '../../api/types';
import { useOrg, useOrgSlug } from '../../app/params';
import { describeError } from '../../lib/errors';
import { timeAgo } from '../../lib/format';
import { rememberOrg } from '../../lib/lastOrg';
import { Alert } from '../../ui/Alert';
import { Badge, PriorityIcon, priorityLabel, StatusBadge } from '../../ui/Badge';
import { EmptyState } from '../../ui/EmptyState';
import { Page, PageHeader, Panel, Skeleton } from '../../ui/Page';
import { sessionQueryOptions } from '../auth/session';
import { IssueTable } from '../issues/IssueTable';
import { greetingFor } from './HomePage';
import {
  counterFilters,
  describeDue,
  groupByDue,
  inboxPreviewQuery,
  MINE_OPEN,
  myBreakdownQuery,
  resolvedSince,
  type DueGroup,
} from './myWork';
import styles from './MyWorkPage.module.css';

const NOTIFICATION_ICONS: Record<Notification['kind'], ReactNode> = {
  ASSIGNED: <UserPlus size={15} aria-hidden="true" />,
  MENTIONED: <AtSign size={15} aria-hidden="true" />,
  COMMENTED: <MessageSquare size={15} aria-hidden="true" />,
  STATUS_CHANGED: <GitCommitHorizontal size={15} aria-hidden="true" />,
  UPDATED: <Bell size={15} aria-hidden="true" />,
};

const GROUP_TITLES: Record<DueGroup, string> = {
  overdue: 'Overdue',
  thisWeek: 'Due this week',
  later: 'Later or no due date',
};

/** The organization home: what needs the signed-in user's attention, at a glance. */
export function MyWorkPage() {
  const slug = useOrgSlug();
  const org = useOrg();
  const { data: session } = useQuery(sessionQueryOptions);
  const now = new Date();
  const counters = counterFilters(now);
  // Local midnight seven days ago: a stable query key all day, and what "the last 7 days" means.
  const weekAgo = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7);

  const assigned = useQuery(searchQuery(slug, { ...MINE_OPEN, sort: 'dueDate', direction: 'asc' }, 0, 25));
  const overdue = useQuery(searchQuery(slug, counters.overdue, 0, 1));
  const dueThisWeek = useQuery(searchQuery(slug, counters.dueThisWeek, 0, 1));
  const inProgress = useQuery(searchQuery(slug, counters.inProgress, 0, 1));
  const recentlyResolved = useQuery(
    searchQuery(slug, { assignees: ['me'], resolved: true, updatedSince: weekAgo.toISOString(), sort: 'updated', direction: 'desc' }, 0, 50),
  );
  const reported = useQuery({
    ...searchQuery(slug, { reporters: [session?.user.id ?? ''], resolved: false, sort: 'updated', direction: 'desc' }, 0, 8),
    enabled: Boolean(session),
  });

  useEffect(() => {
    rememberOrg(slug);
  }, [slug]);

  const firstName = session?.user.displayName.split(/\s+/)[0] ?? '';
  const done = recentlyResolved.data ? resolvedSince(recentlyResolved.data.items, weekAgo) : undefined;
  const mine = { org: slug } as const;

  return (
    <Page>
      <PageHeader
        eyebrow={`${org.name} · ${now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}`}
        title={`${greetingFor(now)}${firstName ? `, ${firstName}` : ''}`}
        description={summary(overdue.data?.total, dueThisWeek.data?.total, assigned.data?.total)}
      />

      <ul className={styles.stats} aria-label="Your work at a glance">
        <StatTile
          label="Assigned to you"
          value={assigned.data?.total}
          icon={<ListTodo size={16} aria-hidden="true" />}
          link={{ to: '/$org/search', params: mine, search: { assignee: 'me', state: 'open' } }}
        />
        <StatTile
          label="Overdue"
          value={overdue.data?.total}
          tone={overdue.data && overdue.data.total > 0 ? 'danger' : undefined}
          icon={<AlarmClock size={16} aria-hidden="true" />}
          link={{ to: '/$org/search', params: mine, search: { assignee: 'me', state: 'open', sort: 'dueDate', dir: 'asc' } }}
        />
        <StatTile
          label="Due this week"
          value={dueThisWeek.data?.total}
          tone={dueThisWeek.data && dueThisWeek.data.total > 0 ? 'warning' : undefined}
          icon={<CalendarClock size={16} aria-hidden="true" />}
          link={{ to: '/$org/search', params: mine, search: { assignee: 'me', state: 'open', sort: 'dueDate', dir: 'asc' } }}
        />
        <StatTile
          label="In progress"
          value={inProgress.data?.total}
          icon={<CircleDot size={16} aria-hidden="true" />}
          link={{ to: '/$org/search', params: mine, search: { assignee: 'me', category: 'IN_PROGRESS' } }}
        />
        <StatTile
          label="Done in the last 7 days"
          value={done?.length}
          tone={done && done.length > 0 ? 'success' : undefined}
          icon={<CircleCheck size={16} aria-hidden="true" />}
          link={{ to: '/$org/search', params: mine, search: { assignee: 'me', state: 'resolved', sort: 'updated', dir: 'desc' } }}
        />
      </ul>

      <div className={styles.columns}>
        <div className={styles.main}>
          <Panel
            title="Up next"
            actions={
              <Link to="/$org/search" params={mine} search={{ assignee: 'me', state: 'open' }}>
                View all{assigned.data && assigned.data.total > assigned.data.items.length ? ` ${String(assigned.data.total)}` : ''}
              </Link>
            }
            padded={false}
          >
            {assigned.isPending && <div className={styles.pad}><Skeleton lines={5} /></div>}
            {assigned.isError && <div className={styles.pad}><Alert tone="danger">{describeError(assigned.error)}</Alert></div>}
            {assigned.data?.items.length === 0 && (
              <div className={styles.pad}>
                <EmptyState icon={<CircleCheck size={20} />} title="Nothing assigned to you">
                  Issues assigned to you across all projects appear here, soonest due first.
                </EmptyState>
              </div>
            )}
            {assigned.data && assigned.data.items.length > 0 && <UpNext org={slug} issues={assigned.data.items} now={now} />}
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
        </div>

        <aside className={styles.side} aria-label="More for you">
          <InboxPreview org={slug} />
          <Breakdowns org={slug} />
          <RecentlyDone org={slug} issues={done} pending={recentlyResolved.isPending} />
          <Dashboards org={slug} />
        </aside>
      </div>
    </Page>
  );
}

/** One sentence under the greeting that says what matters most today. */
function summary(overdue: number | undefined, dueThisWeek: number | undefined, assigned: number | undefined): string | undefined {
  if (overdue === undefined || dueThisWeek === undefined || assigned === undefined) {
    return undefined;
  }
  if (assigned === 0) {
    return 'Nothing is assigned to you right now.';
  }
  if (overdue > 0) {
    return `${String(overdue)} ${overdue === 1 ? 'issue is' : 'issues are'} past due. Start there.`;
  }
  if (dueThisWeek > 0) {
    return `Nothing overdue. ${String(dueThisWeek)} due this week.`;
  }
  return 'Nothing overdue or due this week.';
}

function StatTile({
  label,
  value,
  icon,
  link,
  tone,
}: {
  label: string;
  value: number | undefined;
  icon: ReactNode;
  link: LinkProps;
  tone?: 'danger' | 'warning' | 'success' | undefined;
}) {
  return (
    <li>
      <Link {...link} className={styles.stat} data-tone={tone}>
        <span className={styles.statLabel}>
          <span className={styles.statIcon}>{icon}</span>
          {label}
        </span>
        <span className={styles.statValue}>{value ?? '–'}</span>
      </Link>
    </li>
  );
}

function UpNext({ org, issues, now }: { org: string; issues: IssueSummary[]; now: Date }) {
  const groups = groupByDue(issues, now);
  return (
    <div className={styles.groups}>
      {(['overdue', 'thisWeek', 'later'] as const).map((group) =>
        groups[group].length === 0 ? null : (
          <section key={group} className={styles.group} aria-labelledby={`up-next-${group}`}>
            <h3 id={`up-next-${group}`} className={styles.groupTitle} data-group={group}>
              {GROUP_TITLES[group]}
              <span className={styles.groupCount}>{groups[group].length}</span>
            </h3>
            <ul className={styles.cards}>
              {groups[group].map((issue) => (
                <IssueCard key={issue.id} org={org} issue={issue} now={now} />
              ))}
            </ul>
          </section>
        ),
      )}
    </div>
  );
}

function IssueCard({ org, issue, now }: { org: string; issue: IssueSummary; now: Date }) {
  const due = describeDue(issue.dueDate, now);
  return (
    <li>
      <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.key }} className={styles.card}>
        <PriorityIcon priority={issue.priority} />
        <span className={styles.cardKey}>{issue.key}</span>
        <span className={styles.cardTitle}>{issue.title}</span>
        <span className={styles.cardMeta}>
          {issue.subtaskCount > 0 && (
            <span className={styles.subtasks} title="Subtasks done">
              {issue.subtasksDone}/{issue.subtaskCount}
            </span>
          )}
          <StatusBadge name={issue.status.name} category={issue.status.category} />
          {due && <Badge tone={due.tone}>{due.text}</Badge>}
        </span>
      </Link>
    </li>
  );
}

function InboxPreview({ org }: { org: string }) {
  const inbox = useQuery(inboxPreviewQuery(org));
  return (
    <Panel
      title="Inbox"
      actions={
        <Link to="/$org/inbox" params={{ org }}>
          Open inbox
        </Link>
      }
      padded={false}
    >
      {inbox.isPending && <div className={styles.pad}><Skeleton lines={3} label="Loading notifications" /></div>}
      {inbox.isError && <div className={styles.pad}><Alert tone="danger">{describeError(inbox.error)}</Alert></div>}
      {inbox.data?.items.length === 0 && (
        <p className={styles.quiet}>
          <CircleCheck size={16} aria-hidden="true" /> You’re all caught up.
        </p>
      )}
      {inbox.data && inbox.data.items.length > 0 && (
        <ul className={styles.feed}>
          {inbox.data.items.map((n) => (
            <li key={n.id}>
              {n.issueKey ? (
                <Link to="/$org/issue/$issueKey" params={{ org, issueKey: n.issueKey }} className={styles.feedItem}>
                  <NotificationLine notification={n} />
                </Link>
              ) : (
                <div className={styles.feedItem}>
                  <NotificationLine notification={n} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function NotificationLine({ notification: n }: { notification: Notification }) {
  return (
    <>
      <span className={styles.feedIcon}>{NOTIFICATION_ICONS[n.kind]}</span>
      <span className={styles.feedText}>
        <span className={styles.feedTitle}>{n.title}</span>
        <span className={styles.feedMeta}>
          {n.actorName ? `${n.actorName} · ` : ''}
          {timeAgo(n.createdAt)}
        </span>
      </span>
    </>
  );
}

interface BarRow {
  key: string;
  label: ReactNode;
  name: string;
  count: number;
  link?: LinkProps | undefined;
}

/** Open work by priority (natural order) and by project (largest first), as one-hue bars. */
function Breakdowns({ org }: { org: string }) {
  const byPriority = useQuery(myBreakdownQuery(org, 'priority'));
  const byProject = useQuery(myBreakdownQuery(org, 'project'));
  const projects = useQuery(projectsQuery(org));
  const total = (byPriority.data ?? []).reduce((sum, b) => sum + b.count, 0);

  const priorityRows: BarRow[] = [...(byPriority.data ?? [])]
    .sort((a, b) => priorityOrder(a) - priorityOrder(b))
    .map((bucket) => {
      const known = PRIORITIES.find((p) => p === bucket.key);
      return {
        key: bucket.key ?? bucket.label,
        name: known ? priorityLabel(known) : bucket.label,
        label: known ? <PriorityIcon priority={known} withLabel /> : bucket.label,
        count: bucket.count,
        link: known ? { to: '/$org/search', params: { org }, search: { assignee: 'me', state: 'open', priority: known } } : undefined,
      };
    });
  const projectRows: BarRow[] = [...(byProject.data ?? [])]
    .sort((a, b) => b.count - a.count)
    .slice(0, 6)
    .map((bucket) => {
      const project = projects.data?.find((p) => p.id === bucket.key);
      return {
        key: bucket.key ?? bucket.label,
        name: bucket.label,
        label: (
          <>
            {project && <span className={styles.barKey}>{project.key}</span>}
            <span className={styles.barName}>{bucket.label}</span>
          </>
        ),
        count: bucket.count,
        link: project ? { to: '/$org/search', params: { org }, search: { assignee: 'me', state: 'open', project: project.key } } : undefined,
      };
    });

  return (
    <Panel title="Your open work">
      {(byPriority.isPending || byProject.isPending) && <Skeleton lines={4} label="Loading your open work" />}
      {byPriority.isError && <Alert tone="danger">{describeError(byPriority.error)}</Alert>}
      {byPriority.data && total === 0 && <p className={styles.quietInline}>No open issues assigned to you.</p>}
      {byPriority.data && total > 0 && (
        <div className={styles.breakdowns}>
          <BarList title="By priority" rows={priorityRows} />
          {byProject.data && <BarList title="By project" rows={projectRows} />}
        </div>
      )}
    </Panel>
  );
}

function priorityOrder(bucket: Bucket): number {
  const index = PRIORITIES.findIndex((p) => p === bucket.key);
  return index < 0 ? PRIORITIES.length : index;
}

/** Ranked bars on one baseline; each row names its count and links to the matching issues. */
function BarList({ title, rows }: { title: string; rows: BarRow[] }) {
  const max = Math.max(1, ...rows.map((r) => r.count));
  return (
    <section className={styles.barList} aria-label={title}>
      <h3 className={styles.barTitle}>{title}</h3>
      <ul>
        {rows.map((row) => {
          const content = (
            <>
              <span className={styles.barLabel}>{row.label}</span>
              <span className={styles.barTrack} aria-hidden="true">
                {/* Width is data, set through the CSSOM (allowed by the CSP), never an inline style attribute in HTML. */}
                <span className={styles.bar} ref={(el) => el?.style.setProperty('--share', String(row.count / max))} />
              </span>
              <span className={styles.barValue}>{row.count}</span>
            </>
          );
          const name = `${row.name}: ${String(row.count)} open`;
          return (
            <li key={row.key}>
              {row.link ? (
                <Link {...row.link} className={styles.barRow} aria-label={name} title={name}>
                  {content}
                </Link>
              ) : (
                <div className={styles.barRow} aria-label={name} title={name}>
                  {content}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function RecentlyDone({ org, issues, pending }: { org: string; issues: IssueSummary[] | undefined; pending: boolean }) {
  return (
    <Panel title="Done in the last 7 days" padded={false}>
      {pending && <div className={styles.pad}><Skeleton lines={3} /></div>}
      {issues?.length === 0 && <p className={styles.quiet}>Issues you resolve show up here for a week.</p>}
      {issues && issues.length > 0 && (
        <ul className={styles.feed}>
          {issues.slice(0, 6).map((issue) => (
            <li key={issue.id}>
              <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.key }} className={styles.feedItem}>
                <span className={styles.feedIcon} data-tone="success">
                  <CircleCheck size={15} aria-hidden="true" />
                </span>
                <span className={styles.feedText}>
                  <span className={styles.feedTitle}>
                    <span className={styles.cardKey}>{issue.key}</span> {issue.title}
                  </span>
                  <span className={styles.feedMeta}>Resolved {timeAgo(issue.resolvedAt)}</span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function Dashboards({ org }: { org: string }) {
  const dashboards = useQuery(dashboardsQuery(org));
  if (!dashboards.data || dashboards.data.length === 0) {
    return null;
  }
  return (
    <Panel
      title="Dashboards"
      actions={
        <Link to="/$org/dashboards" params={{ org }}>
          All
        </Link>
      }
      padded={false}
    >
      <ul className={styles.feed}>
        {[...dashboards.data]
          .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
          .slice(0, 4)
          .map((d) => (
            <li key={d.id}>
              <Link to="/$org/dashboards/$id" params={{ org, id: d.id }} className={styles.feedItem}>
                <span className={styles.feedIcon}>
                  <LayoutDashboard size={15} aria-hidden="true" />
                </span>
                <span className={styles.feedText}>
                  <span className={styles.feedTitle}>{d.name}</span>
                  <span className={styles.feedMeta}>
                    {d.shared ? `Shared by ${d.ownerName}` : 'Private'} · updated {timeAgo(d.updatedAt)}
                  </span>
                </span>
              </Link>
            </li>
          ))}
      </ul>
    </Panel>
  );
}


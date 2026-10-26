import { useQuery } from '@tanstack/react-query';
import { activityQuery } from '../../api/queries';
import type { ActivityEntry } from '../../api/types';
import { describeError } from '../../lib/errors';
import { formatDateTime, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Skeleton } from '../../ui/Page';
import styles from './IssueSections.module.css';

const FIELD_NAMES: Record<string, string> = {
  title: 'title',
  description: 'description',
  status: 'status',
  assignee: 'assignee',
  priority: 'priority',
  labels: 'labels',
  dueDate: 'due date',
  startDate: 'start date',
  estimate: 'estimate',
  milestone: 'milestone',
  parent: 'parent',
  type: 'type',
  links: 'links',
};

function show(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return 'none';
  }
  if (Array.isArray(value)) {
    return value.length ? value.map(show).join(', ') : 'none';
  }
  if (typeof value === 'object') {
    const named = value as { name?: unknown; key?: unknown; displayName?: unknown; fileName?: unknown };
    if (typeof named.fileName === 'string') {
      return named.fileName;
    }
    if (typeof named.displayName === 'string') {
      return named.displayName;
    }
    if (typeof named.name === 'string') {
      return named.name;
    }
    if (typeof named.key === 'string') {
      return named.key;
    }
    return JSON.stringify(value);
  }
  const text = typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : JSON.stringify(value);
  return text.length > 80 ? `${text.slice(0, 79)}…` : text;
}

export function describeActivity(entry: ActivityEntry): string {
  const field = entry.field ? (FIELD_NAMES[entry.field] ?? entry.field.replace(/^cf:/, '')) : '';
  switch (entry.kind) {
    case 'CREATED':
      return 'created the issue';
    case 'COMMENTED':
      return 'commented';
    case 'COMMENT_DELETED':
      return 'deleted a comment';
    case 'ATTACHMENT_ADDED':
      return `attached ${show(entry.newValue)}`;
    case 'ATTACHMENT_REMOVED':
      return `removed attachment ${show(entry.newValue ?? entry.oldValue)}`;
    case 'LINK_ADDED': {
      const link = entry.newValue as { type?: string; key?: string } | null;
      return link?.key ? `linked ${link.key}${link.type ? ` (${link.type.toLowerCase()})` : ''}` : 'added a link';
    }
    case 'LINK_REMOVED':
      return 'removed a link';
    default:
      if (field === 'description') {
        return 'updated the description';
      }
      return `changed ${field} from ${show(entry.oldValue)} to ${show(entry.newValue)}`;
  }
}

export function IssueActivity({ org, issueKey }: { org: string; issueKey: string }) {
  const activity = useQuery(activityQuery(org, issueKey));
  return (
    <section aria-labelledby="history-heading" className={styles.section}>
      <div className={styles.header}>
        <h2 id="history-heading" className={styles.title}>
          History
        </h2>
      </div>
      {activity.isPending && <Skeleton lines={3} label="Loading history" />}
      {activity.isError && <Alert tone="danger">{describeError(activity.error)}</Alert>}
      {activity.data && (
        <ol className={styles.activity}>
          {activity.data.map((entry) => (
            <li key={entry.id}>
              <strong>{entry.actor?.displayName ?? 'Someone'}</strong>
              <span>{describeActivity(entry)}</span>
              <time dateTime={entry.at} title={formatDateTime(entry.at)}>
                · {timeAgo(entry.at)}
              </time>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

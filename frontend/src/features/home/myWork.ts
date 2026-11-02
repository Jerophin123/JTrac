import { queryOptions } from '@tanstack/react-query';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { Bucket, IssueFilter, IssueSummary } from '../../api/types';
import { todayIso } from '../../lib/format';

/** A date `days` from today, as yyyy-MM-dd in the user's time zone. */
export function isoDaysFrom(days: number, now: Date = new Date()): string {
  const date = new Date(now);
  date.setDate(date.getDate() + days);
  return todayIso(date);
}

/** Open issues assigned to the signed-in user. */
export const MINE_OPEN: IssueFilter = { assignees: ['me'], resolved: false };

/** The filters behind the My work counters (each counted by the server, not from one page). */
export function counterFilters(now: Date = new Date()): Record<'overdue' | 'dueThisWeek' | 'inProgress', IssueFilter> {
  return {
    overdue: { ...MINE_OPEN, dueTo: isoDaysFrom(-1, now) },
    dueThisWeek: { ...MINE_OPEN, dueFrom: isoDaysFrom(0, now), dueTo: isoDaysFrom(6, now) },
    inProgress: { assignees: ['me'], categories: ['IN_PROGRESS'] },
  };
}

/**
 * Open work grouped by priority or project. Rooted under the issue keys, so realtime issue
 * events refresh it like the lists.
 */
export const myBreakdownQuery = (org: string, groupBy: 'priority' | 'project') =>
  queryOptions({
    queryKey: [...keys.issuesRoot(org), 'my-work', groupBy] as const,
    queryFn: (): Promise<Bucket[]> =>
      call(() => api.POST('/api/v1/orgs/{org}/issues/aggregate', { params: { path: { org } }, body: { groupBy, filter: MINE_OPEN } })),
    staleTime: 30_000,
  });

/** The latest unread notifications; under the notifications key, so the inbox's changes refresh it. */
export const inboxPreviewQuery = (org: string) =>
  queryOptions({
    queryKey: [...keys.notifications(org, true), 'preview'] as const,
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/notifications', { params: { path: { org }, query: { unread: true, page: 0, size: 5 } } })),
    staleTime: 30_000,
  });

export type DueGroup = 'overdue' | 'thisWeek' | 'later';

/** Splits assigned issues into overdue, due within a week, and later (or no due date). */
export function groupByDue(issues: IssueSummary[], now: Date = new Date()): Record<DueGroup, IssueSummary[]> {
  const today = todayIso(now);
  const weekEnd = isoDaysFrom(6, now);
  const groups: Record<DueGroup, IssueSummary[]> = { overdue: [], thisWeek: [], later: [] };
  for (const issue of issues) {
    if (issue.dueDate && issue.dueDate < today) {
      groups.overdue.push(issue);
    } else if (issue.dueDate && issue.dueDate <= weekEnd) {
      groups.thisWeek.push(issue);
    } else {
      groups.later.push(issue);
    }
  }
  return groups;
}

/** How a due date reads on a card: "Overdue by 3 days", "Due today", "Due Friday", "Due 12 Nov". */
export function describeDue(due: string | null | undefined, now: Date = new Date()): { text: string; tone: 'danger' | 'warning' | 'neutral' } | null {
  if (!due) {
    return null;
  }
  const today = todayIso(now);
  const days = Math.round((Date.parse(`${due}T00:00:00`) - Date.parse(`${today}T00:00:00`)) / 86_400_000);
  if (days < 0) {
    return { text: `Overdue by ${String(-days)} ${-days === 1 ? 'day' : 'days'}`, tone: 'danger' };
  }
  if (days === 0) {
    return { text: 'Due today', tone: 'warning' };
  }
  if (days === 1) {
    return { text: 'Due tomorrow', tone: 'warning' };
  }
  const date = new Date(`${due}T00:00:00`);
  if (days < 7) {
    return { text: `Due ${date.toLocaleDateString(undefined, { weekday: 'long' })}`, tone: 'neutral' };
  }
  return { text: `Due ${date.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}`, tone: 'neutral' };
}

/** Issues resolved since `since` (the search can only filter by last update, so resolvedAt decides). */
export function resolvedSince(issues: IssueSummary[], since: Date): IssueSummary[] {
  return issues.filter((issue) => issue.resolvedAt && Date.parse(issue.resolvedAt) >= since.getTime());
}

import { describe, expect, it } from 'vitest';
import type { IssueSummary } from '../../api/types';
import { counterFilters, describeDue, groupByDue, isoDaysFrom, resolvedSince } from './myWork';

// Wednesday 4 November 2026, mid-morning local time.
const NOW = new Date(2026, 10, 4, 10, 0);

function issue(key: string, dueDate: string | null, resolvedAt: string | null = null): IssueSummary {
  return {
    id: key,
    key,
    projectKey: 'WEB',
    projectId: 'p',
    title: key,
    type: { id: 't', name: 'Task' },
    status: { id: 's', name: 'Open', category: 'TODO' },
    rank: 0,
    customValues: {},
    subtaskCount: 0,
    subtasksDone: 0,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-11-01T00:00:00Z',
    dueDate,
    resolvedAt,
    hiddenFields: [],
  };
}

describe('my work', () => {
  it('counts overdue as due before today and this week as today plus six days', () => {
    expect(isoDaysFrom(0, NOW)).toBe('2026-11-04');
    expect(counterFilters(NOW).overdue).toMatchObject({ assignees: ['me'], resolved: false, dueTo: '2026-11-03' });
    expect(counterFilters(NOW).dueThisWeek).toMatchObject({ dueFrom: '2026-11-04', dueTo: '2026-11-10' });
  });

  it('groups assigned issues by when they are due', () => {
    const groups = groupByDue([issue('A', '2026-11-01'), issue('B', '2026-11-04'), issue('C', '2026-11-10'), issue('D', '2026-11-11'), issue('E', null)], NOW);
    expect(groups.overdue.map((i) => i.key)).toEqual(['A']);
    expect(groups.thisWeek.map((i) => i.key)).toEqual(['B', 'C']);
    expect(groups.later.map((i) => i.key)).toEqual(['D', 'E']);
  });

  it('describes due dates the way people say them', () => {
    expect(describeDue('2026-11-01', NOW)).toEqual({ text: 'Overdue by 3 days', tone: 'danger' });
    expect(describeDue('2026-11-03', NOW)).toEqual({ text: 'Overdue by 1 day', tone: 'danger' });
    expect(describeDue('2026-11-04', NOW)).toEqual({ text: 'Due today', tone: 'warning' });
    expect(describeDue('2026-11-05', NOW)).toEqual({ text: 'Due tomorrow', tone: 'warning' });
    expect(describeDue('2026-11-07', NOW)?.tone).toBe('neutral');
    expect(describeDue(null, NOW)).toBeNull();
  });

  it('counts only issues actually resolved within the window', () => {
    const since = new Date(2026, 9, 28);
    const issues = [issue('A', null, '2026-11-02T09:00:00Z'), issue('B', null, '2026-10-01T09:00:00Z'), issue('C', null, null)];
    expect(resolvedSince(issues, since).map((i) => i.key)).toEqual(['A']);
  });
});

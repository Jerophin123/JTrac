import { describe, expect, it } from 'vitest';
import { fromIssueFilter, parseFilterState, toIssueFilter } from './filters';

describe('issue filters', () => {
  it('parses only valid URL values', () => {
    const state = parseFilterState({ q: 'login', category: 'DONE', priority: 'BOGUS', dir: 'sideways', page: '2', state: 'resolved' });
    expect(state).toMatchObject({ q: 'login', category: 'DONE', priority: undefined, dir: undefined, page: 2, state: 'resolved' });
    expect(parseFilterState({ q: 'x'.repeat(500) }).q).toBeUndefined();
  });

  it('defaults to open issues and maps every dimension', () => {
    expect(toIssueFilter({})).toEqual({ resolved: false });
    expect(toIssueFilter({ state: 'all' })).toEqual({});
    expect(
      toIssueFilter({ q: 'crash', category: 'IN_PROGRESS', priority: 'HIGH', assignee: 'me', sort: 'dueDate', dir: 'desc' }, 'WEB'),
    ).toEqual({
      projectKeys: ['WEB'],
      text: 'crash',
      categories: ['IN_PROGRESS'],
      priorities: ['HIGH'],
      assignees: ['me'],
      resolved: false,
      sort: 'dueDate',
      direction: 'desc',
    });
  });

  it('round-trips through saved views', () => {
    const state = { q: 'a', project: 'WEB', assignee: 'none', state: 'resolved' as const, sort: 'priority', dir: 'asc' as const };
    expect(fromIssueFilter(toIssueFilter(state))).toMatchObject(state);
  });
});

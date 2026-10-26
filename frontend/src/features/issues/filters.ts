import type { IssueFilter, StatusCategory } from '../../api/types';
import { CATEGORIES, PRIORITIES } from '../../api/types';

/** Issue list state as it appears in the URL (shareable, bookmarkable). */
export interface FilterState {
  q?: string | undefined;
  project?: string | undefined;
  category?: StatusCategory | undefined;
  priority?: string | undefined;
  assignee?: string | undefined;
  label?: string | undefined;
  milestone?: string | undefined;
  type?: string | undefined;
  state?: 'open' | 'resolved' | 'all' | undefined;
  sort?: string | undefined;
  dir?: 'asc' | 'desc' | undefined;
  page?: number | undefined;
  view?: string | undefined;
}

const str = (value: unknown): string | undefined => (typeof value === 'string' && value.length > 0 && value.length <= 200 ? value : undefined);

export function parseFilterState(search: Record<string, unknown>): FilterState {
  const category = str(search.category);
  const state = str(search.state);
  const dir = str(search.dir);
  const page = Number(search.page);
  return {
    q: str(search.q),
    project: str(search.project),
    category: CATEGORIES.includes(category as StatusCategory) ? (category as StatusCategory) : undefined,
    priority: PRIORITIES.includes(str(search.priority) as (typeof PRIORITIES)[number]) ? str(search.priority) : undefined,
    assignee: str(search.assignee),
    label: str(search.label),
    milestone: str(search.milestone),
    type: str(search.type),
    state: state === 'open' || state === 'resolved' || state === 'all' ? state : undefined,
    sort: str(search.sort),
    dir: dir === 'asc' || dir === 'desc' ? dir : undefined,
    page: Number.isInteger(page) && page > 0 ? page : undefined,
    view: str(search.view),
  };
}

export function toIssueFilter(state: FilterState, fixedProject?: string): IssueFilter {
  const project = fixedProject ?? state.project;
  const filter: IssueFilter = {};
  if (project) {
    filter.projectKeys = [project];
  }
  if (state.q) {
    filter.text = state.q;
  }
  if (state.category) {
    filter.categories = [state.category];
  }
  if (state.priority) {
    filter.priorities = [state.priority as (typeof PRIORITIES)[number]];
  }
  if (state.assignee) {
    filter.assignees = [state.assignee];
  }
  if (state.label) {
    filter.labelIds = [state.label];
  }
  if (state.milestone) {
    filter.milestones = [state.milestone];
  }
  if (state.type) {
    filter.typeIds = [state.type];
  }
  const resolved = state.state ?? 'open';
  if (resolved !== 'all') {
    filter.resolved = resolved === 'resolved';
  }
  if (state.sort) {
    filter.sort = state.sort as NonNullable<IssueFilter['sort']>;
    filter.direction = state.dir ?? 'asc';
  }
  return filter;
}

/** The inverse of {@link toIssueFilter}, for opening saved views. */
export function fromIssueFilter(filter: IssueFilter): FilterState {
  return {
    q: filter.text,
    project: filter.projectKeys?.[0],
    category: filter.categories?.[0],
    priority: filter.priorities?.[0],
    assignee: filter.assignees?.[0],
    label: filter.labelIds?.[0],
    milestone: filter.milestones?.[0],
    type: filter.typeIds?.[0],
    state: filter.resolved === undefined ? 'all' : filter.resolved ? 'resolved' : 'open',
    sort: filter.sort,
    dir: filter.direction,
  };
}

export function activeFilterCount(state: FilterState): number {
  return [state.q, state.category, state.priority, state.assignee, state.label, state.milestone, state.type].filter(Boolean).length;
}

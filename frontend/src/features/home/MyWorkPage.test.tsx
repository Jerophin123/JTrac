import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dashboardsQuery, orgQuery, projectsQuery, searchQuery } from '../../api/queries';
import type { IssueSummary } from '../../api/types';
import { sessionQueryKey } from '../auth/session';
import { counterFilters, inboxPreviewQuery, MINE_OPEN, myBreakdownQuery } from './myWork';
import { MyWorkPage } from './MyWorkPage';

const NOW = new Date(2026, 10, 4, 10, 0);
const USER = '00000000-0000-7000-8000-000000000001';

function issue(key: string, title: string, dueDate: string | null, extra: Partial<IssueSummary> = {}): IssueSummary {
  return {
    id: key,
    key,
    projectKey: 'WEB',
    projectId: 'p-web',
    title,
    type: { id: 't', name: 'Task' },
    status: { id: 's', name: 'In progress', category: 'IN_PROGRESS' },
    priority: 'HIGH',
    rank: 0,
    customValues: {},
    subtaskCount: 0,
    subtasksDone: 0,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-11-03T00:00:00Z',
    dueDate,
    hiddenFields: [],
    ...extra,
  };
}

const page = (items: IssueSummary[], total = items.length) => ({ items, page: 0, size: 25, total });

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const org = 'acme';
  const counters = counterFilters(NOW);
  const weekAgo = new Date(2026, 9, 28);
  qc.setQueryData(sessionQueryKey, { user: { id: USER, email: 'ada@example.com', displayName: 'Ada Lovelace', handle: 'ada' }, consentRequired: false });
  qc.setQueryData(orgQuery(org).queryKey, {
    id: '00000000-0000-7000-8000-0000000000aa',
    slug: org,
    name: 'Acme',
    settings: { allowedEmailDomains: [], defaultProjectVisibility: 'ORGANIZATION', membersCanCreateProjects: true },
    role: 'MEMBER',
    memberCount: 4,
    version: 1,
  });
  qc.setQueryData(searchQuery(org, { ...MINE_OPEN, sort: 'dueDate', direction: 'asc' }, 0, 25).queryKey, page([
    issue('WEB-1', 'Fix the login timeout', '2026-11-01'),
    issue('WEB-2', 'Ship the footer links', '2026-11-04', { subtaskCount: 3, subtasksDone: 1 }),
    issue('WEB-3', 'Write release notes', null, { priority: 'LOW' }),
  ], 3));
  qc.setQueryData(searchQuery(org, counters.overdue, 0, 1).queryKey, page([], 1));
  qc.setQueryData(searchQuery(org, counters.dueThisWeek, 0, 1).queryKey, page([], 1));
  qc.setQueryData(searchQuery(org, counters.inProgress, 0, 1).queryKey, page([], 2));
  qc.setQueryData(
    searchQuery(org, { assignees: ['me'], resolved: true, updatedSince: weekAgo.toISOString(), sort: 'updated', direction: 'desc' }, 0, 50).queryKey,
    page([
      issue('WEB-9', 'Old resolved, edited lately', null, { resolvedAt: '2026-09-01T00:00:00Z' }),
      issue('API-4', 'Rotate the signing keys', null, { resolvedAt: '2026-11-03T08:00:00Z' }),
    ]),
  );
  qc.setQueryData(searchQuery(org, { reporters: [USER], resolved: false, sort: 'updated', direction: 'desc' }, 0, 8).queryKey, page([]));
  qc.setQueryData(inboxPreviewQuery(org).queryKey, {
    items: [{ id: 'n1', kind: 'MENTIONED', title: 'You were mentioned on WEB-1', issueKey: 'WEB-1', actorName: 'Grace', createdAt: '2026-11-04T09:30:00Z', read: false }],
    page: 0,
    size: 5,
    total: 1,
  });
  qc.setQueryData(myBreakdownQuery(org, 'priority').queryKey, [
    { key: 'LOW', label: 'LOW', count: 1, estimate: 0 },
    { key: 'HIGH', label: 'HIGH', count: 2, estimate: 0 },
  ]);
  qc.setQueryData(myBreakdownQuery(org, 'project').queryKey, [{ key: 'p-web', label: 'Website', count: 3, estimate: 0 }]);
  qc.setQueryData(projectsQuery(org).queryKey, [{ id: 'p-web', key: 'WEB', name: 'Website', visibility: 'ORGANIZATION', archived: false, myPermissions: ['BROWSE'] }]);
  qc.setQueryData(dashboardsQuery(org).queryKey, []);

  const root = createRootRoute({ component: Outlet });
  const orgRoute = createRoute({ getParentRoute: () => root, path: '/$org', component: MyWorkPage });
  const router = createRouter({ routeTree: root.addChildren([orgRoute]), history: createMemoryHistory({ initialEntries: ['/acme'] }) });
  render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('MyWorkPage', () => {
  it('greets the user and leads with what matters most', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: 'Good morning, Ada' })).toBeInTheDocument();
    expect(screen.getByText('1 issue is past due. Start there.')).toBeInTheDocument();
  });

  it('shows server-counted tiles that open the matching issues', async () => {
    renderPage();
    const tiles = await screen.findByRole('list', { name: 'Your work at a glance' });
    expect(within(tiles).getByRole('link', { name: /Assigned to you\s*3/ })).toHaveAttribute('href', '/acme/search?assignee=me&state=open');
    expect(within(tiles).getByRole('link', { name: /In progress\s*2/ })).toHaveAttribute('href', expect.stringContaining('category=IN_PROGRESS'));
    // Only the issue resolved within the week counts, not one merely edited lately.
    expect(within(tiles).getByRole('link', { name: /Done in the last 7 days\s*1/ })).toBeInTheDocument();
  });

  it('groups up-next work by due date with readable due chips', async () => {
    renderPage();
    const overdue = await screen.findByRole('region', { name: /Overdue/ });
    expect(within(overdue).getByRole('link', { name: /WEB-1.*Fix the login timeout.*Overdue by 3 days/ })).toHaveAttribute('href', '/acme/issue/WEB-1');
    const week = screen.getByRole('region', { name: /Due this week/ });
    expect(within(week).getByText('Due today')).toBeInTheDocument();
    expect(within(week).getByTitle('Subtasks done')).toHaveTextContent('1/3');
    expect(screen.getByRole('region', { name: /Later or no due date/ })).toHaveTextContent('Write release notes');
  });

  it('previews the inbox and the open-work breakdowns', async () => {
    renderPage();
    expect(await screen.findByRole('link', { name: /You were mentioned on WEB-1.*Grace/ })).toHaveAttribute('href', '/acme/issue/WEB-1');
    const byPriority = screen.getByRole('region', { name: 'By priority' });
    const rows = within(byPriority).getAllByRole('link');
    // Natural priority order, not by count; each row names its count and filters the list.
    expect(rows.map((r) => r.getAttribute('aria-label'))).toEqual(['High: 2 open', 'Low: 1 open']);
    expect(rows[0]).toHaveAttribute('href', expect.stringContaining('priority=HIGH'));
    expect(within(screen.getByRole('region', { name: 'By project' })).getByRole('link', { name: 'Website: 3 open' }))
      .toHaveAttribute('href', expect.stringContaining('project=WEB'));
    expect(screen.getByRole('link', { name: /API-4.*Rotate the signing keys/ })).toBeInTheDocument();
  });
});

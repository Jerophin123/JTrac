import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { keys, unreadQuery } from '../../api/queries';
import type { Session } from '../../features/auth/session';
import { readSidebarCollapsed, saveSidebarCollapsed } from '../../lib/sidebar';
import { Sidebar } from './Sidebar';

const session: Session = {
  user: { id: '00000000-0000-7000-8000-000000000001', email: 'ada@example.com', displayName: 'Ada Lovelace', handle: 'ada' },
  consentRequired: false,
};

const onOpenSearch = vi.fn();

/** Holds the collapsed state the way AppShell does, persisting each change. */
function Harness({ initiallyCollapsed }: { initiallyCollapsed: boolean }) {
  const [collapsed, setCollapsed] = useState(initiallyCollapsed);
  return (
    <Sidebar
      id="nav"
      session={session}
      open={false}
      collapsed={collapsed}
      onToggleCollapsed={() => {
        saveSidebarCollapsed(!collapsed);
        setCollapsed(!collapsed);
      }}
      onClose={() => undefined}
      onSignedOut={() => Promise.resolve()}
      onOpenSearch={onOpenSearch}
    />
  );
}

function renderSidebar({ collapsed = false } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(unreadQuery('acme').queryKey, { count: 3 });
  queryClient.setQueryData(keys.globalSearch('acme', 'web'), {
    query: 'web',
    issues: [{ key: 'WEB-1', title: 'Fix the login page', status: 'Open', statusCategory: 'TODO', projectKey: 'WEB' }],
    projects: [{ key: 'WEB', name: 'Website', archived: false }],
    people: [],
  });
  const rootRoute = createRootRoute({ component: Outlet });
  const orgRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/$org',
    component: () => <Harness initiallyCollapsed={collapsed} />,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([orgRoute]),
    history: createMemoryHistory({ initialEntries: ['/acme'] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

beforeEach(() => {
  window.localStorage.clear();
  onOpenSearch.mockReset();
});

describe('Sidebar collapse', () => {
  it('collapses to the icon rail and back, remembering the choice', async () => {
    renderSidebar();
    const sidebar = await screen.findByRole('complementary', { name: 'Sidebar' });
    const toggle = screen.getByRole('button', { name: 'Collapse sidebar' });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(sidebar).not.toHaveAttribute('data-collapsed');

    await userEvent.click(toggle);

    expect(sidebar).toHaveAttribute('data-collapsed');
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute('aria-expanded', 'false');
    expect(readSidebarCollapsed()).toBe(true);

    await userEvent.click(screen.getByRole('button', { name: 'Expand sidebar' }));

    expect(sidebar).not.toHaveAttribute('data-collapsed');
    expect(readSidebarCollapsed()).toBe(false);
  });

  it('keeps every destination named and adds tooltips in the rail', async () => {
    renderSidebar({ collapsed: true });
    const inbox = await screen.findByRole('link', { name: /Inbox/ });
    expect(inbox).toHaveAccessibleName('Inbox 3 unread');
    expect(inbox).toHaveAttribute('title', 'Inbox (3 unread)');
    expect(screen.getByRole('link', { name: 'My work' })).toHaveAttribute('title', 'My work');
    expect(screen.getByRole('button', { name: /Search/ })).toHaveAttribute('title', 'Search (Ctrl K)');
  });

  it('shows no tooltips while expanded (the labels are visible)', async () => {
    renderSidebar();
    expect(await screen.findByRole('link', { name: 'My work' })).not.toHaveAttribute('title');
  });

  it('shows results right under the sidebar search while typing', async () => {
    const router = renderSidebar();
    const field = await screen.findByRole('combobox', { name: 'Search issues, projects and people' });
    await userEvent.type(field, 'web');

    const results = await screen.findByRole('listbox', { name: 'Search results' });
    expect(field).toHaveValue('web');
    expect(field).toHaveAttribute('aria-expanded', 'true');
    expect(await within(results).findByRole('option', { name: /WEB-1 Fix the login page/ })).toBeInTheDocument();
    expect(within(results).getByRole('option', { name: /Website/ })).toBeInTheDocument();
    expect(within(results).getByRole('option', { name: 'See all issues matching “web”' })).toBeInTheDocument();
    expect(onOpenSearch).not.toHaveBeenCalled();

    await userEvent.keyboard('{ArrowDown}');
    expect(within(results).getByRole('option', { name: /WEB-1/ })).toHaveAttribute('aria-selected', 'true');
    await userEvent.keyboard('{Enter}');

    expect(router.state.location.pathname).toBe('/acme/issue/WEB-1');
    expect(field).toHaveValue('');
  });

  it('closes the results with Escape and clears with a second Escape', async () => {
    renderSidebar();
    const field = await screen.findByRole('combobox', { name: 'Search issues, projects and people' });
    await userEvent.type(field, 'web');
    await screen.findByRole('listbox', { name: 'Search results' });

    await userEvent.keyboard('{Escape}');
    expect(field).toHaveAttribute('aria-expanded', 'false');
    expect(field).toHaveValue('web');
    await userEvent.keyboard('{Escape}');
    expect(field).toHaveValue('');
  });

  it('keeps the profile menu short: settings live in Your account', async () => {
    renderSidebar();
    await userEvent.click(await screen.findByRole('button', { name: /Ada Lovelace/ }));
    const items = screen.getAllByRole('menuitem').map((item) => item.textContent);
    expect(items).toEqual(['Profile & settings', 'Sign out']);
  });

  it('starts expanded when nothing is stored', () => {
    expect(readSidebarCollapsed()).toBe(false);
  });
});

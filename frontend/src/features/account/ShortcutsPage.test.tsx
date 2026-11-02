import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { readShortcutSettings, resetShortcutStoreForTests } from '../../lib/shortcuts';
import { ToastProvider } from '../../ui/Toast';
import { sessionQueryKey } from '../auth/session';
import { ShortcutsPage } from './ShortcutsPage';

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(sessionQueryKey, {
    user: { id: '00000000-0000-7000-8000-000000000001', email: 'ada@example.com', displayName: 'Ada Lovelace', handle: 'ada' },
    consentRequired: false,
  });
  const rootRoute = createRootRoute({ component: Outlet });
  const page = createRoute({ getParentRoute: () => rootRoute, path: '/account/shortcuts', component: ShortcutsPage });
  const router = createRouter({
    routeTree: rootRoute.addChildren([page]),
    history: createMemoryHistory({ initialEntries: ['/account/shortcuts'] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

function row(label: string): HTMLElement {
  const item = screen.getByText(label).closest('li');
  if (!item) {
    throw new Error(`No row for ${label}`);
  }
  return item;
}

beforeEach(() => {
  window.localStorage.clear();
  resetShortcutStoreForTests();
});

describe('ShortcutsPage', () => {
  it('records new keys and saves them', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Change shortcut for Create an issue' }));
    const recorder = screen.getByRole('button', { name: 'New keys for Create an issue' });
    expect(recorder).toHaveFocus();

    await userEvent.keyboard('{Shift>}N{/Shift}');
    expect(within(recorder).getByText('Shift N')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(readShortcutSettings().bindings).toEqual({ createIssue: 'shift+n' });
    expect(within(row('Create an issue')).getByText('Shift N')).toBeInTheDocument();
    expect(within(row('Create an issue')).getByRole('button', { name: 'Reset shortcut for Create an issue' })).toBeInTheDocument();
  });

  it('warns before taking keys from another shortcut, then hands them over', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Change shortcut for Go to Inbox' }));
    await userEvent.keyboard('c');

    expect(screen.getByText(/Already used by “Create an issue”/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Use these keys' }));

    expect(within(row('Create an issue')).getByText('Off')).toBeInTheDocument();
    expect(readShortcutSettings().bindings).toEqual({ goInbox: 'c', createIssue: null });
  });

  it('records a two-key sequence', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Change shortcut for Go to Projects' }));
    await userEvent.keyboard('gx');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(readShortcutSettings().bindings).toEqual({ goProjects: 'g x' });
  });

  it('refuses browser keys and cancels with Escape', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Change shortcut for Create an issue' }));
    await userEvent.keyboard('{Control>}c{/Control}');
    expect(screen.getByText(/belongs to the browser or text editing/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('button', { name: 'New keys for Create an issue' })).not.toBeInTheDocument();
    expect(readShortcutSettings().bindings).toEqual({});
  });

  it('turns single-key shortcuts off and resets everything', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('switch', { name: 'Single-key shortcuts' }));
    expect(readShortcutSettings().singleKeyEnabled).toBe(false);
    expect(within(row('Create an issue')).getByText('(single-key shortcuts are off)')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Change shortcut for Create an issue' }));
    await userEvent.click(screen.getByRole('button', { name: 'Remove shortcut' }));
    await userEvent.click(screen.getByRole('button', { name: 'Reset all to defaults' }));

    expect(readShortcutSettings()).toEqual({ bindings: {}, singleKeyEnabled: false });
  });
});

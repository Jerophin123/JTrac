import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import type { AgentConnection, AgentConnectionOptions } from '../../api/types';
import { ToastProvider } from '../../ui/Toast';
import { sessionQueryKey } from '../auth/session';
import { AgentConnectionsPage } from './AgentConnectionsPage';

vi.mock('../../api/client', () => ({ api: { GET: vi.fn(), POST: vi.fn(), PATCH: vi.fn(), DELETE: vi.fn() } }));

const get = vi.mocked(api.GET);
const post = vi.mocked(api.POST);

const OPTIONS: AgentConnectionOptions = {
  available: true,
  serverUrl: 'https://tracker.example.com',
  scopes: [
    { name: 'issues:read', description: 'Read issues', write: false },
    { name: 'attachments:read', description: 'Read attachments', write: false },
    { name: 'issues:comment', description: 'Add comments', write: true },
  ],
  profiles: [
    { key: 'ISSUE_READER', label: 'Issue reader', scopes: ['issues:read', 'attachments:read'], isDefault: true },
    { key: 'ISSUE_CONTRIBUTOR', label: 'Issue contributor', scopes: ['issues:read', 'attachments:read', 'issues:comment'], isDefault: false },
  ],
  maxConnections: 20,
};

const CONNECTION: AgentConnection = {
  id: '0190a6f0-0000-7000-8000-000000000001',
  name: 'Laptop',
  url: 'https://tracker.example.com/mcp/c_abcdefghijklmnopqrstuvwxyz012345',
  organization: { id: '0190a6f0-0000-7000-8000-0000000000aa', slug: 'acme', name: 'Acme' },
  scopes: ['issues:read', 'attachments:read'],
  profile: 'ISSUE_READER',
  status: 'ACTIVE',
  createdAt: '2026-10-28T10:00:00Z',
  lastUsedAt: null,
  lastClient: null,
  expiresAt: null,
  rotatedAt: null,
  revokedAt: null,
  authorizedAgents: 0,
  version: 0,
};

const ORGS = [{ id: CONNECTION.organization.id, slug: 'acme', name: 'Acme', role: 'OWNER' }];

function ok(data: unknown, status = 200) {
  return Promise.resolve({ data, response: new Response(null, { status }) }) as never;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  queryClient.setQueryData(sessionQueryKey, null);
  const rootRoute = createRootRoute({ component: Outlet });
  const paths = ['/account', '/account/tokens', '/account/privacy', '/account/appearance'];
  const routes = [
    createRoute({ getParentRoute: () => rootRoute, path: '/account/ai-agent-connections', component: AgentConnectionsPage }),
    ...paths.map((path) => createRoute({ getParentRoute: () => rootRoute, path, component: () => null })),
  ];
  const router = createRouter({ routeTree: rootRoute.addChildren(routes), history: createMemoryHistory({ initialEntries: ['/account/ai-agent-connections'] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

// jsdom has no modal dialogs; opening and closing is enough for these tests.
if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}

beforeEach(() => {
  get.mockReset();
  post.mockReset();
});

describe('AgentConnectionsPage', () => {
  it('creates a read-only connection by default and opens its setup guide', async () => {
    let created = false;
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok(OPTIONS);
      if (path === '/api/v1/me/agent-connections') return ok(created ? [CONNECTION] : []);
      if (path === '/api/v1/orgs') return ok(ORGS);
      return ok([]);
    }) as never);
    post.mockImplementation((() => {
      created = true;
      return ok(CONNECTION, 201);
    }) as never);
    renderPage();
    expect(await screen.findByText(/No connections yet/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'New connection' }));
    const dialog = await screen.findByRole('dialog', { name: 'New AI agent connection' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Laptop');
    expect(within(dialog).getByLabelText('Permissions')).toHaveTextContent('Issue reader (read-only, recommended)');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create connection' }));

    expect(post).toHaveBeenCalledWith('/api/v1/me/agent-connections', {
      body: { name: 'Laptop', organizationId: CONNECTION.organization.id, scopes: ['issues:read', 'attachments:read'], expiresInDays: 90 },
    });
    const manage = await screen.findByRole('dialog', { name: 'Laptop' });
    expect(within(manage).getByLabelText('Connection URL')).toHaveValue(CONNECTION.url);
    expect(within(manage).getByText('Waiting for an agent')).toBeInTheDocument();
    expect(within(manage).getByText(`claude mcp add --transport http jtrac ${CONNECTION.url}`)).toBeInTheDocument();
  });

  it('shows Connected only when the server reports a signed-in agent', async () => {
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok(OPTIONS);
      return ok([{ ...CONNECTION, authorizedAgents: 1, lastUsedAt: '2026-10-28T11:00:00Z', lastClient: 'Codex' }]);
    }) as never);
    renderPage();
    expect(await screen.findByText('Connected')).toBeInTheDocument();
    expect(screen.getByText(/by Codex/)).toBeInTheDocument();
  });

  it('explains why connections are unavailable', async () => {
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok({ ...OPTIONS, available: false, unavailableReason: 'AI agent connections require HTTPS.' });
      return ok([]);
    }) as never);
    renderPage();
    expect(await screen.findByText('AI agent connections require HTTPS.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New connection' })).toBeDisabled();
  });
});

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

const UTCP_OPTIONS = {
  available: true,
  utcpVersion: '1.0.1',
  manualVersion: '1.0.0',
  scopes: [
    { name: 'issues:read', description: 'Read issues', category: 'Issues' },
    { name: 'projects:read', description: 'Read projects', category: 'Projects' },
  ],
};

const UTCP_SETTINGS = {
  enabled: true,
  state: 'ENABLED',
  manualUrl: 'https://tracker.example.com/api/v1/utcp',
  utcpVersion: '1.0.1',
  manualVersion: '1.0.0',
  lastUsedAt: null,
  lastClient: null,
  authorizedClients: 0,
  toolCount: 24,
};

const UTCP_TOOLS = [
  {
    name: 'search_issues',
    description: 'Search issues with full-text search',
    category: 'Issues',
    scope: 'issues:read',
    inputs: {},
    outputs: {},
  },
];

const UTCP_ACTIVITY = {
  items: [
    {
      id: '0190a6f0-0000-7000-8000-000000000001',
      toolName: 'search_issues',
      status: 'SUCCESS',
      occurredAt: '2026-10-31T07:00:00Z',
      durationMs: 42,
      clientAgent: 'Test Agent',
      targetIdentifier: 'TEST-1',
    },
  ],
  page: 0,
  size: 10,
  total: 1,
};

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
      if (path === '/api/v1/me/utcp/options') return ok(UTCP_OPTIONS);
      if (path === '/api/v1/me/utcp/settings') return ok(UTCP_SETTINGS);
      if (path === '/api/v1/me/utcp/activity') return ok(UTCP_ACTIVITY);
      if (path === '/api/v1/utcp/tools') return ok(UTCP_TOOLS);
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
      if (path === '/api/v1/me/utcp/options') return ok(UTCP_OPTIONS);
      if (path === '/api/v1/me/utcp/settings') return ok(UTCP_SETTINGS);
      if (path === '/api/v1/me/utcp/activity') return ok(UTCP_ACTIVITY);
      if (path === '/api/v1/utcp/tools') return ok(UTCP_TOOLS);
      return ok([{ ...CONNECTION, authorizedAgents: 1, lastUsedAt: '2026-10-28T11:00:00Z', lastClient: 'Codex' }]);
    }) as never);
    renderPage();
    expect(await screen.findByText('Connected')).toBeInTheDocument();
    expect(screen.getByText(/by Codex/)).toBeInTheDocument();
  });

  it('explains why connections are unavailable', async () => {
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok({ ...OPTIONS, available: false, unavailableReason: 'AI agent connections require HTTPS.' });
      if (path === '/api/v1/me/utcp/options') return ok(UTCP_OPTIONS);
      if (path === '/api/v1/me/utcp/settings') return ok(UTCP_SETTINGS);
      if (path === '/api/v1/me/utcp/activity') return ok(UTCP_ACTIVITY);
      if (path === '/api/v1/utcp/tools') return ok(UTCP_TOOLS);
      return ok([]);
    }) as never);
    renderPage();
    expect(await screen.findByText('AI agent connections require HTTPS.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New connection' })).toBeDisabled();
  });

  it('renders UTCP integration card as sibling with Experimental badge and discovery URL', async () => {
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok(OPTIONS);
      if (path === '/api/v1/me/agent-connections') return ok([]);
      if (path === '/api/v1/orgs') return ok(ORGS);
      if (path === '/api/v1/me/utcp/options') return ok(UTCP_OPTIONS);
      if (path === '/api/v1/me/utcp/settings') return ok(UTCP_SETTINGS);
      if (path === '/api/v1/me/utcp/activity') return ok(UTCP_ACTIVITY);
      if (path === '/api/v1/utcp/tools') return ok(UTCP_TOOLS);
      return ok([]);
    }) as never);
    renderPage();

    // Sibling UTCP presence with Experimental badge
    expect(await screen.findByText('Experimental')).toBeInTheDocument();
    expect(screen.getByText('Connect UTCP-compatible AI agents directly to JTRAC tools through a standardized tool discovery and calling interface.')).toBeInTheDocument();

    // Discovery URL display
    expect(await screen.findByLabelText('UTCP manual (discovery URL)')).toHaveValue(UTCP_SETTINGS.manualUrl);
    expect(screen.getByRole('button', { name: /Copy UTCP manual URL/i })).toBeInTheDocument();
  });

  it('allows disabling and enabling UTCP integration', async () => {
    let enabled = true;
    get.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/agent-connections/options') return ok(OPTIONS);
      if (path === '/api/v1/me/agent-connections') return ok([]);
      if (path === '/api/v1/orgs') return ok(ORGS);
      if (path === '/api/v1/me/utcp/options') return ok(UTCP_OPTIONS);
      if (path === '/api/v1/me/utcp/settings') return ok({ ...UTCP_SETTINGS, enabled, state: enabled ? 'ENABLED' : 'DISABLED' });
      if (path === '/api/v1/me/utcp/activity') return ok(UTCP_ACTIVITY);
      if (path === '/api/v1/utcp/tools') return ok(UTCP_TOOLS);
      return ok([]);
    }) as never);
    post.mockImplementation(((path: string) => {
      if (path === '/api/v1/me/utcp/disable') {
        enabled = false;
        return ok({ enabled: false, state: 'DISABLED' });
      }
      if (path === '/api/v1/me/utcp/enable') {
        enabled = true;
        return ok({ enabled: true, state: 'ENABLED' });
      }
      return ok({});
    }) as never);
    renderPage();

    const disableBtn = await screen.findByRole('button', { name: 'Disable UTCP' });
    await userEvent.click(disableBtn);
    expect(post).toHaveBeenCalledWith('/api/v1/me/utcp/disable', {});
  });
});

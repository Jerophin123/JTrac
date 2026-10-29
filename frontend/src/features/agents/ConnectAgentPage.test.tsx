import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import type { AgentConsent } from '../../api/types';
import { ConnectAgentPage } from './ConnectAgentPage';
import { leaveTo } from './navigation';

vi.mock('./navigation', () => ({ leaveTo: vi.fn() }));
vi.mock('../../api/client', () => ({ api: { GET: vi.fn(), POST: vi.fn() } }));

const get = vi.mocked(api.GET);
const post = vi.mocked(api.POST);

const CONSENT: AgentConsent = {
  clientName: 'Claude Code',
  redirectUri: 'http://localhost:43123/callback',
  redirectHost: 'localhost',
  redirectKind: 'LOOPBACK',
  connectionId: '0190a6f0-0000-7000-8000-000000000001',
  connectionName: 'Laptop',
  organizationName: 'Acme',
  organizationSlug: 'acme',
  granted: [{ name: 'issues:read', description: 'Search and read issues', write: false }],
  notEnabled: [{ name: 'issues:write', description: 'Change issue fields', write: true }],
  expiresAt: '2026-10-28T12:00:00Z',
};

function ok(data: unknown) {
  return Promise.resolve({ data, response: new Response(null, { status: 200 }) }) as never;
}

function renderPage(props: { request?: string; error?: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const rootRoute = createRootRoute({ component: Outlet });
  const page = createRoute({ getParentRoute: () => rootRoute, path: '/', component: () => <ConnectAgentPage {...props} /> });
  const connections = createRoute({ getParentRoute: () => rootRoute, path: '/account/ai-agent-connections', component: () => <p>Connections</p> });
  const legal = ['/legal/terms', '/legal/privacy', '/legal/grievance'].map((path) => createRoute({ getParentRoute: () => rootRoute, path, component: () => null }));
  const router = createRouter({ routeTree: rootRoute.addChildren([page, connections, ...legal]), history: createMemoryHistory({ initialEntries: ['/'] }) });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  get.mockReset();
  post.mockReset();
  vi.mocked(leaveTo).mockReset();
});

describe('ConnectAgentPage', () => {
  it('shows who asks, where the result goes and what will be allowed', async () => {
    get.mockReturnValue(ok(CONSENT));
    renderPage({ request: 'handle-1' });
    expect(await screen.findByText('Claude Code')).toBeInTheDocument();
    expect(screen.getByText('Laptop in Acme')).toBeInTheDocument();
    expect(screen.getByText(/goes to a program on this computer/)).toBeInTheDocument();
    expect(screen.getByText('issues:read')).toBeInTheDocument();
    expect(screen.getByText(/Also requested but not enabled for this connection: issues:write/)).toBeInTheDocument();
    expect(screen.queryByText(/will be able to change issues/)).not.toBeInTheDocument();
  });

  it('warns about unrecognised websites and write access', async () => {
    get.mockReturnValue(
      ok({
        ...CONSENT,
        redirectKind: 'UNRECOGNISED',
        redirectHost: 'agent.example',
        granted: [{ name: 'issues:write', description: 'Change issue fields', write: true }],
      }),
    );
    renderPage({ request: 'handle-1' });
    expect(await screen.findByText('Unrecognised website')).toBeInTheDocument();
    expect(screen.getByText('This agent will be able to change issues as you.')).toBeInTheDocument();
  });

  it('approves and hands the code back to the agent', async () => {
    get.mockReturnValue(ok(CONSENT));
    post.mockReturnValue(ok({ redirectUrl: 'http://localhost:43123/callback?code=abc&state=xyz' }));
    renderPage({ request: 'handle-1' });
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(post).toHaveBeenCalledWith('/api/v1/me/agent-authorizations/{handle}/approve', { params: { path: { handle: 'handle-1' } } });
    expect(leaveTo).toHaveBeenCalledWith('http://localhost:43123/callback?code=abc&state=xyz');
    expect(await screen.findByText(/You can close this tab/)).toBeInTheDocument();
  });

  it('cannot approve when no requested permission is enabled', async () => {
    get.mockReturnValue(ok({ ...CONSENT, granted: [] }));
    renderPage({ request: 'handle-1' });
    expect(await screen.findByRole('button', { name: 'Approve' })).toBeDisabled();
  });

  it('explains failed sign-ins without contacting the server', async () => {
    renderPage({ error: 'invalid_redirect_uri' });
    expect(await screen.findByText(/did not register/)).toBeInTheDocument();
    expect(get).not.toHaveBeenCalled();
  });
});

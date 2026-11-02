import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { components } from '../../api/schema';
import { publicConfigQueryOptions, sessionQueryKey } from '../auth/session';
import { CookiePolicyPage } from '../legal/LegalPages';
import { aboutQuery, AboutPage } from './AboutPage';

type LegalInfo = components['schemas']['LegalInfo'];

const UNCONFIGURED: LegalInfo = {
  version: '2026-10',
  effectiveDate: '2026-10-24',
  operatorName: '',
  operatorAddress: '',
  contactEmail: '',
  grievanceOfficerName: '',
  grievanceOfficerEmail: '',
  jurisdiction: '',
};

function renderAt(path: string, { legal = UNCONFIGURED, serverAdmin = false } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  qc.setQueryData(sessionQueryKey, {
    user: { id: 'u', email: 'ada@example.com', displayName: 'Ada', handle: 'ada', serverAdmin },
    consentRequired: false,
  });
  qc.setQueryData(publicConfigQueryOptions.queryKey, { registrationOpen: false, emailEnabled: true, ssoProviders: [], legal });
  qc.setQueryData(aboutQuery.queryKey, { version: '3.1.0', buildTime: '2026-11-02T08:30:00Z' });
  const root = createRootRoute({ component: Outlet });
  const routes = [
    createRoute({ getParentRoute: () => root, path: '/account/about', component: AboutPage }),
    createRoute({ getParentRoute: () => root, path: '/legal/cookies', component: CookiePolicyPage }),
  ];
  const router = createRouter({ routeTree: root.addChildren(routes), history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('AboutPage', () => {
  it('shows the version and copies it for support requests', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderAt('/account/about');
    expect(await screen.findByText('Version 3.1.0')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(writeText).toHaveBeenCalledWith(expect.stringMatching(/^JTRAC 3\.1\.0 \(built .+\)$/));
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('links every legal document, including the Cookie Policy', async () => {
    renderAt('/account/about');
    const legal = await screen.findByRole('region', { name: 'Legal and policies' });
    const documents = [
      ['Terms of Service', '/legal/terms'],
      ['Privacy Notice', '/legal/privacy'],
      ['Cookie Policy', '/legal/cookies'],
      ['Grievance Redressal', '/legal/grievance'],
    ] as const;
    for (const [name, href] of documents) {
      expect(within(legal).getByRole('link', { name: new RegExp(name) })).toHaveAttribute('href', href);
    }
    expect(legal).toHaveTextContent('Version 2026-10');
  });

  it('marks missing operator details instead of inventing them, and tells server admins how to fill them in', async () => {
    renderAt('/account/about', { serverAdmin: true });
    expect(await screen.findByText('[Operator name not configured]')).toBeInTheDocument();
    expect(screen.getByText('jtrac configure legal')).toBeInTheDocument();
  });

  it('lists every account page in the navigation, with the server pages for server admins only', async () => {
    renderAt('/account/about', { serverAdmin: true });
    const nav = await screen.findByRole('navigation', { name: 'Account settings' });
    for (const name of ['Profile', 'API tokens', 'AI agents', 'Privacy & data', 'Appearance', 'Keyboard shortcuts', 'Server overview', 'Outgoing email', 'About JTRAC']) {
      expect(within(nav).getByRole('link', { name })).toBeInTheDocument();
    }
  });

  it('hides the server pages from everyone else', async () => {
    renderAt('/account/about');
    const nav = await screen.findByRole('navigation', { name: 'Account settings' });
    expect(within(nav).queryByRole('link', { name: 'Server overview' })).not.toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Outgoing email' })).not.toBeInTheDocument();
  });

  it('shows configured operator details with a contact link', async () => {
    renderAt('/account/about', { legal: { ...UNCONFIGURED, operatorName: 'Acme Pvt Ltd', contactEmail: 'privacy@acme.test', grievanceOfficerEmail: 'grievance@acme.test' } });
    expect(await screen.findByText('Acme Pvt Ltd')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'privacy@acme.test' })).toHaveAttribute('href', 'mailto:privacy@acme.test');
    expect(screen.queryByText('jtrac configure legal')).not.toBeInTheDocument();
  });
});

describe('CookiePolicyPage', () => {
  it('lists every cookie and stored preference and says there is no tracking', async () => {
    renderAt('/legal/cookies');
    expect(await screen.findByRole('heading', { level: 1, name: 'Cookie Policy' })).toBeInTheDocument();
    for (const name of ['JTRAC_SESSION', 'XSRF-TOKEN', 'jtrac.theme', 'jtrac.lastOrg', 'jtrac.sidebar', 'jtrac.shortcuts']) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
    expect(screen.getByText(/no advertising, analytics, social-media or third-party cookies/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cookie Policy' })).toHaveAttribute('aria-current', 'page');
  });
});

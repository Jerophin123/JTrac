import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SetupPage } from './SetupPage';
import * as setupApi from './setupApi';
import * as session from './session';

vi.mock('./setupApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./setupApi')>();
  return { ...actual, completeSetup: vi.fn() };
});

vi.mock('./session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./session')>();
  return { ...actual, login: vi.fn() };
});

const completeSetup = vi.mocked(setupApi.completeSetup);
const login = vi.mocked(session.login);
const PASSWORD = 'super-secret-password-123';

async function renderSetup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const rootRoute = createRootRoute({ component: Outlet });
  const setupRoute = createRoute({ getParentRoute: () => rootRoute, path: '/setup', component: SetupPage });
  const orgRoute = createRoute({ getParentRoute: () => rootRoute, path: '/$org', component: () => <p>Org Home</p> });
  const router = createRouter({
    routeTree: rootRoute.addChildren([setupRoute, orgRoute]),
    history: createMemoryHistory({ initialEntries: ['/setup'] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  await router.load();
}

beforeEach(() => {
  completeSetup.mockReset();
  login.mockReset();
});

describe('SetupPage', () => {
  it('renders confirmation password field and disables submission on password mismatch', async () => {
    await renderSetup();

    expect(await screen.findByLabelText(/^Setup code/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Display name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Email address/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Password/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Confirm password/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Organization name/i)).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/^Setup code/i), 'CODE-1234');
    await userEvent.type(screen.getByLabelText(/^Display name/i), 'Administrator');
    await userEvent.type(screen.getByLabelText(/^Email address/i), 'admin@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/i), PASSWORD);
    await userEvent.type(screen.getByLabelText(/^Confirm password/i), 'different-password');
    await userEvent.type(screen.getByLabelText(/^Organization name/i), 'Acme Corp');

    const submitBtn = screen.getByRole('button', { name: /Complete Setup/i });
    expect(submitBtn).toBeDisabled();
    expect(screen.getByText('The passwords don’t match.')).toBeInTheDocument();
    expect(completeSetup).not.toHaveBeenCalled();
  });

  it('enables submission when passwords match and submits correct payload without confirm field', async () => {
    completeSetup.mockResolvedValue({ organizationSlug: 'acme-corp' });
    login.mockResolvedValue({
      user: { id: 'u1', email: 'admin@example.com', displayName: 'Administrator', handle: 'admin' },
      consentRequired: false,
    });

    await renderSetup();

    await userEvent.type(await screen.findByLabelText(/^Setup code/i), 'CODE-1234');
    await userEvent.type(screen.getByLabelText(/^Display name/i), 'Administrator');
    await userEvent.type(screen.getByLabelText(/^Email address/i), 'admin@example.com');
    await userEvent.type(screen.getByLabelText(/^Password/i), PASSWORD);
    await userEvent.type(screen.getByLabelText(/^Confirm password/i), PASSWORD);
    await userEvent.type(screen.getByLabelText(/^Organization name/i), 'Acme Corp');

    // Consent must be given explicitly: the box starts unticked and blocks submission.
    const submitBtn = screen.getByRole('button', { name: /Complete Setup/i });
    expect(submitBtn).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/I have read and agree/i));
    expect(submitBtn).not.toBeDisabled();
    await userEvent.click(submitBtn);

    expect(completeSetup).toHaveBeenCalledWith({
      setupCode: 'CODE-1234',
      displayName: 'Administrator',
      email: 'admin@example.com',
      password: PASSWORD,
      organizationName: 'Acme Corp',
      organizationSlug: 'acme-corp',
      acceptTerms: true,
    });
  });
});

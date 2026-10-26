import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRoute, createRouter, Outlet, RouterProvider } from '@tanstack/react-router';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RegisterPage } from './AccountFlows';
import * as session from './session';

vi.mock('./session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./session')>();
  return { ...actual, register: vi.fn() };
});

const register = vi.mocked(session.register);
const PASSWORD = 'correct horse battery staple';

function renderRegister() {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  queryClient.setQueryData(['public-config'], { registrationOpen: true });
  const rootRoute = createRootRoute({ component: Outlet });
  const registerRoute = createRoute({ getParentRoute: () => rootRoute, path: '/register', component: RegisterPage });
  const loginRoute = createRoute({ getParentRoute: () => rootRoute, path: '/login', component: () => <p>Sign in page</p> });
  const router = createRouter({
    routeTree: rootRoute.addChildren([registerRoute, loginRoute]),
    history: createMemoryHistory({ initialEntries: ['/register'] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

async function fillForm(confirm: string) {
  await userEvent.type(await screen.findByLabelText('Name'), 'Ada Lovelace');
  await userEvent.type(screen.getByLabelText('Email'), 'ada@example.com');
  await userEvent.type(screen.getByLabelText('Password'), PASSWORD);
  await userEvent.type(screen.getByLabelText('Confirm password'), confirm);
}

beforeEach(() => {
  register.mockReset();
});

describe('RegisterPage', () => {
  it('asks for the password twice and blocks a mismatch', async () => {
    renderRegister();
    await fillForm('something else entirely');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('The passwords don’t match.')).toBeInTheDocument();
    expect(screen.getByLabelText('Confirm password')).toHaveAttribute('aria-invalid', 'true');
    expect(register).not.toHaveBeenCalled();
  });

  it('requires consent to the Terms and Privacy Notice', async () => {
    renderRegister();
    await fillForm(PASSWORD);
    expect(screen.getByLabelText(/I have read and agree/i)).not.toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Accept the Terms of Service and Privacy Notice to continue.')).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('sends only the account fields and the consent, never the confirmation', async () => {
    register.mockResolvedValue(undefined);
    renderRegister();
    await fillForm(PASSWORD);
    await userEvent.click(screen.getByLabelText(/I have read and agree/i));
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText(/We sent a confirmation link/)).toBeInTheDocument();
    expect(register).toHaveBeenCalledTimes(1);
    expect(register.mock.calls[0]?.[0]).toEqual({ displayName: 'Ada Lovelace', email: 'ada@example.com', password: PASSWORD, acceptTerms: true });
  });

  it('shows and hides the typed password', async () => {
    renderRegister();
    const field = await screen.findByLabelText('Password');
    await userEvent.type(field, PASSWORD);
    expect(field).toHaveAttribute('type', 'password');

    const toggle = screen.getByRole('button', { name: 'Show password' });
    await userEvent.click(toggle);
    expect(field).toHaveAttribute('type', 'text');
    expect(field).toHaveValue(PASSWORD);
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(field).toHaveAttribute('type', 'password');
  });
});

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/problem';
import { describeLoginError, LoginPage, SIGNED_OUT_NOTICE_MS } from './LoginPage';
import * as session from './session';

vi.mock('./session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./session')>();
  return { ...actual, login: vi.fn() };
});

const login = vi.mocked(session.login);

function renderLogin({ signedOut = false } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const rootRoute = createRootRoute({ component: Outlet });
  const loginRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/login',
    component: () => <LoginPage redirectTo="/" signedOut={signedOut} />,
  });
  const homeRoute = createRoute({ getParentRoute: () => rootRoute, path: '/', component: () => <p>Home page</p> });
  const router = createRouter({
    routeTree: rootRoute.addChildren([loginRoute, homeRoute]),
    history: createMemoryHistory({ initialEntries: ['/login'] }),
  });
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { queryClient, router };
}

beforeEach(() => {
  login.mockReset();
});

describe('LoginPage', () => {
  it('validates input before contacting the server', async () => {
    renderLogin();
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter your email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(login).not.toHaveBeenCalled();
  });

  it('shows the server’s invalid-credentials answer', async () => {
    login.mockRejectedValue(
      new ApiError(401, { type: 'urn:jtac:problem:invalid-credentials', title: 'Invalid credentials', status: 401 }),
    );
    renderLogin();
    await userEvent.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That email and password combination is incorrect.');
    expect(login.mock.calls[0]?.[0]).toEqual({ email: 'ada@example.com', password: 'wrong password' });
  });

  it('stores the session and navigates on success', async () => {
    const user = { id: '0192f4a0-0000-7000-8000-000000000001', email: 'ada@example.com', displayName: 'Ada', handle: 'ada' };
    login.mockResolvedValue({ user, consentRequired: false });
    const { queryClient } = renderLogin();
    await userEvent.type(await screen.findByLabelText('Email'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Home page')).toBeInTheDocument();
    await waitFor(() => {
      expect(queryClient.getQueryData(session.sessionQueryKey)).toEqual({ user, consentRequired: false });
    });
  });

  it('confirms a completed sign-out', async () => {
    renderLogin({ signedOut: true });
    expect(await screen.findByRole('status')).toHaveTextContent('You’re signed out.');
  });

  it('removes the sign-out confirmation after a few seconds', async () => {
    // Fake timers from the start (so the notice's timers are controllable), still ticking in
    // real time so the router and queries can settle.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderLogin({ signedOut: true });
      expect(await screen.findByRole('status')).toHaveTextContent('You’re signed out.');
      act(() => {
        vi.advanceTimersByTime(SIGNED_OUT_NOTICE_MS - 1000);
      });
      expect(screen.getByText('You’re signed out.')).toBeInTheDocument();
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(screen.queryByText('You’re signed out.')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('describeLoginError', () => {
  it('explains rate limiting with the retry time', () => {
    const error = new ApiError(429, {
      type: 'urn:jtac:problem:rate-limited',
      title: 'Too many',
      status: 429,
      retryAfterSeconds: 610,
    });
    expect(describeLoginError(error)).toBe('Too many sign-in attempts. Try again in 11 minutes.');
  });

  it('distinguishes an unreachable server', () => {
    expect(describeLoginError(new TypeError('Failed to fetch'))).toMatch(/can’t be reached/);
  });

  it('falls back to a generic message', () => {
    expect(describeLoginError(new ApiError(500, undefined))).toBe(
      'Something went wrong while signing in. Please try again.',
    );
  });
});

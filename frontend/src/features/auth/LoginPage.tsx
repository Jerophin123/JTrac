import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { LockKeyhole, Moon, Sun, Sunrise } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError, isNetworkError } from '../../api/problem';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { PasswordField } from '../../ui/PasswordField';
import { TextField } from '../../ui/TextField';
import { AuthLayout, authFormClass } from './AuthLayout';
import styles from './LoginPage.module.css';
import { login, sessionQueryKey, type Session } from './session';
import { setupStatusQueryOptions } from './setupApi';

const loginSchema = z.object({
  email: z
    .string()
    .trim()
    .min(1, 'Enter your email address.')
    .max(320, 'Email must be at most 320 characters.')
    .pipe(z.email('Enter a valid email address.')),
  password: z.string().min(1, 'Enter your password.').max(1024, 'Password is too long.'),
});

type LoginForm = z.infer<typeof loginSchema>;

export function describeLoginError(error: unknown): string {
  if (isNetworkError(error)) {
    return 'JTRAC can’t be reached right now. Check your connection and try again.';
  }
  if (error instanceof ApiError) {
    if (error.is('urn:jtac:problem:invalid-credentials')) {
      return 'That email and password combination is incorrect.';
    }
    if (error.is('urn:jtac:problem:rate-limited')) {
      const seconds = error.problem?.retryAfterSeconds ?? 60;
      const minutes = Math.max(1, Math.ceil(seconds / 60));
      return `Too many sign-in attempts. Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`;
    }
    if (error.is('urn:jtac:problem:validation-failed')) {
      return 'Check the highlighted fields and try again.';
    }
    if (error.is('urn:jtac:problem:email-not-verified')) {
      return 'Confirm your email address first: open the link we sent you, or request a new one from the confirmation page.';
    }
  }
  return 'Something went wrong while signing in. Please try again.';
}

/** A friendly greeting for the local time of day, with a matching icon. */
function Greeting() {
  const hour = new Date().getHours();
  const [Icon, text] = hour < 5 ? [Moon, 'Good evening'] : hour < 12 ? [Sunrise, 'Good morning'] : hour < 18 ? [Sun, 'Good afternoon'] : [Moon, 'Good evening'];
  return (
    <>
      <Icon size={15} aria-hidden="true" />
      {text}, welcome back
    </>
  );
}

/** How long the signed-out confirmation stays before it fades away, and the fade itself. */
export const SIGNED_OUT_NOTICE_MS = 5000;
const FADE_MS = 400;

/** A brief confirmation after signing out: it fades out after a few seconds instead of lingering. */
function SignedOutNotice() {
  const [phase, setPhase] = useState<'shown' | 'leaving' | 'gone'>('shown');
  useEffect(() => {
    const fade = window.setTimeout(() => { setPhase('leaving'); }, SIGNED_OUT_NOTICE_MS - FADE_MS);
    const hide = window.setTimeout(() => { setPhase('gone'); }, SIGNED_OUT_NOTICE_MS);
    return () => {
      window.clearTimeout(fade);
      window.clearTimeout(hide);
    };
  }, []);
  if (phase === 'gone') {
    return null;
  }
  return (
    <div className={styles.notice} data-leaving={phase === 'leaving' || undefined}>
      <Alert tone="success" title="You’re signed out." />
    </div>
  );
}

interface LoginPageProps {
  redirectTo: string | undefined;
  signedOut: boolean;
}

export function LoginPage({ redirectTo, signedOut }: LoginPageProps) {
  const queryClient = useQueryClient();
  const setupQuery = useQuery(setupStatusQueryOptions);
  const navigate = useNavigate();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema), mode: 'onSubmit' });

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: async (session: Session) => {
      queryClient.setQueryData(sessionQueryKey, session);
      await navigate({ href: redirectTo ?? '/', replace: true });
    },
    onError: (error) => {
      if (error instanceof ApiError && error.is('urn:jtac:problem:validation-failed')) {
        for (const fieldError of error.problem?.errors ?? []) {
          if (fieldError.field === 'email' || fieldError.field === 'password') {
            setError(fieldError.field, { message: fieldError.message });
          }
        }
      }
    },
  });

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(values);
  });

  return (
    <AuthLayout
      greeting={<Greeting />}
      title="Sign in"
      subtitle="Pick up right where you and your team left off."
      note={
        <>
          <LockKeyhole size={13} aria-hidden="true" />
          <span>Your session stays private in a secure cookie that scripts can’t read. On a shared computer, sign out when you’re done.</span>
        </>
      }
    >
      {setupQuery.data?.required && (
        <Alert tone="info" title="Initial setup required">
          No accounts exist yet. <Link to="/setup">Complete the first-run welcome setup</Link> to create the administrator account and your organization.
        </Alert>
      )}
      {signedOut && !mutation.isError && <SignedOutNotice />}
      {mutation.isError && <Alert tone="danger">{describeLoginError(mutation.error)}</Alert>}

      <form className={authFormClass} onSubmit={(event) => void onSubmit(event)} noValidate>
        <TextField
          label="Email"
          type="email"
          autoComplete="username"
          inputMode="email"
          spellCheck={false}
          error={errors.email?.message}
          {...register('email')}
        />
        <PasswordField label="Password" autoComplete="current-password" error={errors.password?.message} {...register('password')} />
        <div className={styles.forgot}>
          <Link to="/forgot-password">Forgot your password?</Link>
        </div>
        <Button type="submit" variant="primary" block loading={mutation.isPending}>
          {mutation.isPending ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </AuthLayout>
  );
}

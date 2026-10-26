import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useEffect, useRef } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { describeError, fieldErrors } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { Skeleton } from '../../ui/Page';
import { PasswordField } from '../../ui/PasswordField';
import { TextField } from '../../ui/TextField';
import { ConsentCheckbox } from '../legal/ConsentCheckbox';
import { AuthLayout, authFormClass } from './AuthLayout';
import {
  confirmPasswordReset,
  publicConfigQueryOptions,
  register as registerAccount,
  requestPasswordReset,
  resendVerification,
  sessionQueryOptions,
  verifyEmail,
} from './session';

const email = z.string().trim().min(1, 'Enter your email address.').max(320).pipe(z.email('Enter a valid email address.'));
const newPassword = z
  .string()
  .min(12, 'Use at least 12 characters.')
  .max(128, 'Use at most 128 characters.')
  .refine((value) => value.trim().length > 0, 'The password must not be only spaces.');

// --- registration -------------------------------------------------------------------------

const confirmPassword = z.string().min(1, 'Type the password again to confirm it.');
const acceptTerms = z.boolean().refine((v) => v, 'Accept the Terms of Service and Privacy Notice to continue.');

const registerSchema = z
  .object({
    displayName: z.string().trim().min(1, 'Enter your name.').max(120),
    email,
    password: newPassword,
    confirm: confirmPassword,
    acceptTerms,
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'The passwords don’t match.' });

export function RegisterPage() {
  const config = useQuery(publicConfigQueryOptions);
  const form = useForm<z.infer<typeof registerSchema>>({ resolver: zodResolver(registerSchema), defaultValues: { acceptTerms: false } });
  const mutation = useMutation({
    mutationFn: registerAccount,
    onError: (error) => {
      for (const [field, message] of Object.entries(fieldErrors(error))) {
        if (field === 'email' || field === 'password' || field === 'displayName' || field === 'acceptTerms') {
          form.setError(field, { message });
        }
      }
    },
  });
  const errors = form.formState.errors;

  if (config.isPending) {
    return (
      <AuthLayout title="Create your account">
        <Skeleton lines={4} />
      </AuthLayout>
    );
  }
  if (config.data && !config.data.registrationOpen) {
    return (
      <AuthLayout title="Registration is by invitation" footer={<Link to="/login">Back to sign in</Link>}>
        <Alert tone="info">Ask an administrator of your organization to invite you by email.</Alert>
      </AuthLayout>
    );
  }
  if (mutation.isSuccess) {
    return (
      <AuthLayout title="Check your inbox" footer={<Link to="/login">Back to sign in</Link>}>
        <Alert tone="success">
          We sent a confirmation link to <strong>{form.getValues('email')}</strong>. Open it to activate your account.
        </Alert>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout
      title="Create your account"
      subtitle="Start a workspace for your team, or join one you were invited to."
      footer={
        <>
          Already have an account? <Link to="/login">Sign in</Link>
        </>
      }
    >
      {mutation.isError && Object.keys(fieldErrors(mutation.error)).length === 0 && (
        <Alert tone="danger">{describeError(mutation.error)}</Alert>
      )}
      <form className={authFormClass} noValidate onSubmit={(e) => void form.handleSubmit((v) => { mutation.mutate({ displayName: v.displayName, email: v.email, password: v.password, acceptTerms: v.acceptTerms }); })(e)}>
        <TextField label="Name" autoComplete="name" error={errors.displayName?.message} {...form.register('displayName')} />
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...form.register('email')} />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          hint="At least 12 characters. A passphrase is easiest to remember."
          error={errors.password?.message}
          {...form.register('password')}
        />
        <PasswordField label="Confirm password" autoComplete="new-password" error={errors.confirm?.message} {...form.register('confirm')} />
        <ConsentCheckbox error={errors.acceptTerms?.message} {...form.register('acceptTerms')} />
        <Button type="submit" variant="primary" block loading={mutation.isPending}>
          Create account
        </Button>
      </form>
    </AuthLayout>
  );
}

// --- email verification ---------------------------------------------------------------------

export function VerifyEmailPage({ token }: { token: string | undefined }) {
  const mutation = useMutation({ mutationFn: verifyEmail });
  const started = useRef(false);
  useEffect(() => {
    if (token && !started.current) {
      started.current = true;
      mutation.mutate(token);
    }
  }, [token, mutation]);

  return (
    <AuthLayout title="Confirm your email" footer={<Link to="/login">Go to sign in</Link>}>
      {!token && <Alert tone="danger">This link is incomplete. Open the link from the email again.</Alert>}
      {mutation.isPending && <Skeleton lines={2} label="Confirming your email address" />}
      {mutation.isSuccess && <Alert tone="success" title="Your email address is confirmed.">You can sign in now.</Alert>}
      {mutation.isError && <ResendVerification message={describeError(mutation.error)} />}
    </AuthLayout>
  );
}

function ResendVerification({ message }: { message: string }) {
  const form = useForm<{ email: string }>({ resolver: zodResolver(z.object({ email })) });
  const resend = useMutation({ mutationFn: (value: string) => resendVerification(value) });
  return (
    <>
      <Alert tone="danger">{message}</Alert>
      {resend.isSuccess ? (
        <Alert tone="success">If that address has an unconfirmed account, a new link is on its way.</Alert>
      ) : (
        <form className={authFormClass} noValidate onSubmit={(e) => void form.handleSubmit((v) => { resend.mutate(v.email); })(e)}>
          <TextField label="Email" type="email" autoComplete="email" error={form.formState.errors.email?.message} {...form.register('email')} />
          <Button type="submit" loading={resend.isPending}>
            Send a new link
          </Button>
        </form>
      )}
    </>
  );
}

// --- password reset ---------------------------------------------------------------------------

export function ForgotPasswordPage() {
  const config = useQuery(publicConfigQueryOptions);
  const form = useForm<{ email: string }>({ resolver: zodResolver(z.object({ email })) });
  const mutation = useMutation({ mutationFn: (value: string) => requestPasswordReset(value) });
  return (
    <AuthLayout
      title="Reset your password"
      subtitle="Enter the email address of your account. We’ll send you a link to choose a new password."
      footer={<Link to="/login">Back to sign in</Link>}
    >
      {config.data?.emailEnabled === false && (
        <Alert tone="warning" title="Email isn’t available on this server yet">
          Reset links can’t be emailed until an administrator sets up outgoing email. Ask your JTRAC administrator for help.
        </Alert>
      )}
      {mutation.isSuccess ? (
        <Alert tone="success" title="Check your inbox">
          If an account exists for that address, a reset link is on its way. It expires in one hour.
        </Alert>
      ) : (
        <>
          {mutation.isError && <Alert tone="danger">{describeError(mutation.error)}</Alert>}
          <form className={authFormClass} noValidate onSubmit={(e) => void form.handleSubmit((v) => { mutation.mutate(v.email); })(e)}>
            <TextField label="Email" type="email" autoComplete="email" error={form.formState.errors.email?.message} {...form.register('email')} />
            <Button type="submit" variant="primary" block loading={mutation.isPending}>
              Send reset link
            </Button>
          </form>
        </>
      )}
    </AuthLayout>
  );
}

const resetSchema = z
  .object({ password: newPassword, confirm: confirmPassword })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'The passwords don’t match.' });

export function ResetPasswordPage({ token }: { token: string | undefined }) {
  const form = useForm<z.infer<typeof resetSchema>>({ resolver: zodResolver(resetSchema) });
  const mutation = useMutation({
    mutationFn: (password: string) => confirmPasswordReset(token ?? '', password),
    onError: (error) => {
      const message = fieldErrors(error).password;
      if (message) {
        form.setError('password', { message });
      }
    },
  });
  return (
    <AuthLayout title="Choose a new password" footer={<Link to="/login">Back to sign in</Link>}>
      {!token && <Alert tone="danger">This link is incomplete. Open the link from the email again.</Alert>}
      {mutation.isSuccess ? (
        <Alert tone="success" title="Your password was changed.">
          All other sessions were signed out. <Link to="/login">Sign in</Link> with your new password.
        </Alert>
      ) : (
        <>
          {mutation.isError && !fieldErrors(mutation.error).password && (
            <Alert tone="danger">
              {describeError(mutation.error)} <Link to="/forgot-password">Request a new link</Link>.
            </Alert>
          )}
          <form className={authFormClass} noValidate onSubmit={(e) => void form.handleSubmit((v) => { mutation.mutate(v.password); })(e)}>
            <PasswordField label="New password" autoComplete="new-password" hint="At least 12 characters." error={form.formState.errors.password?.message} {...form.register('password')} />
            <PasswordField label="Confirm new password" autoComplete="new-password" error={form.formState.errors.confirm?.message} {...form.register('confirm')} />
            <Button type="submit" variant="primary" block loading={mutation.isPending} disabled={!token}>
              Change password
            </Button>
          </form>
        </>
      )}
    </AuthLayout>
  );
}

// --- invitations ------------------------------------------------------------------------------

const newAccountSchema = z
  .object({ displayName: z.string().trim().min(1, 'Enter your name.').max(120), password: newPassword, confirm: confirmPassword, acceptTerms })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'The passwords don’t match.' });

export function AcceptInvitePage({ token }: { token: string | undefined }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const session = useQuery(sessionQueryOptions);
  const preview = useQuery({
    queryKey: ['invitation', token],
    enabled: Boolean(token),
    retry: false,
    queryFn: () => call(() => api.GET('/api/v1/public/invitations/{token}', { params: { path: { token: token ?? '' } } })),
  });
  const accept = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/invitations/{token}/accept', { params: { path: { token: token ?? '' } } })),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['orgs'] });
      await navigate({ to: '/$org', params: { org: result.organizationSlug } });
    },
  });
  const form = useForm<z.infer<typeof newAccountSchema>>({ resolver: zodResolver(newAccountSchema), defaultValues: { acceptTerms: false } });
  const register = useMutation({
    mutationFn: (values: z.infer<typeof newAccountSchema>) =>
      call(() =>
        api.POST('/api/v1/public/invitations/{token}/register', {
          params: { path: { token: token ?? '' } },
          // Only the API fields are sent: never the confirmation.
          body: { displayName: values.displayName, password: values.password, acceptTerms: values.acceptTerms },
        }),
      ),
  });

  if (!token) {
    return (
      <AuthLayout title="Invitation">
        <Alert tone="danger">This link is incomplete. Open the link from the email again.</Alert>
      </AuthLayout>
    );
  }
  if (preview.isPending || session.isPending) {
    return (
      <AuthLayout title="Invitation">
        <Skeleton lines={3} label="Loading invitation" />
      </AuthLayout>
    );
  }
  if (preview.isError) {
    return (
      <AuthLayout title="Invitation" footer={<Link to="/login">Go to sign in</Link>}>
        <Alert tone="danger">{describeError(preview.error)}</Alert>
      </AuthLayout>
    );
  }
  const invite = preview.data;
  const title = `Join ${invite.organizationName}`;
  const subtitle = (
    <>
      {invite.invitedBy ?? 'An administrator'} invited <strong>{invite.email}</strong> as {invite.role.toLowerCase()}.
    </>
  );

  if (register.isSuccess) {
    return (
      <AuthLayout title={title} footer={<Link to="/login">Sign in</Link>}>
        <Alert tone="success" title="Your account is ready.">Sign in to open {invite.organizationName}.</Alert>
      </AuthLayout>
    );
  }
  if (session.data) {
    const sameAccount = session.data.user.email.toLowerCase() === invite.email.toLowerCase();
    return (
      <AuthLayout title={title} subtitle={subtitle}>
        {!sameAccount && (
          <Alert tone="warning">
            You’re signed in as {session.data.user.email}. Sign out and sign in as {invite.email} to accept.
          </Alert>
        )}
        {accept.isError && <Alert tone="danger">{describeError(accept.error)}</Alert>}
        <Button variant="primary" block disabled={!sameAccount} loading={accept.isPending} onClick={() => { accept.mutate(); }}>
          Accept invitation
        </Button>
      </AuthLayout>
    );
  }
  if (invite.accountExists) {
    return (
      <AuthLayout title={title} subtitle={subtitle}>
        <Alert tone="info">You already have an account. Sign in, then open this link again to accept.</Alert>
        <Link to="/login" search={{ redirect: `/invite?token=${encodeURIComponent(token)}` }}>
          Sign in to accept
        </Link>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title={title} subtitle={subtitle}>
      {register.isError && <Alert tone="danger">{describeError(register.error)}</Alert>}
      <form className={authFormClass} noValidate onSubmit={(e) => void form.handleSubmit((v) => { register.mutate(v); })(e)}>
        <TextField label="Your name" autoComplete="name" error={form.formState.errors.displayName?.message} {...form.register('displayName')} />
        <PasswordField
          label="Choose a password"
          autoComplete="new-password"
          hint="At least 12 characters."
          error={form.formState.errors.password?.message}
          {...form.register('password')}
        />
        <PasswordField label="Confirm password" autoComplete="new-password" error={form.formState.errors.confirm?.message} {...form.register('confirm')} />
        <ConsentCheckbox error={form.formState.errors.acceptTerms?.message} {...form.register('acceptTerms')} />
        <Button type="submit" variant="primary" block loading={register.isPending}>
          Create account and join
        </Button>
      </form>
    </AuthLayout>
  );
}

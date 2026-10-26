import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Building2, KeyRound, LockKeyhole, ShieldCheck, Sparkles, UserRound, type LucideIcon } from 'lucide-react';
import { useState, type ReactNode, type SyntheticEvent } from 'react';
import { describeError, fieldErrors } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { PasswordField } from '../../ui/PasswordField';
import { TextField } from '../../ui/TextField';
import { slugify } from '../orgs/OrgPages';
import { ConsentCheckbox } from '../legal/ConsentCheckbox';
import { AuthLayout, authFormClass, type ShowcaseContent } from './AuthLayout';
import { login, sessionQueryKey } from './session';
import { completeSetup, setupStatusQueryOptions } from './setupApi';
import styles from './SetupPage.module.css';

/** The showcase beside the form walks through what setup does, one point per form step. */
const SETUP_SHOWCASE: ShowcaseContent = {
  tagline: (
    <>
      Three steps to your first <em>workspace</em>.
    </>
  ),
  lede: 'Prove you run this server, create the administrator, and name the organization your team will work in.',
  points: [
    { Icon: KeyRound, text: 'Verify ownership with the server’s setup code' },
    { Icon: UserRound, text: 'Create the administrator account' },
    { Icon: Building2, text: 'Name your first organization and its address' },
  ],
};

interface SetupStepProps {
  id: string;
  step: number;
  Icon: LucideIcon;
  title: string;
  description: string;
  children: ReactNode;
}

/** One numbered group of the setup form. */
function SetupStep({ id, step, Icon, title, description, children }: SetupStepProps) {
  return (
    <section className={styles.step} aria-labelledby={id}>
      <div className={styles.stepHeader}>
        <span className={styles.stepIcon} aria-hidden="true">
          <Icon size={16} />
        </span>
        <div className={styles.stepText}>
          <p className={styles.stepNumber}>Step {step} of 3</p>
          <h2 id={id} className={styles.stepTitle}>
            {title}
          </h2>
          <p className={styles.stepDescription}>{description}</p>
        </div>
      </div>
      <div className={styles.stepFields}>{children}</div>
    </section>
  );
}

export function SetupPage() {
  const [setupCode, setSetupCode] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [confirmTouched, setConfirmTouched] = useState(false);
  const [clientPasswordError, setClientPasswordError] = useState<string | null>(null);
  const [orgName, setOrgName] = useState('');
  const [orgSlug, setOrgSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [acceptTerms, setAcceptTerms] = useState(false);

  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const setupMutation = useMutation({
    mutationFn: async () => {
      const result = await completeSetup({
        setupCode: setupCode.trim(),
        displayName: displayName.trim(),
        email: email.trim(),
        password,
        organizationName: orgName.trim(),
        organizationSlug: orgSlug.trim() || undefined,
        acceptTerms,
      });

      // Automatically sign in the newly created administrator
      const session = await login({ email: email.trim(), password });
      queryClient.setQueryData(sessionQueryKey, session);
      await queryClient.invalidateQueries({ queryKey: setupStatusQueryOptions.queryKey });
      return result;
    },
    onSuccess: async (data) => {
      await navigate({ to: '/$org', params: { org: data.organizationSlug } });
    },
  });

  const errors = fieldErrors(setupMutation.error);
  const generalError = setupMutation.error ? describeError(setupMutation.error) : null;

  const handleSubmit = (e: SyntheticEvent) => {
    e.preventDefault();
    if (password !== confirmPassword) {
      setClientPasswordError('The passwords don’t match.');
      return;
    }
    setClientPasswordError(null);
    setupMutation.mutate();
  };

  return (
    <AuthLayout
      wide
      showcase={SETUP_SHOWCASE}
      greeting={
        <>
          <Sparkles size={15} aria-hidden="true" />
          First-run setup
        </>
      }
      title="Welcome to JTRAC"
      subtitle="Create the administrator account and your first organization. This page is only available until setup is complete."
      note={
        <>
          <LockKeyhole size={13} aria-hidden="true" />
          <span>The setup code lives on the server, so only someone who runs this installation can finish setup. It stops working once setup is done.</span>
        </>
      }
    >
        {generalError && <Alert tone="danger">{generalError}</Alert>}

        <form onSubmit={handleSubmit} className={authFormClass} noValidate>
          <SetupStep id="sec-code" step={1} Icon={KeyRound} title="Verify the server" description="Proves you run this installation.">
            <TextField
              label="Setup code"
              hint="Check setup-code.txt in the server data folder to verify server ownership."
              placeholder="ABCD-EFGH-JKMN"
              required
              maxLength={64}
              value={setupCode}
              error={errors.setupCode}
              className={styles.codeField}
              autoCapitalize="characters"
              autoCorrect="off"
              spellCheck={false}
              onChange={(e) => {
                setSetupCode(e.target.value);
              }}
            />
          </SetupStep>

          <SetupStep id="sec-admin" step={2} Icon={UserRound} title="Administrator account" description="You’ll sign in with these details.">
            <TextField
              label="Display name"
              placeholder="e.g. Admin User"
              required
              maxLength={120}
              value={displayName}
              error={errors.displayName}
              onChange={(e) => {
                setDisplayName(e.target.value);
              }}
            />
            <TextField
              label="Email address"
              type="email"
              placeholder="admin@example.com"
              required
              maxLength={320}
              value={email}
              error={errors.email}
              onChange={(e) => {
                setEmail(e.target.value);
              }}
            />
            <PasswordField
              label="Password"
              hint="At least 12 characters."
              autoComplete="new-password"
              required
              minLength={12}
              maxLength={128}
              value={password}
              error={errors.password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (clientPasswordError && e.target.value === confirmPassword) {
                  setClientPasswordError(null);
                }
              }}
            />
            <PasswordField
              label="Confirm password"
              autoComplete="new-password"
              required
              minLength={12}
              maxLength={128}
              value={confirmPassword}
              error={clientPasswordError ?? (confirmTouched && confirmPassword && password !== confirmPassword ? 'The passwords don’t match.' : undefined)}
              onBlur={() => {
                setConfirmTouched(true);
              }}
              onChange={(e) => {
                setConfirmPassword(e.target.value);
                if (clientPasswordError && e.target.value === password) {
                  setClientPasswordError(null);
                }
              }}
            />
          </SetupStep>

          <SetupStep id="sec-org" step={3} Icon={Building2} title="First organization" description="Where your team’s projects will live.">
            <TextField
              label="Organization name"
              placeholder="e.g. Acme Corp"
              required
              maxLength={80}
              value={orgName}
              error={errors.organizationName}
              onChange={(e) => {
                const val = e.target.value;
                setOrgName(val);
                if (!slugEdited) {
                  setOrgSlug(slugify(val));
                }
              }}
            />
            <TextField
              label="Organization URL slug"
              hint={`Your workspace address will be /${orgSlug || 'acme'}`}
              placeholder="acme"
              maxLength={40}
              value={orgSlug}
              error={errors.organizationSlug}
              onChange={(e) => {
                setSlugEdited(true);
                setOrgSlug(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, ''));
              }}
            />
          </SetupStep>

          <ConsentCheckbox
            checked={acceptTerms}
            error={errors.acceptTerms}
            onChange={(e) => {
              setAcceptTerms(e.target.checked);
            }}
          />

          <div className={styles.actions}>
            <Button
              type="submit"
              variant="primary"
              block
              loading={setupMutation.isPending}
              disabled={!setupCode.trim() || !displayName.trim() || !email.trim() || !password || !confirmPassword || password !== confirmPassword || !orgName.trim() || !acceptTerms}
              icon={<ShieldCheck size={18} aria-hidden="true" />}
            >
              {setupMutation.isPending ? 'Setting up…' : 'Complete Setup & Start'}
            </Button>
          </div>
        </form>
    </AuthLayout>
  );
}

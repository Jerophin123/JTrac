import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { Building2, Download, FileText, KeyRound, Monitor, Moon, Palette, Plus, Scale, ShieldCheck, Sun, Trash2, UserRound } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, orgsQuery, profileQuery } from '../../api/queries';
import { describeError, fieldErrors } from '../../lib/errors';
import { downloadBlob } from '../../lib/download';
import { formatDate, timeAgo } from '../../lib/format';
import { formatOrgRole } from '../../lib/roles';
import { useTheme, type ThemePreference } from '../../lib/theme';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Input, Select, TextArea } from '../../ui/Field';
import { OrgMark } from '../../ui/OrgMark';
import { PasswordField } from '../../ui/PasswordField';
import { Skeleton } from '../../ui/Page';
import { SaveBar, SettingsCard, SettingsEmpty, SettingsLayout, SettingsList, SettingsListItem, SettingsSection } from '../../ui/Settings';
import { Switch } from '../../ui/Switch';
import { useToast } from '../../ui/Toast';
import { sessionQueryKey, sessionQueryOptions, useCanCreateOrganizations } from '../auth/session';
import { formatLegalDate, GrievanceEmail, useLegalInfo } from '../legal/legal';
import styles from './AccountPages.module.css';

function AccountLayout({ children }: { children: ReactNode }) {
  const { data: session } = useQuery(sessionQueryOptions);
  return (
    <SettingsLayout
      title="Your account"
      navLabel="Account settings"
      context={
        session && (
          <>
            <Avatar name={session.user.displayName} />
            <strong className={styles.contextName}>{session.user.displayName}</strong>
            <span>@{session.user.handle}</span>
          </>
        )
      }
      groups={[
        {
          label: 'Account',
          items: [
            { label: 'Profile', icon: <UserRound size={16} />, link: { to: '/account' }, exact: true },
            { label: 'API tokens', icon: <KeyRound size={16} />, link: { to: '/account/tokens' } },
            { label: 'Privacy & data', icon: <ShieldCheck size={16} />, link: { to: '/account/privacy' } },
            { label: 'Appearance', icon: <Palette size={16} />, link: { to: '/account/appearance' } },
          ],
        },
      ]}
    >
      {children}
    </SettingsLayout>
  );
}

export function ProfilePage() {
  const profile = useQuery(profileQuery);
  return (
    <AccountLayout>
      {profile.isPending && <Skeleton lines={4} />}
      {profile.isError && <Alert tone="danger">{describeError(profile.error)}</Alert>}
      {profile.data && <ProfileForm profile={profile.data} />}
      <UserOrganizations />
      <PasswordForm />
    </AccountLayout>
  );
}

function UserOrganizations() {
  const { data: orgs, isPending, isError, error } = useQuery(orgsQuery);
  const navigate = useNavigate();
  const userOrgs = orgs ?? [];
  const canCreateOrgs = useCanCreateOrganizations();
  return (
    <SettingsSection
      title="Organizations"
      description="Organizations you belong to, and your role in each."
    >
      {isPending && <Skeleton lines={3} />}
      {isError && <Alert tone="danger">{describeError(error)}</Alert>}
      {orgs !== undefined && (
        <SettingsCard
          flush
          footer={
            canCreateOrgs ? (
              <Button size="sm" icon={<Plus size={14} aria-hidden="true" />} onClick={() => void navigate({ to: '/new' })}>
                New organization
              </Button>
            ) : undefined
          }
        >
          {userOrgs.length === 0 ? (
            <SettingsEmpty>
              <Building2 size={16} aria-hidden="true" className={styles.inlineIcon} /> You are not part of any organization yet.{' '}
              {canCreateOrgs ? 'Create one or ask an administrator for an invitation.' : 'Ask an administrator for an invitation.'}
            </SettingsEmpty>
          ) : (
            <SettingsList label="Your organizations">
              {userOrgs.map((org) => (
                <SettingsListItem
                  key={org.id}
                  leading={<OrgMark name={org.name} logoUrl={org.logoUrl} color={org.color} size="md" />}
                  title={
                    <>
                      {org.name}
                      <Badge tone={org.role === 'OWNER' || org.role === 'ADMIN' ? 'accent' : 'neutral'}>{formatOrgRole(org.role)}</Badge>
                    </>
                  }
                  meta={`@${org.slug}`}
                >
                  <Link to="/$org" params={{ org: org.slug }} className={styles.openLink}>
                    Open
                  </Link>
                </SettingsListItem>
              ))}
            </SettingsList>
          )}
        </SettingsCard>
      )}
    </SettingsSection>
  );
}

function ProfileForm({ profile }: { profile: { displayName: string; handle: string; email: string; emailVerified: boolean } }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [displayName, setDisplayName] = useState(profile.displayName);
  const [handle, setHandle] = useState(profile.handle);
  const save = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/me', { body: { displayName: displayName.trim(), handle: handle.trim() } })),
    onSuccess: async (saved) => {
      queryClient.setQueryData(keys.profile, saved);
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
      toast.success('Profile saved');
    },
  });
  const errors = fieldErrors(save.error);
  const dirty = displayName !== profile.displayName || handle !== profile.handle;
  return (
    <form className={styles.form} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <SettingsSection title="Profile" description="How you appear to others in comments, mentions and assignments.">
        <SettingsCard>
          {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}
          <div className={styles.identity}>
            <span className={styles.bigAvatar}>
              <Avatar name={displayName || profile.displayName} />
            </span>
            <div className={styles.identityText}>
              <strong>{displayName || profile.displayName}</strong>
              <span className="muted">@{handle || profile.handle}</span>
            </div>
          </div>
          <div className="form-grid">
            <Input label="Name" maxLength={120} value={displayName} error={errors.displayName} onChange={(e) => { setDisplayName(e.target.value); }} />
            <Input label="Handle" maxLength={32} value={handle} error={errors.handle} hint="Others mention you as @handle." onChange={(e) => { setHandle(e.target.value.toLowerCase()); }} />
          </div>
          <Input label="Email" value={profile.email} disabled hint={profile.emailVerified ? 'Confirmed' : 'Not confirmed yet'} />
        </SettingsCard>
      </SettingsSection>
      <SaveBar
        dirty={dirty}
        saving={save.isPending}
        saveLabel="Save profile"
        onDiscard={() => {
          setDisplayName(profile.displayName);
          setHandle(profile.handle);
        }}
      />
    </form>
  );
}

function PasswordForm() {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const change = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/me/password', { body: { currentPassword: current, newPassword: next } })),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setConfirm('');
      toast.success('Password changed. Your other sessions were signed out.');
    },
  });
  const errors = fieldErrors(change.error);
  const mismatch = confirm.length > 0 && confirm !== next;
  return (
    <SettingsSection title="Password" description="Changing it signs you out everywhere else. Use at least 12 characters.">
      <form onSubmit={(e) => { e.preventDefault(); if (!mismatch) { change.mutate(); } }}>
        <SettingsCard
          footer={
            <Button type="submit" variant="primary" size="sm" loading={change.isPending} disabled={!current || next.length < 12 || mismatch}>
              Change password
            </Button>
          }
        >
          {change.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(change.error)}</Alert>}
          <PasswordField label="Current password" autoComplete="current-password" value={current} error={errors.currentPassword} onChange={(e) => { setCurrent(e.target.value); }} />
          <div className="form-grid">
            <PasswordField label="New password" autoComplete="new-password" hint="At least 12 characters." value={next} error={errors.newPassword} onChange={(e) => { setNext(e.target.value); }} />
            <PasswordField label="Confirm new password" autoComplete="new-password" value={confirm} error={mismatch ? 'The passwords don’t match.' : undefined} onChange={(e) => { setConfirm(e.target.value); }} />
          </div>
        </SettingsCard>
      </form>
    </SettingsSection>
  );
}

const THEME_OPTIONS: { value: ThemePreference; label: string; description: string; Icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', description: 'Warm paper tones', Icon: Sun },
  { value: 'dark', label: 'Dark', description: 'Easy on the eyes at night', Icon: Moon },
  { value: 'system', label: 'System', description: 'Follows your device', Icon: Monitor },
];

export function AppearancePage() {
  const { preference, setPreference } = useTheme();
  return (
    <AccountLayout>
      <SettingsSection title="Theme" description="Choose how JTRAC looks to you. Saved on this device only.">
        <SettingsCard>
          <fieldset className={styles.themeChoices}>
            <legend className="visually-hidden">Theme</legend>
            {THEME_OPTIONS.map(({ value, label, description, Icon }) => (
              <label key={value} className={styles.themeChoice}>
                <input
                  type="radio"
                  name="jtrac-theme-preference"
                  value={value}
                  checked={preference === value}
                  onChange={() => {
                    setPreference(value);
                  }}
                  className={styles.themeInput}
                />
                <span className={styles.themePreview} data-preview={value} aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </span>
                <span className={styles.themeLabel}>
                  <Icon size={15} aria-hidden="true" />
                  {label}
                </span>
                <span className={styles.themeDescription}>{description}</span>
              </label>
            ))}
          </fieldset>
        </SettingsCard>
      </SettingsSection>
    </AccountLayout>
  );
}

export function TokensPage() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const tokens = useQuery({ queryKey: keys.tokens, queryFn: () => call(() => api.GET('/api/v1/me/tokens')) });
  const orgs = useQuery(orgsQuery);
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);
  const revoke = useMutation({
    mutationFn: (id: string) => call(() => api.DELETE('/api/v1/me/tokens/{id}', { params: { path: { id } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.tokens }),
    onError: (e) => { toast.error(describeError(e)); },
  });
  return (
    <AccountLayout>
      <SettingsSection
        title="API tokens"
        layout="stacked"
        description={
          <>
            Tokens let scripts call the JTRAC API as you: <code>Authorization: Bearer jtrac_…</code>. They have your permissions (optionally read-only or
            limited to one organization). Treat them like passwords.
          </>
        }
        actions={
          <Button variant="primary" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setCreating(true); }}>
            New token
          </Button>
        }
      >
        {tokens.isPending && <Skeleton lines={3} />}
        {tokens.isError && <Alert tone="danger">{describeError(tokens.error)}</Alert>}
        {tokens.data && (
          <SettingsCard flush>
            {tokens.data.length === 0 ? (
              <SettingsEmpty>No tokens yet.</SettingsEmpty>
            ) : (
              <SettingsList label="API tokens">
                {tokens.data.map((t) => (
                  <SettingsListItem
                    key={t.id}
                    leading={
                      <span className={styles.tokenMark}>
                        <KeyRound size={16} aria-hidden="true" />
                      </span>
                    }
                    title={
                      <>
                        {t.name} <code className={styles.prefix}>{t.prefix}…</code>
                        {t.scopes.includes('write') ? <Badge tone="warning">read & write</Badge> : <Badge>read-only</Badge>}
                      </>
                    }
                    meta={
                      <>
                        Created {timeAgo(t.createdAt)} · {t.lastUsedAt ? `last used ${timeAgo(t.lastUsedAt)}` : 'never used'}
                        {t.expiresAt ? ` · expires ${formatDate(t.expiresAt)}` : ''}
                        {t.organizationId ? ` · ${orgs.data?.find((o) => o.id === t.organizationId)?.name ?? 'one organization'}` : ''}
                      </>
                    }
                  >
                    <Button size="sm" variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { revoke.mutate(t.id); }}>
                      Revoke
                    </Button>
                  </SettingsListItem>
                ))}
              </SettingsList>
            )}
          </SettingsCard>
        )}
      </SettingsSection>
      {creating && (
        <CreateTokenDialog
          onClose={() => { setCreating(false); }}
          onCreated={(value) => {
            setCreating(false);
            setSecret(value);
          }}
        />
      )}
      <Dialog open={secret !== null} size="sm" title="Your new token" description="Copy it now. It will not be shown again." onClose={() => { setSecret(null); }}>
        <Input label="Token" readOnly value={secret ?? ''} onFocus={(e) => { e.target.select(); }} />
      </Dialog>
    </AccountLayout>
  );
}

function CreateTokenDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (secret: string) => void }) {
  const queryClient = useQueryClient();
  const orgs = useQuery(orgsQuery);
  const [name, setName] = useState('');
  const [write, setWrite] = useState(false);
  const [organizationId, setOrganizationId] = useState('');
  const [expires, setExpires] = useState('90');
  const create = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/me/tokens', {
          body: {
            name: name.trim(),
            scopes: write ? ['read', 'write'] : ['read'],
            organizationId: organizationId || null,
            expiresAt: expires ? new Date(Date.now() + Number(expires) * 86_400_000).toISOString() : null,
          },
        }),
      ),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: keys.tokens });
      onCreated(result.secret);
    },
  });
  return (
    <Dialog
      open
      size="sm"
      title="New API token"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={create.isPending} disabled={!name.trim()} onClick={() => { create.mutate(); }}>
            Create token
          </Button>
        </>
      }
    >
      {create.isError && <Alert tone="danger">{describeError(create.error)}</Alert>}
      <Input label="Name" maxLength={80} placeholder="e.g. CI pipeline" value={name} onChange={(e) => { setName(e.target.value); }} />
      <Switch label="Allow changes (write access)" checked={write} onChange={(e) => { setWrite(e.target.checked); }} />
      <p className={styles.hint}>Without write access, the token can only read.</p>
      <Select label="Organization" value={organizationId} onChange={(e) => { setOrganizationId(e.target.value); }}>
        <option value="">All my organizations</option>
        {(orgs.data ?? []).map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </Select>
      <Select label="Expires" value={expires} onChange={(e) => { setExpires(e.target.value); }}>
        <option value="30">In 30 days</option>
        <option value="90">In 90 days</option>
        <option value="365">In a year</option>
        <option value="">Never</option>
      </Select>
    </Dialog>
  );
}

// --- privacy & data (DPDP Act: notice, access, withdrawal, grievance) ------------------------

const consentQuery = { queryKey: ['me', 'consent'] as const, queryFn: () => call(() => api.GET('/api/v1/me/consent')) };

export function PrivacyPage() {
  const consent = useQuery(consentQuery);
  const { legal } = useLegalInfo();
  return (
    <AccountLayout>
      <SettingsSection title="Your consent" description="What you agreed to, and when. JTRAC asks again whenever the documents change.">
        <SettingsCard>
          {consent.isPending && <Skeleton lines={2} />}
          {consent.isError && <Alert tone="danger">{describeError(consent.error)}</Alert>}
          {consent.data && (
            <>
              <p>
                {consent.data.accepted ? (
                  <>
                    <Badge tone="success">Accepted</Badge> You accepted version {consent.data.currentVersion} of the Terms of Service
                    and Privacy Notice.
                  </>
                ) : (
                  <>
                    <Badge tone="warning">Not accepted</Badge> You haven’t accepted the current version ({consent.data.currentVersion}).
                  </>
                )}
              </p>
              {consent.data.history.length > 0 && (
                <ul className={styles.consentHistory} aria-label="Consent history">
                  {consent.data.history.map((record) => (
                    <li key={`${record.version}-${record.givenAt}`}>
                      <span>Version {record.version}</span>
                      <span className="muted">
                        Accepted {formatDate(record.givenAt)}
                        {record.withdrawnAt ? ` · withdrawn ${formatDate(record.withdrawnAt)}` : ''}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          <div className={styles.legalLinks}>
            <Link to="/legal/terms">
              <FileText size={15} aria-hidden="true" /> Terms of Service
            </Link>
            <Link to="/legal/privacy">
              <ShieldCheck size={15} aria-hidden="true" /> Privacy Notice
            </Link>
            <Link to="/legal/grievance">
              <Scale size={15} aria-hidden="true" /> Grievance Redressal
            </Link>
          </div>
          {legal && (
            <p className={styles.hint}>
              Current version {legal.version}, effective {formatLegalDate(legal.effectiveDate)}.
            </p>
          )}
        </SettingsCard>
      </SettingsSection>

      <DataDownload />

      <SettingsSection title="Questions and complaints" description="Ask about your data, correct it, or raise a grievance.">
        <SettingsCard>
          <p>
            Write to the grievance officer
            {legal ? (
              <>
                {' '}
                at <GrievanceEmail legal={legal} />
              </>
            ) : null}{' '}
            from your account’s email address. You can also nominate someone to act for you. See{' '}
            <Link to="/legal/grievance">Grievance Redressal</Link> for timelines and how to escalate to the Data Protection Board of
            India.
          </p>
        </SettingsCard>
      </SettingsSection>

      <WithdrawConsent />
    </AccountLayout>
  );
}

/** Right to access (DPDP Act, section 11): a copy of what JTRAC holds about the account. */
function DataDownload() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const download = useMutation({
    mutationFn: async () => {
      const [profile, orgs, consent] = await Promise.all([
        queryClient.query(profileQuery),
        queryClient.query(orgsQuery),
        queryClient.query(consentQuery),
      ]);
      const tokens = await call(() => api.GET('/api/v1/me/tokens'));
      return {
        exportedAt: new Date().toISOString(),
        about:
          'Personal data JTRAC holds about your account. Content you created inside organizations (issues, comments, attachments) can be exported from each organization.',
        profile,
        organizations: orgs.map((o) => ({ name: o.name, slug: o.slug, role: o.role })),
        consent,
        apiTokens: tokens.map((t) => ({ name: t.name, scopes: t.scopes, createdAt: t.createdAt, lastUsedAt: t.lastUsedAt, expiresAt: t.expiresAt })),
      };
    },
    onSuccess: (data) => {
      downloadBlob(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), `jtrac-my-data-${data.exportedAt.slice(0, 10)}.json`);
      toast.success('Your data was downloaded');
    },
  });
  return (
    <SettingsSection title="Your data" description="Download a copy of the personal data on your account, as a JSON file.">
      <SettingsCard>
        {download.isError && <Alert tone="danger">{describeError(download.error)}</Alert>}
        <p>Includes your profile, organization memberships and roles, consent history and API token details (never the tokens themselves).</p>
        <div>
          <Button icon={<Download size={15} aria-hidden="true" />} loading={download.isPending} onClick={() => { download.mutate(); }}>
            Download my data
          </Button>
        </div>
      </SettingsCard>
    </SettingsSection>
  );
}

/** Withdrawal of consent (DPDP Act, section 6(4)), which also files an erasure request. */
function WithdrawConsent() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const withdraw = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/me/consent/withdraw', { body: { reason: reason.trim() || null } })),
    onSuccess: async () => {
      setOpen(false);
      await queryClient.invalidateQueries({ queryKey: ['me', 'consent'] });
      await queryClient.invalidateQueries({ queryKey: sessionQueryKey });
    },
  });
  return (
    <SettingsSection
      tone="danger"
      title="Withdraw consent"
      description="Stop JTRAC processing your personal data and ask for your account to be erased."
    >
      <SettingsCard>
        <p>
          You won’t be able to use JTRAC after withdrawing. The grievance officer receives an erasure request and replies by
          email; until your data is erased you can change your mind by signing in and accepting the terms again.
        </p>
        <div>
          <Button variant="danger" icon={<Trash2 size={15} aria-hidden="true" />} onClick={() => { setOpen(true); }}>
            Withdraw consent…
          </Button>
        </div>
      </SettingsCard>
      <Dialog
        open={open}
        size="sm"
        title="Withdraw consent?"
        description="Your erasure request goes to the grievance officer. You’ll get an email confirming it."
        onClose={() => { setOpen(false); }}
        footer={
          <>
            <Button variant="ghost" onClick={() => { setOpen(false); }}>
              Cancel
            </Button>
            <Button variant="danger" loading={withdraw.isPending} onClick={() => { withdraw.mutate(); }}>
              Withdraw and request erasure
            </Button>
          </>
        }
      >
        {withdraw.isError && <Alert tone="danger">{describeError(withdraw.error)}</Alert>}
        <TextArea
          label="Reason (optional)"
          maxLength={1000}
          rows={3}
          value={reason}
          onChange={(e) => { setReason(e.target.value); }}
        />
      </Dialog>
    </SettingsSection>
  );
}

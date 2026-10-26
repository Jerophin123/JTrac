import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CircleAlert, CircleCheck, CircleMinus, CircleX, Mail, Send, Server } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import type { components } from '../../api/schema';
import { describeError, fieldErrors } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Input, Select } from '../../ui/Field';
import { Skeleton } from '../../ui/Page';
import { PasswordField } from '../../ui/PasswordField';
import { SaveBar, SettingRow, SettingsCard, SettingsLayout, SettingsSection } from '../../ui/Settings';
import { Switch } from '../../ui/Switch';
import { useToast } from '../../ui/Toast';
import { publicConfigQueryOptions, sessionQueryOptions } from '../auth/session';
import styles from './ServerSettingsPage.module.css';

type SmtpSettings = components['schemas']['SmtpSettings'];
type TlsMode = SmtpSettings['tls'];
type TestResult = components['schemas']['SmtpTestResult'];

const mailQuery = { queryKey: ['system', 'mail'] as const, queryFn: () => call(() => api.GET('/api/v1/system/mail')) };
const infoQuery = { queryKey: ['system', 'info'] as const, queryFn: () => call(() => api.GET('/api/v1/system/info')) };

const DEFAULT_PORT: Record<TlsMode, number> = { STARTTLS: 587, IMPLICIT: 465, NONE: 25 };

/** Server-wide settings for server administrators; the server enforces who may use them. */
export function ServerSettingsPage() {
  const info = useQuery(infoQuery);
  const mail = useQuery(mailQuery);
  return (
    <SettingsLayout
      title="Server settings"
      navLabel="Server settings"
      context={<span>Applies to everyone on this JTRAC server</span>}
      groups={[{ label: 'Server', items: [{ label: 'Outgoing email', icon: <Mail size={16} />, link: { to: '/server/settings' }, exact: true }] }]}
    >
      <SettingsSection title="About this server" description="The address used in links in emails, and the software version.">
        <SettingsCard>
          {info.isPending && <Skeleton lines={2} />}
          {info.isError && <Alert tone="danger">{describeError(info.error)}</Alert>}
          {info.data && (
            <dl className={styles.facts}>
              <dt>
                <Server size={14} aria-hidden="true" /> Version
              </dt>
              <dd>{info.data.version}</dd>
              <dt>Public address</dt>
              <dd>{info.data.publicUrl}</dd>
              <dt>Java runtime</dt>
              <dd>{info.data.javaVersion}</dd>
            </dl>
          )}
          <p className={styles.hint}>Change the address, port or database with the <code>jtrac configure</code> command on the server.</p>
        </SettingsCard>
      </SettingsSection>
      {mail.isPending && <Skeleton lines={6} />}
      {mail.isError && <Alert tone="danger">{describeError(mail.error)}</Alert>}
      {mail.data && <MailSettingsForm view={mail.data} />}
    </SettingsLayout>
  );
}

function MailSettingsForm({ view }: { view: components['schemas']['MailSettingsView'] }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const session = useQuery(sessionQueryOptions);
  const [form, setForm] = useState<SmtpSettings>(view.settings);
  const [password, setPassword] = useState('');
  const [recipientInput, setRecipient] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<components['schemas']['SettingWarning'][]>([]);

  // Until the administrator types an address, tests go to their own.
  const recipient = recipientInput ?? session.data?.user.email ?? '';

  const save = useMutation({
    mutationFn: () => call(() => api.PUT('/api/v1/system/mail', { body: { settings: form, password: password === '' ? null : password } })),
    onSuccess: async (result) => {
      setPassword('');
      setWarnings(result.warnings.filter((w) => w.field !== 'smtp.enabled'));
      queryClient.setQueryData(mailQuery.queryKey, result.mail);
      await queryClient.invalidateQueries({ queryKey: publicConfigQueryOptions.queryKey });
      toast.success(result.mail.settings.enabled ? 'Email settings saved and applied' : 'Email turned off: messages will wait in the queue');
    },
  });
  const test = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/system/mail/test', { body: { settings: { ...form, enabled: true }, password: password === '' ? null : password, recipient } }),
      ),
  });

  const errors = fieldErrors(save.error);
  const set = <K extends keyof SmtpSettings>(key: K, value: SmtpSettings[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
  };
  const dirty = JSON.stringify(form) !== JSON.stringify(view.settings) || password !== '';

  return (
    <form
      className={styles.form}
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        save.mutate();
      }}
    >
      <SettingsSection
        title="Outgoing email"
        description="Invitations, password resets and notifications are sent through your SMTP server. Any standard provider works."
      >
        <SettingsCard>
          {view.overriddenBy.length > 0 && (
            <Alert tone="warning" title="Some settings are fixed by the server environment">
              Environment variables override these values: {view.overriddenBy.join(', ')}. Changes you save here take effect only
              after those variables are removed.
            </Alert>
          )}
          {!form.enabled && (
            <Alert tone="warning" title="Email is off">
              Invitations, password resets and notifications are queued but not sent. Turn email on and save to deliver them.
            </Alert>
          )}
          {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}
          <SettingRow label="Send email" description="Off keeps messages waiting in the queue (email pending).">
            <Switch label="Send email" hideLabel checked={form.enabled} onChange={(e) => { set('enabled', e.target.checked); }} />
          </SettingRow>
          <div className="form-grid">
            <Input
              label="SMTP server"
              placeholder="smtp.your-provider.com"
              hint="Use the host name your email provider lists for SMTP (it can differ by region or plan)."
              value={form.host}
              error={errors['smtp.host']}
              autoComplete="off"
              spellCheck={false}
              onChange={(e) => { set('host', e.target.value); }}
            />
            <Input
              label="Port"
              type="number"
              min={1}
              max={65535}
              value={form.port}
              error={errors['smtp.port']}
              hint="587 for STARTTLS, 465 for implicit TLS, 25 only for a relay."
              onChange={(e) => { set('port', Number(e.target.value)); }}
            />
          </div>
          <Select
            label="Encryption"
            value={form.tls}
            error={errors['smtp.tls']}
            hint="Certificates are always verified."
            onChange={(e) => {
              const next = e.target.value as TlsMode;
              setForm((current) => ({
                ...current,
                tls: next,
                port: current.port === DEFAULT_PORT[current.tls] ? DEFAULT_PORT[next] : current.port,
              }));
            }}
          >
            <option value="STARTTLS">STARTTLS (usually port 587)</option>
            <option value="IMPLICIT">Implicit TLS / SSL (usually port 465)</option>
            <option value="NONE">None: trusted relay on a private network only</option>
          </Select>
          <SettingRow label="Sign in to the SMTP server" description="Required by public providers. Turn off only for a trusted relay.">
            <Switch label="Sign in to the SMTP server" hideLabel checked={form.auth} onChange={(e) => { set('auth', e.target.checked); }} />
          </SettingRow>
          {form.auth && (
            <div className="form-grid">
              <Input
                label="User name"
                value={form.username}
                autoComplete="off"
                spellCheck={false}
                error={errors['smtp.username']}
                onChange={(e) => { set('username', e.target.value); }}
              />
              <PasswordField
                label="Password"
                autoComplete="new-password"
                value={password}
                placeholder={view.passwordSet ? 'Saved (leave blank to keep)' : ''}
                hint="Some providers require an app-specific password."
                error={errors['smtp.password']}
                onChange={(e) => { setPassword(e.target.value); }}
              />
            </div>
          )}
          <div className="form-grid">
            <Input
              label="From address"
              type="email"
              placeholder="jtrac@your-domain.com"
              value={form.fromAddress}
              error={errors['smtp.fromAddress']}
              hint="Providers usually only allow the signed-in mailbox or a verified alias."
              onChange={(e) => { set('fromAddress', e.target.value); }}
            />
            <Input label="From name" value={form.fromName} error={errors['smtp.fromName']} onChange={(e) => { set('fromName', e.target.value); }} />
          </div>
          <details className={styles.advanced}>
            <summary>Advanced</summary>
            <div className={styles.advancedBody}>
              <Input label="Reply-to address (optional)" type="email" value={form.replyTo} error={errors['smtp.replyTo']} onChange={(e) => { set('replyTo', e.target.value); }} />
              <div className="form-grid">
                <Input
                  label="Timeout (seconds)"
                  type="number"
                  min={1}
                  max={300}
                  value={form.timeoutSeconds}
                  error={errors['smtp.timeoutSeconds']}
                  onChange={(e) => { set('timeoutSeconds', Number(e.target.value)); }}
                />
                <Input
                  label="Delivery attempts"
                  type="number"
                  min={1}
                  max={50}
                  value={form.maxAttempts}
                  hint="Failed sends are retried with growing delays."
                  error={errors['smtp.maxAttempts']}
                  onChange={(e) => { set('maxAttempts', Number(e.target.value)); }}
                />
              </div>
              <Input
                label="Extra trusted CA certificates (optional)"
                placeholder="/path/to/ca.pem"
                hint="Absolute path on the server to a PEM file, for a relay with a certificate from a private CA."
                value={form.trustedCertificates}
                error={errors['smtp.trustedCertificates']}
                onChange={(e) => { set('trustedCertificates', e.target.value); }}
              />
            </div>
          </details>
          {warnings.length > 0 && (
            <Alert tone="warning" title="Saved, with warnings">
              <ul className={styles.warnings}>
                {warnings.map((w) => (
                  <li key={w.field + w.message}>{w.message}</li>
                ))}
              </ul>
            </Alert>
          )}
          <p className={styles.hint}>
            Saved to <code>{view.configDirectory}</code>; the password goes to a separate file only the server account can read.
          </p>
        </SettingsCard>
      </SettingsSection>

      <SettingsSection title="Test email" description="Sends a real message with the settings above (saved or not) and shows each step.">
        <SettingsCard>
          <div className={styles.testRow}>
            <Input label="Send to" type="email" value={recipient} onChange={(e) => { setRecipient(e.target.value); }} />
            <Button
              type="button"
              icon={<Send size={15} aria-hidden="true" />}
              loading={test.isPending}
              disabled={!recipient.trim() || !form.host.trim()}
              onClick={() => { test.mutate(); }}
            >
              Send test email
            </Button>
          </div>
          {test.isError && <Alert tone="danger">{describeError(test.error)}</Alert>}
          {test.data && <TestReport result={test.data} />}
        </SettingsCard>
      </SettingsSection>

      <SettingsSection title="Queue" description="Messages waiting, sent and failed.">
        <SettingsCard>
          <dl className={styles.stats}>
            <div>
              <dt>Waiting</dt>
              <dd>{view.outbox.pending}</dd>
            </div>
            <div>
              <dt>Sent in the last 24 h</dt>
              <dd>{view.outbox.sentLast24Hours}</dd>
            </div>
            <div>
              <dt>Failed</dt>
              <dd>{view.outbox.failed}</dd>
            </div>
          </dl>
          {view.outbox.lastError && (
            <p className={styles.hint}>
              Last delivery error: <code>{view.outbox.lastError}</code>
            </p>
          )}
        </SettingsCard>
      </SettingsSection>

      <SaveBar dirty={dirty} saving={save.isPending} saveLabel="Save email settings" onDiscard={() => { setForm(view.settings); setPassword(''); }} />
    </form>
  );
}

const STEP_ICON = {
  OK: <CircleCheck size={16} aria-hidden="true" />,
  WARNING: <CircleAlert size={16} aria-hidden="true" />,
  FAILED: <CircleX size={16} aria-hidden="true" />,
  SKIPPED: <CircleMinus size={16} aria-hidden="true" />,
} as const;

const STEP_LABEL = { OK: 'Passed', WARNING: 'Warning', FAILED: 'Failed', SKIPPED: 'Skipped' } as const;

function TestReport({ result }: { result: TestResult }) {
  return (
    <div className={styles.report} role="status">
      <p className={styles.reportSummary}>
        <Badge tone={result.accepted ? 'success' : 'danger'}>{result.accepted ? 'Accepted' : 'Not sent'}</Badge> {result.summary}
      </p>
      <ol className={styles.steps}>
        {result.steps.map((step, index) => (
          <li key={`${String(index)}-${step.name}`} className={styles.step} data-status={step.status}>
            <span className={styles.stepIcon} title={STEP_LABEL[step.status]}>
              {STEP_ICON[step.status]}
              <span className="visually-hidden">{STEP_LABEL[step.status]}:</span>
            </span>
            <span className={styles.stepText}>
              <strong>{step.name}</strong>
              <span>{step.detail}</span>
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Check, Copy, ExternalLink, Plus, RefreshCw, Settings2, ShieldAlert, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { orgsQuery } from '../../api/queries';
import type { AgentConnection, AgentConnectionOptions, AgentScope, UtcpActivity, UtcpToolSummary } from '../../api/types';
import { describeError } from '../../lib/errors';
import { formatDate, formatDateTime, pluralize, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Checkbox, Input, Select } from '../../ui/Field';
import { Skeleton } from '../../ui/Page';
import { SettingsCard, SettingsEmpty, SettingsList, SettingsListItem, SettingsSection } from '../../ui/Settings';
import { Segmented } from '../../ui/Tabs';
import { useToast } from '../../ui/Toast';
import { AccountLayout } from '../account/AccountPages';
import { agentGuides } from './agentSetup';
import styles from './AgentConnections.module.css';

export const agentKeys = {
  options: ['me', 'agent-connections', 'options'] as const,
  list: ['me', 'agent-connections'] as const,
  agents: (id: string) => ['me', 'agent-connections', id, 'agents'] as const,
  activity: (id: string) => ['me', 'agent-connections', id, 'activity'] as const,
};

export const utcpKeys = {
  options: ['me', 'utcp', 'options'] as const,
  settings: ['me', 'utcp', 'settings'] as const,
  activity: ['me', 'utcp', 'activity'] as const,
  tools: ['utcp', 'tools'] as const,
};

const optionsQuery = { queryKey: agentKeys.options, queryFn: () => call(() => api.GET('/api/v1/me/agent-connections/options')) };

const PROFILE_LABELS: Record<AgentConnection['profile'], string> = {
  ISSUE_READER: 'Issue reader',
  PROJECT_ANALYST: 'Project analyst',
  CODING_ASSISTANT: 'Coding assistant',
  ISSUE_CONTRIBUTOR: 'Issue contributor',
  CUSTOM: 'Custom',
};

export function AgentConnectionsContent() {
  const options = useQuery(optionsQuery);
  const connections = useQuery({ queryKey: agentKeys.list, queryFn: () => call(() => api.GET('/api/v1/me/agent-connections')) });
  const [creating, setCreating] = useState(false);
  const [managing, setManaging] = useState<string | null>(null);
  const managed = connections.data?.find((c) => c.id === managing) ?? null;
  const available = options.data?.available ?? false;
  return (
    <>
      <SettingsSection
        title="AI agent connections"
        layout="stacked"
        description={
          <>
            Let coding agents such as Claude Code, Codex, Gemini CLI, Antigravity or OpenCode read your JTRAC issues and, if you allow it, update them.
            Each connection has its own URL and works in one organization with your permissions. Agents sign in through your browser: never share
            your password or an API token with them.
          </>
        }
        actions={
          <Button variant="primary" icon={<Plus size={14} aria-hidden="true" />} disabled={!available} onClick={() => { setCreating(true); }}>
            New connection
          </Button>
        }
      >
        {options.data && !options.data.available && <Alert tone="warning">{options.data.unavailableReason}</Alert>}
        {(connections.isPending || options.isPending) && <Skeleton lines={3} />}
        {connections.isError && <Alert tone="danger">{describeError(connections.error)}</Alert>}
        {connections.data && (
          <SettingsCard flush>
            {connections.data.length === 0 ? (
              <SettingsEmpty>No connections yet. Create one, then add its URL to your agent.</SettingsEmpty>
            ) : (
              <SettingsList label="AI agent connections">
                {connections.data.map((c) => (
                  <SettingsListItem
                    key={c.id}
                    leading={
                      <span className={styles.mark}>
                        <Bot size={16} aria-hidden="true" />
                      </span>
                    }
                    title={
                      <>
                        {c.name} <StatusBadge connection={c} /> <Badge tone={c.scopes.some(isWrite) ? 'warning' : 'neutral'}>{PROFILE_LABELS[c.profile]}</Badge>
                      </>
                    }
                    meta={
                      <>
                        {c.organization.name} · created {timeAgo(c.createdAt)} ·{' '}
                        {c.lastUsedAt ? `last used ${timeAgo(c.lastUsedAt)}${c.lastClient ? ` by ${c.lastClient}` : ''}` : 'no agent has used it yet'}
                        {c.expiresAt ? ` · expires ${formatDate(c.expiresAt)}` : ''}
                      </>
                    }
                  >
                    <Button size="sm" icon={<Settings2 size={14} aria-hidden="true" />} onClick={() => { setManaging(c.id); }}>
                      Manage
                    </Button>
                  </SettingsListItem>
                ))}
              </SettingsList>
            )}
          </SettingsCard>
        )}
      </SettingsSection>
      <UtcpSection />
      {creating && options.data && (
        <CreateConnectionDialog
          options={options.data}
          onClose={() => { setCreating(false); }}
          onCreated={(id) => {
            setCreating(false);
            setManaging(id);
          }}
        />
      )}
      {managed && options.data && <ManageConnectionDialog connection={managed} options={options.data} onClose={() => { setManaging(null); }} />}
    </>
  );
}

export function AgentConnectionsPage() {
  return (
    <AccountLayout>
      <AgentConnectionsContent />
    </AccountLayout>
  );
}

const WRITE_SCOPES = new Set<AgentScope>(['issues:comment', 'issues:write', 'issues:transition', 'reports:write']);

function isWrite(scope: AgentScope): boolean {
  return WRITE_SCOPES.has(scope);
}

function StatusBadge({ connection }: { connection: AgentConnection }) {
  if (connection.status === 'REVOKED') return <Badge tone="danger">Revoked</Badge>;
  if (connection.status === 'EXPIRED') return <Badge tone="danger">Expired</Badge>;
  // "Connected" only on server-side evidence: an agent holds a valid sign-in.
  return connection.authorizedAgents > 0 ? <Badge tone="success">Connected</Badge> : <Badge tone="info">Waiting for an agent</Badge>;
}

export function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      size="sm"
      icon={copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
      aria-label={`${label}${copied ? ' (copied)' : ''}`}
      onClick={() => {
        void navigator.clipboard.writeText(value).then(() => {
          setCopied(true);
          window.setTimeout(() => { setCopied(false); }, 2000);
        });
      }}
    >
      {copied ? 'Copied' : 'Copy'}
    </Button>
  );
}

function ScopePicker({ options, value, onChange }: { options: AgentConnectionOptions; value: AgentScope[]; onChange: (next: AgentScope[]) => void }) {
  const toggle = (scope: AgentScope, on: boolean) => {
    onChange(on ? [...value, scope] : value.filter((s) => s !== scope));
  };
  const read = options.scopes.filter((s) => !s.write);
  const write = options.scopes.filter((s) => s.write);
  return (
    <div className={styles.scopes}>
      <fieldset className={styles.scopeGroup}>
        <legend>Read</legend>
        {read.map((s) => (
          <Checkbox key={s.name} label={s.name} hint={s.description} checked={value.includes(s.name as AgentScope)} onChange={(e) => { toggle(s.name as AgentScope, e.target.checked); }} />
        ))}
      </fieldset>
      <fieldset className={styles.scopeGroup}>
        <legend>Change (off by default)</legend>
        {write.map((s) => (
          <Checkbox key={s.name} label={s.name} hint={s.description} checked={value.includes(s.name as AgentScope)} onChange={(e) => { toggle(s.name as AgentScope, e.target.checked); }} />
        ))}
      </fieldset>
      <p className={styles.hint}>These only narrow what an agent may do. It can never do more than you can in the web app.</p>
    </div>
  );
}

function CreateConnectionDialog({ options, onClose, onCreated }: { options: AgentConnectionOptions; onClose: () => void; onCreated: (id: string) => void }) {
  const queryClient = useQueryClient();
  const orgs = useQuery(orgsQuery);
  const defaultProfile = options.profiles.find((p) => p.isDefault) ?? options.profiles[0];
  const [name, setName] = useState('');
  const [organizationId, setOrganizationId] = useState('');
  const [profile, setProfile] = useState(defaultProfile?.key ?? 'CUSTOM');
  const [scopes, setScopes] = useState<AgentScope[]>((defaultProfile?.scopes ?? []) as AgentScope[]);
  const [expires, setExpires] = useState('90');
  const orgId = organizationId || (orgs.data?.length === 1 ? (orgs.data[0]?.id ?? '') : '');
  const chosen = profile === 'CUSTOM' ? scopes : ((options.profiles.find((p) => p.key === profile)?.scopes ?? []) as AgentScope[]);
  const create = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/me/agent-connections', {
          body: { name: name.trim(), organizationId: orgId, scopes: chosen, expiresInDays: expires ? (Number(expires) as 30 | 90 | 365) : null },
        }),
      ),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: agentKeys.list });
      onCreated(created.id);
    },
  });
  return (
    <Dialog
      open
      title="New AI agent connection"
      description="You get a URL to add to your agent. The agent then asks you to approve it in the browser."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={create.isPending} disabled={!name.trim() || !orgId || chosen.length === 0} onClick={() => { create.mutate(); }}>
            Create connection
          </Button>
        </>
      }
    >
      {create.isError && <Alert tone="danger">{describeError(create.error)}</Alert>}
      <Input label="Name" maxLength={80} placeholder="e.g. Claude Code on my laptop" value={name} onChange={(e) => { setName(e.target.value); }} />
      <Select label="Organization" value={orgId} onChange={(e) => { setOrganizationId(e.target.value); }}>
        <option value="">Choose an organization</option>
        {(orgs.data ?? []).map((o) => (
          <option key={o.id} value={o.id}>
            {o.name}
          </option>
        ))}
      </Select>
      <Select
        label="Permissions"
        value={profile}
        onChange={(e) => {
          setProfile(e.target.value);
          const preset = options.profiles.find((p) => p.key === e.target.value);
          if (preset) setScopes(preset.scopes as AgentScope[]);
        }}
      >
        {options.profiles.map((p) => (
          <option key={p.key} value={p.key}>
            {p.label}
            {p.isDefault ? ' (read-only, recommended)' : p.scopes.some((s) => WRITE_SCOPES.has(s as AgentScope)) ? ' (can change issues)' : ' (read-only)'}
          </option>
        ))}
        <option value="CUSTOM">Custom</option>
      </Select>
      {profile === 'CUSTOM' ? (
        <ScopePicker options={options} value={scopes} onChange={setScopes} />
      ) : (
        <p className={styles.hint}>Allows: {chosen.join(', ')}</p>
      )}
      <Select label="Expires" value={expires} onChange={(e) => { setExpires(e.target.value); }}>
        <option value="30">In 30 days</option>
        <option value="90">In 90 days</option>
        <option value="365">In a year</option>
        <option value="">Never</option>
      </Select>
    </Dialog>
  );
}

type Tab = 'setup' | 'permissions' | 'activity' | 'settings';

function ManageConnectionDialog({ connection, options, onClose }: { connection: AgentConnection; options: AgentConnectionOptions; onClose: () => void }) {
  const [tab, setTab] = useState<Tab>('setup');
  const revoked = connection.status !== 'ACTIVE';
  return (
    <Dialog open size="lg" title={connection.name} description={`${connection.organization.name} · ${PROFILE_LABELS[connection.profile]}`} onClose={onClose}>
      <div className={styles.urlRow}>
        <Input label="Connection URL" readOnly value={connection.url} onFocus={(e) => { e.target.select(); }} />
        <CopyButton value={connection.url} label="Copy connection URL" />
      </div>
      <p className={styles.hint}>
        The URL only tells an agent where to connect; it is not a password. Agents still have to sign in and be approved by you. <StatusBadge connection={connection} />
      </p>
      <Segmented<Tab>
        label="Connection details"
        value={tab}
        onChange={setTab}
        options={[
          { value: 'setup', label: 'Set up an agent' },
          { value: 'permissions', label: 'Permissions' },
          { value: 'activity', label: 'Activity' },
          { value: 'settings', label: 'Settings' },
        ]}
      />
      {tab === 'setup' && (revoked ? <Alert tone="warning">This connection no longer works. Create a new one.</Alert> : <SetupGuide url={connection.url} />)}
      {tab === 'permissions' && <PermissionsTab connection={connection} options={options} />}
      {tab === 'activity' && <ActivityTab connection={connection} />}
      {tab === 'settings' && <SettingsTab connection={connection} onDeleted={onClose} />}
    </Dialog>
  );
}

export function SetupGuide({ url }: { url: string }) {
  const guides = agentGuides(url);
  const [agent, setAgent] = useState(guides[0]?.id ?? '');
  const guide = guides.find((g) => g.id === agent) ?? guides[0];
  if (!guide) return null;
  return (
    <div className={styles.guide}>
      <Select label="Agent" value={agent} onChange={(e) => { setAgent(e.target.value); }}>
        {guides.map((g) => (
          <option key={g.id} value={g.id}>
            {g.name}
          </option>
        ))}
      </Select>
      <ol className={styles.steps}>
        {guide.steps.map((step) => (
          <li key={step.text}>
            <p>{step.text}</p>
            {step.code && (
              <div className={styles.codeBlock}>
                {step.file && <span className={styles.file}>{step.file}</span>}
                <pre>
                  <code>{step.code}</code>
                </pre>
                <CopyButton value={step.code} label={`Copy ${step.file ?? 'command'}`} />
              </div>
            )}
          </li>
        ))}
      </ol>
      {guide.notes.map((note) => (
        <Alert key={note} tone="info">
          {note}
        </Alert>
      ))}
      <p className={styles.hint}>
        Steps follow the agent’s own documentation (
        <a href={guide.docs} target="_blank" rel="noreferrer">
          {guide.name} docs <ExternalLink size={12} aria-hidden="true" />
        </a>
        ). Copying a command does not connect anything: the status changes to Connected only after an agent has signed in.
      </p>
    </div>
  );
}

function PermissionsTab({ connection, options }: { connection: AgentConnection; options: AgentConnectionOptions }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [scopes, setScopes] = useState<AgentScope[]>(connection.scopes);
  const dirty = scopes.slice().sort().join() !== connection.scopes.slice().sort().join();
  const save = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/me/agent-connections/{id}', { params: { path: { id: connection.id } }, body: { scopes, version: connection.version } })),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: agentKeys.list });
      toast.success('Permissions saved. They apply to the agent’s next request.');
    },
  });
  if (connection.status !== 'ACTIVE') return <Alert tone="warning">This connection no longer works.</Alert>;
  return (
    <div className={styles.tab}>
      {save.isError && <Alert tone="danger">{describeError(save.error)}</Alert>}
      <ScopePicker options={options} value={scopes} onChange={setScopes} />
      <div className={styles.actions}>
        <Button onClick={() => { setScopes(connection.scopes); }} disabled={!dirty}>
          Discard
        </Button>
        <Button variant="primary" loading={save.isPending} disabled={!dirty || scopes.length === 0} onClick={() => { save.mutate(); }}>
          Save permissions
        </Button>
      </div>
    </div>
  );
}

function ActivityTab({ connection }: { connection: AgentConnection }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const agents = useQuery({
    queryKey: agentKeys.agents(connection.id),
    queryFn: () => call(() => api.GET('/api/v1/me/agent-connections/{id}/agents', { params: { path: { id: connection.id } } })),
  });
  const activity = useQuery({
    queryKey: agentKeys.activity(connection.id),
    queryFn: () => call(() => api.GET('/api/v1/me/agent-connections/{id}/activity', { params: { path: { id: connection.id }, query: { size: 50 } } })),
  });
  const signOut = useMutation({
    mutationFn: (clientId: string) =>
      call(() => api.DELETE('/api/v1/me/agent-connections/{id}/agents/{clientId}', { params: { path: { id: connection.id, clientId } } })),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['me', 'agent-connections'] });
      toast.success('The agent was signed out.');
    },
    onError: (e) => { toast.error(describeError(e)); },
  });
  return (
    <div className={styles.tab}>
      <h3 className={styles.subheading}>Signed-in agents</h3>
      {agents.isPending && <Skeleton lines={2} />}
      {agents.data?.length === 0 && <p className={styles.hint}>No agent is signed in.</p>}
      {agents.data && agents.data.length > 0 && (
        <ul className={styles.agentList}>
          {agents.data.map((a) => (
            <li key={a.clientId}>
              <span>
                <strong>{a.clientName}</strong> · signed in {timeAgo(a.authorizedAt)} · last token {timeAgo(a.lastRefreshedAt)}
              </span>
              <Button size="sm" variant="danger" onClick={() => { signOut.mutate(a.clientId); }}>
                Sign out
              </Button>
            </li>
          ))}
        </ul>
      )}
      <p className={styles.hint}>Agent names are reported by the agents themselves.</p>
      <h3 className={styles.subheading}>Recent activity</h3>
      {activity.isPending && <Skeleton lines={3} />}
      {activity.isError && <Alert tone="danger">{describeError(activity.error)}</Alert>}
      {activity.data?.items.length === 0 && <p className={styles.hint}>Nothing yet.</p>}
      {activity.data && activity.data.items.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <caption className={styles.srOnly}>Recent activity of this connection</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">What</th>
                <th scope="col">Target</th>
                <th scope="col">Result</th>
              </tr>
            </thead>
            <tbody>
              {activity.data.items.map((item, i) => (
                <tr key={`${item.at}-${String(i)}`}>
                  <td title={formatDateTime(item.at)}>{timeAgo(item.at)}</td>
                  <td>
                    <code>{item.name}</code>
                  </td>
                  <td>{item.target ?? '—'}</td>
                  <td>
                    <Badge tone={item.outcome === 'OK' ? 'success' : item.outcome === 'DENIED' ? 'warning' : 'danger'}>{item.outcome.replace('_', ' ').toLowerCase()}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className={styles.hint}>
            Showing {pluralize(activity.data.items.length, 'entry', 'entries')} of {activity.data.total}. Only tool names and issue keys are kept, for 90 days.
          </p>
        </div>
      )}
    </div>
  );
}

function SettingsTab({ connection, onDeleted }: { connection: AgentConnection; onDeleted: () => void }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [name, setName] = useState(connection.name);
  const [confirm, setConfirm] = useState<'rotate' | 'revoke' | 'delete' | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['me', 'agent-connections'] });
  const rename = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/me/agent-connections/{id}', { params: { path: { id: connection.id } }, body: { name: name.trim(), version: connection.version } })),
    onSuccess: async () => {
      await refresh();
      toast.success('Renamed.');
    },
  });
  const act = useMutation({
    mutationFn: async (action: 'rotate' | 'revoke' | 'delete') => {
      const path = { params: { path: { id: connection.id } } };
      if (action === 'rotate') await call(() => api.POST('/api/v1/me/agent-connections/{id}/rotate', path));
      else if (action === 'revoke') await call(() => api.POST('/api/v1/me/agent-connections/{id}/revoke', path));
      else await call(() => api.DELETE('/api/v1/me/agent-connections/{id}', path));
      return action;
    },
    onSuccess: async (action) => {
      setConfirm(null);
      await refresh();
      if (action === 'delete') onDeleted();
      toast.success(action === 'rotate' ? 'New URL created. Update your agents and sign in again.' : action === 'revoke' ? 'Connection revoked.' : 'Connection deleted.');
    },
    onError: (e) => { toast.error(describeError(e)); },
  });
  const active = connection.status === 'ACTIVE';
  return (
    <div className={styles.tab}>
      {rename.isError && <Alert tone="danger">{describeError(rename.error)}</Alert>}
      {active && (
        <div className={styles.urlRow}>
          <Input label="Name" maxLength={80} value={name} onChange={(e) => { setName(e.target.value); }} />
          <Button loading={rename.isPending} disabled={!name.trim() || name.trim() === connection.name} onClick={() => { rename.mutate(); }}>
            Rename
          </Button>
        </div>
      )}
      <dl className={styles.facts}>
        <dt>Created</dt>
        <dd>{formatDateTime(connection.createdAt)}</dd>
        <dt>Last successful use</dt>
        <dd>{connection.lastUsedAt ? formatDateTime(connection.lastUsedAt) : 'Never'}</dd>
        <dt>Expires</dt>
        <dd>{connection.expiresAt ? formatDate(connection.expiresAt) : 'Never'}</dd>
        {connection.rotatedAt && (
          <>
            <dt>URL last changed</dt>
            <dd>{formatDateTime(connection.rotatedAt)}</dd>
          </>
        )}
      </dl>
      <div className={styles.danger}>
        {active && (
          <>
            <Button icon={<RefreshCw size={14} aria-hidden="true" />} onClick={() => { setConfirm('rotate'); }}>
              Change URL
            </Button>
            <Button variant="danger" icon={<ShieldAlert size={14} aria-hidden="true" />} onClick={() => { setConfirm('revoke'); }}>
              Revoke
            </Button>
          </>
        )}
        <Button variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirm('delete'); }}>
          Delete
        </Button>
      </div>
      <Dialog
        open={confirm !== null}
        size="sm"
        title={confirm === 'rotate' ? 'Change the URL?' : confirm === 'revoke' ? 'Revoke this connection?' : 'Delete this connection?'}
        description={
          confirm === 'rotate'
            ? 'The current URL stops working and every agent is signed out. Add the new URL to your agents and sign in again.'
            : confirm === 'revoke'
              ? 'Every agent using it is signed out immediately and it cannot be used again.'
              : 'It is revoked and removed together with its activity.'
        }
        onClose={() => { setConfirm(null); }}
        footer={
          <>
            <Button onClick={() => { setConfirm(null); }}>Cancel</Button>
            <Button variant={confirm === 'rotate' ? 'primary' : 'danger'} loading={act.isPending} onClick={() => { if (confirm) act.mutate(confirm); }}>
              {confirm === 'rotate' ? 'Change URL' : confirm === 'revoke' ? 'Revoke' : 'Delete'}
            </Button>
          </>
        }
      >
        {null}
      </Dialog>
    </div>
  );
}

export function UtcpSection() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const settings = useQuery({
    queryKey: utcpKeys.settings,
    queryFn: () => call(() => api.GET('/api/v1/me/utcp/settings')),
  });
  const options = useQuery({
    queryKey: utcpKeys.options,
    queryFn: () => call(() => api.GET('/api/v1/me/utcp/options')),
  });
  const activity = useQuery({
    queryKey: utcpKeys.activity,
    queryFn: () => call(() => api.GET('/api/v1/me/utcp/activity', { params: { query: { size: 10 } } })),
  });
  const tools = useQuery({
    queryKey: utcpKeys.tools,
    queryFn: () => call(() => api.GET('/api/v1/utcp/tools')),
  });

  const [activeTab, setActiveTab] = useState<'instructions' | 'tools' | 'activity'>('instructions');

  const enableMutation = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/me/utcp/enable', {})),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: utcpKeys.settings });
      toast.success('UTCP integration enabled.');
    },
    onError: (err) => {
      toast.error(describeError(err));
    },
  });

  const disableMutation = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/me/utcp/disable', {})),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: utcpKeys.settings });
      toast.success('UTCP integration disabled.');
    },
    onError: (err) => {
      toast.error(describeError(err));
    },
  });

  const isEnabled = settings.data?.enabled ?? true;
  const manualUrl = settings.data?.manualUrl ?? (typeof window !== 'undefined' ? `${window.location.origin}/api/v1/utcp` : '/api/v1/utcp');

  return (
    <SettingsSection
      title="UTCP"
      layout="stacked"
      description={
        <div style={{ display: 'grid', gap: 'var(--space-2)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
            <span style={{ fontWeight: 'var(--weight-semibold)', fontSize: 'var(--text-md)' }}>UTCP</span>
            <Badge tone="warning">Experimental</Badge>
          </div>
          <div>Connect UTCP-compatible AI agents directly to JTRAC tools through a standardized tool discovery and calling interface.</div>
        </div>
      }
      actions={
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-2)' }}>
          {settings.data && (
            <Badge tone={isEnabled ? 'success' : 'neutral'}>
              {isEnabled ? 'Enabled' : 'Disabled'}
            </Badge>
          )}
          {isEnabled ? (
            <Button
              size="sm"
              variant="secondary"
              loading={disableMutation.isPending}
              onClick={() => { disableMutation.mutate(); }}
            >
              Disable UTCP
            </Button>
          ) : (
            <Button
              size="sm"
              variant="primary"
              loading={enableMutation.isPending}
              onClick={() => { enableMutation.mutate(); }}
            >
              Enable UTCP
            </Button>
          )}
        </div>
      }
    >
      {options.data && !options.data.available && (
        <Alert tone="warning">{options.data.unavailableReason}</Alert>
      )}

      {(settings.isPending || options.isPending) && <Skeleton lines={3} />}
      {settings.isError && <Alert tone="danger">{describeError(settings.error)}</Alert>}

      {settings.data && (
        <SettingsCard>
          <div style={{ display: 'grid', gap: 'var(--space-4)' }}>
            <div>
              <div className={styles.urlRow}>
                <Input
                  label="UTCP manual (discovery URL)"
                  value={manualUrl}
                  readOnly
                  onFocus={(e) => { e.target.select(); }}
                />
                <CopyButton value={manualUrl} label="Copy UTCP manual URL" />
              </div>
              <p className={styles.hint}>Pass this discovery manual URL to your UTCP client or agent runtime.</p>
            </div>

            <dl className={styles.facts}>
              <dt>Protocol</dt>
              <dd>
                Universal Tool Calling Protocol (UTCP) v{settings.data.utcpVersion} <Badge tone="warning">Experimental</Badge>
              </dd>
              <dt>Status</dt>
              <dd>
                {isEnabled
                  ? 'Enabled and listening for requests'
                  : 'Disabled — tool executions are blocked'}
              </dd>
              <dt>Catalogue</dt>
              <dd>{settings.data.toolCount} {settings.data.toolCount === 1 ? 'tool' : 'tools'} exposed</dd>
              <dt>Activity</dt>
              <dd>
                {settings.data.lastUsedAt
                  ? settings.data.lastClient
                    ? `Last used ${timeAgo(settings.data.lastUsedAt)} by ${settings.data.lastClient}`
                    : `Last used ${timeAgo(settings.data.lastUsedAt)}`
                  : 'No UTCP agent has connected yet'}
              </dd>
            </dl>

            <Segmented
              label="UTCP details"
              value={activeTab}
              onChange={(tab) => { setActiveTab(tab); }}
              options={[
                { value: 'instructions', label: 'Connection guide' },
                { value: 'tools', label: `Tools (${tools.data?.length ?? settings.data.toolCount})` },
                { value: 'activity', label: 'Activity log' },
              ]}
            />

            {activeTab === 'instructions' && (
              <div className={styles.guide}>
                <ol className={styles.steps}>
                  <li>
                    <p>
                      <strong>Generate a personal API token</strong>
                    </p>
                    <p className={styles.hint}>
                      Go to Account → Personal Tokens and create a token with read scopes (<code>issues:read</code>, <code>projects:read</code>, <code>attachments:read</code>, <code>reports:read</code>).
                    </p>
                  </li>
                  <li>
                    <p>
                      <strong>Configure your UTCP client</strong>
                    </p>
                    <p className={styles.hint}>
                      Point your client to the discovery manual. Authenticate using Bearer token authorization:
                    </p>
                    <div className={styles.codeBlock}>
                      <pre>{JSON.stringify({
                        name: 'jtrac',
                        manual_url: manualUrl,
                        auth: {
                          auth_type: 'api_key',
                          var_name: 'Authorization',
                          location: 'header',
                          api_key: 'Bearer ${JTRAC_API_TOKEN}',
                        },
                      }, null, 2)}</pre>
                      <CopyButton
                        value={JSON.stringify({
                          name: 'jtrac',
                          manual_url: manualUrl,
                          auth: {
                            auth_type: 'api_key',
                            var_name: 'Authorization',
                            location: 'header',
                            api_key: 'Bearer ${JTRAC_API_TOKEN}',
                          },
                        }, null, 2)}
                        label="Copy UTCP configuration"
                      />
                    </div>
                  </li>
                  <li>
                    <p>
                      <strong>Multi-tenant organization header (optional)</strong>
                    </p>
                    <p className={styles.hint}>
                      If your user account belongs to multiple organizations, specify <code>X-JTRAC-Organization: &lt;slug&gt;</code> in headers, or leave it unset to use your default organization.
                    </p>
                  </li>
                </ol>
              </div>
            )}

            {activeTab === 'tools' && (
              <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
                {tools.data && (
                  <UtcpToolList tools={tools.data} />
                )}
              </div>
            )}

            {activeTab === 'activity' && (
              <div style={{ display: 'grid', gap: 'var(--space-3)' }}>
                {activity.isPending && <Skeleton lines={3} />}
                {activity.isError && <Alert tone="danger">{describeError(activity.error)}</Alert>}
                {activity.data && (
                  <UtcpActivityTable items={activity.data.items} />
                )}
              </div>
            )}
          </div>
        </SettingsCard>
      )}
    </SettingsSection>
  );
}

function UtcpToolList({ tools }: { tools: UtcpToolSummary[] }) {
  const categories = Array.from(new Set(tools.map((tool) => tool.category)));

  return (
    <div style={{ display: 'grid', gap: 'var(--space-4)' }}>
      {categories.map((cat) => {
        const catTools = tools.filter((tool) => tool.category === cat);
        return (
          <div key={cat} style={{ display: 'grid', gap: 'var(--space-2)' }}>
            <h4 className={styles.subheading}>{cat} ({catTools.length})</h4>
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>Tool</th>
                    <th>Scope</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody>
                  {catTools.map((tool) => (
                    <tr key={tool.name}>
                      <td><code>{tool.name}</code></td>
                      <td><Badge tone="neutral">{tool.scope}</Badge></td>
                      <td style={{ whiteSpace: 'normal' }}>{tool.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function UtcpActivityTable({ items }: { items: UtcpActivity[] }) {
  if (items.length === 0) {
    return <p className={styles.hint}>No tool calls recorded yet. Activity will appear here when an agent invokes a tool.</p>;
  }

  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr>
            <th>Time</th>
            <th>Tool</th>
            <th>Target</th>
            <th>Client</th>
            <th>Status</th>
            <th>Duration</th>
          </tr>
        </thead>
        <tbody>
          {items.map((it) => (
            <tr key={it.id}>
              <td>{timeAgo(it.occurredAt)}</td>
              <td><code>{it.toolName}</code></td>
              <td>{(it.target ?? '') === '' ? '—' : it.target}</td>
              <td>{(it.client ?? '') === '' ? 'UTCP client' : it.client}</td>
              <td>
                <Badge tone={it.outcome === 'OK' ? 'success' : 'danger'}>
                  {it.outcome}
                </Badge>
              </td>
              <td>{it.durationMs != null ? `${it.durationMs}ms` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

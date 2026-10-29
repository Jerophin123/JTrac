import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bot, Check, Copy, ExternalLink, Plus, RefreshCw, Settings2, ShieldAlert, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { orgsQuery } from '../../api/queries';
import type { AgentConnection, AgentConnectionOptions, AgentScope } from '../../api/types';
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

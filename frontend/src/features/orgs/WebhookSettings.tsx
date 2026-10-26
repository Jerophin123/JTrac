import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, RefreshCcw, Send, Trash2, Webhook as WebhookIcon } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, projectsQuery } from '../../api/queries';
import type { Webhook } from '../../api/types';
import { useOrgSlug } from '../../app/params';
import { describeError, fieldErrors } from '../../lib/errors';
import { formatDateTime, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Checkbox, Input, Select } from '../../ui/Field';
import { Skeleton } from '../../ui/Page';
import { SettingsCard, SettingsEmpty, SettingsList, SettingsListItem, SettingsSection } from '../../ui/Settings';
import { Switch } from '../../ui/Switch';
import { useToast } from '../../ui/Toast';
import styles from './OrgSettings.module.css';

const EVENTS = ['issue.created', 'issue.updated', 'issue.transitioned', 'issue.commented', 'issue.linked', 'issue.attachment_added', 'issue.deleted'] as const;
type Event = (typeof EVENTS)[number];

export function WebhookSettings() {
  const org = useOrgSlug();
  const webhooks = useQuery({ queryKey: keys.webhooks(org), queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/webhooks', { params: { path: { org } } })) });
  const [editing, setEditing] = useState<Webhook | 'new' | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [logFor, setLogFor] = useState<Webhook | null>(null);
  return (
    <>
      <SettingsSection
        title="Webhooks"
        layout="stacked"
        description={
          <>
            JTRAC POSTs a small JSON event to your HTTPS endpoint, signed with <code>X-JTRAC-Signature: sha256=…</code> (HMAC of
            “timestamp.body”). Payloads contain issue keys and changed field names only. Fetch details with an API token.
          </>
        }
        actions={
          <Button variant="primary" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setEditing('new'); }}>
            Add webhook
          </Button>
        }
      >
        {webhooks.isPending && <Skeleton lines={3} />}
        {webhooks.isError && <Alert tone="danger">{describeError(webhooks.error)}</Alert>}
        {webhooks.data && (
          <SettingsCard flush>
            {webhooks.data.length === 0 ? (
              <SettingsEmpty>No webhooks yet. Add one to push issue events to another system.</SettingsEmpty>
            ) : (
              <SettingsList label="Webhooks">
                {webhooks.data.map((w) => (
                  <SettingsListItem
                    key={w.id}
                    leading={
                      <span className={styles.hookMark} data-active={w.active || undefined} title={w.active ? 'Active' : 'Paused'}>
                        <WebhookIcon size={16} aria-hidden="true" />
                      </span>
                    }
                    title={
                      <>
                        <span className={styles.endpoint}>{w.url}</span>
                        {w.active ? <Badge tone="success">active</Badge> : <Badge tone="warning">paused</Badge>}
                        <Badge>{w.projectKey ?? 'all projects'}</Badge>
                      </>
                    }
                    meta={
                      <>
                        {w.stats.succeeded} delivered · {w.stats.failed} failed · {w.stats.pending} pending
                        {w.stats.lastDeliveryAt ? ` · last ${timeAgo(w.stats.lastDeliveryAt)}` : ''}
                        <span className={styles.events}>
                          {w.events.map((e) => (
                            <span key={e} className={styles.eventTag}>
                              {e}
                            </span>
                          ))}
                        </span>
                      </>
                    }
                  >
                    <Button size="sm" variant="ghost" onClick={() => { setLogFor(w); }}>
                      Deliveries
                    </Button>
                    <Button size="sm" onClick={() => { setEditing(w); }}>
                      Edit
                    </Button>
                  </SettingsListItem>
                ))}
              </SettingsList>
            )}
          </SettingsCard>
        )}
      </SettingsSection>
      {editing && (
        <WebhookDialog
          webhook={editing === 'new' ? undefined : editing}
          onClose={() => { setEditing(null); }}
          onSecret={setSecret}
        />
      )}
      <Dialog open={secret !== null} size="sm" title="Signing secret" description="Copy it now. It will not be shown again." onClose={() => { setSecret(null); }}>
        <Input label="Secret" readOnly value={secret ?? ''} onFocus={(e) => { e.target.select(); }} />
      </Dialog>
      {logFor && <DeliveryLog webhook={logFor} onClose={() => { setLogFor(null); }} />}
    </>
  );
}

function WebhookDialog({ webhook, onClose, onSecret }: { webhook: Webhook | undefined; onClose: () => void; onSecret: (secret: string) => void }) {
  const org = useOrgSlug();
  const queryClient = useQueryClient();
  const projects = useQuery(projectsQuery(org));
  const [url, setUrl] = useState(webhook?.url ?? 'https://');
  const [description, setDescription] = useState(webhook?.description ?? '');
  const [project, setProject] = useState(webhook?.projectKey ?? '');
  const [events, setEvents] = useState<Event[]>((webhook?.events) ?? ['issue.created', 'issue.updated', 'issue.transitioned']);
  const [active, setActive] = useState(webhook?.active ?? true);
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.webhooks(org) });
  const body = { url: url.trim(), description: description.trim() || null, projectKey: project || null, events, active };
  const save = useMutation({
    mutationFn: async () => {
      if (webhook) {
        await call(() => api.PUT('/api/v1/orgs/{org}/webhooks/{id}', { params: { path: { org, id: webhook.id } }, body: { version: webhook.version, webhook: body } }));
        return null;
      }
      return (await call(() => api.POST('/api/v1/orgs/{org}/webhooks', { params: { path: { org } }, body }))).secret;
    },
    onSuccess: async (secret) => {
      await refresh();
      onClose();
      if (secret) {
        onSecret(secret);
      }
    },
  });
  const rotate = useMutation({
    mutationFn: (id: string) => call(() => api.POST('/api/v1/orgs/{org}/webhooks/{id}/rotate-secret', { params: { path: { org, id } } })),
    onSuccess: async (result) => {
      await refresh();
      onClose();
      onSecret(result.secret);
    },
  });
  const ping = useMutation({
    mutationFn: (id: string) => call(() => api.POST('/api/v1/orgs/{org}/webhooks/{id}/ping', { params: { path: { org, id } } })),
  });
  const remove = useMutation({
    mutationFn: (id: string) => call(() => api.DELETE('/api/v1/orgs/{org}/webhooks/{id}', { params: { path: { org, id } } })),
    onSuccess: async () => {
      await refresh();
      onClose();
    },
  });
  const errors = fieldErrors(save.error);
  return (
    <Dialog
      open
      title={webhook ? 'Edit webhook' : 'Add webhook'}
      onClose={onClose}
      footer={
        <>
          {webhook && (
            <Button variant="danger" icon={<Trash2 size={14} aria-hidden="true" />} loading={remove.isPending} onClick={() => { remove.mutate(webhook.id); }}>
              Delete
            </Button>
          )}
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={events.length === 0} onClick={() => { save.mutate(); }}>
            Save
          </Button>
        </>
      }
    >
      {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}
      {ping.isSuccess && <Alert tone="success">Test event queued. Check the delivery log in a few seconds.</Alert>}
      <Input label="Payload URL" type="url" value={url} error={errors.url} hint="Must be https and publicly reachable." onChange={(e) => { setUrl(e.target.value); }} />
      <Input label="Description" maxLength={200} value={description} onChange={(e) => { setDescription(e.target.value); }} />
      <Select label="Project" value={project} onChange={(e) => { setProject(e.target.value); }}>
        <option value="">All projects</option>
        {(projects.data ?? []).map((p) => (
          <option key={p.key} value={p.key}>
            {p.name}
          </option>
        ))}
      </Select>
      <fieldset className="field-group">
        <legend>Events</legend>
        <div className={styles.eventGrid}>
          {EVENTS.map((e) => (
            <Checkbox key={e} label={e} checked={events.includes(e)} onChange={(ev) => { setEvents(ev.target.checked ? [...events, e] : events.filter((x) => x !== e)); }} />
          ))}
        </div>
        {errors.events && <p className="field-error">{errors.events}</p>}
      </fieldset>
      <Switch label="Active: deliver events to this endpoint" checked={active} onChange={(e) => { setActive(e.target.checked); }} />
      {webhook && (
        <div className="row">
          <Button size="sm" icon={<Send size={14} aria-hidden="true" />} loading={ping.isPending} onClick={() => { ping.mutate(webhook.id); }}>
            Send test event
          </Button>
          <Button size="sm" icon={<RefreshCcw size={14} aria-hidden="true" />} loading={rotate.isPending} onClick={() => { rotate.mutate(webhook.id); }}>
            Rotate secret
          </Button>
        </div>
      )}
    </Dialog>
  );
}

function DeliveryLog({ webhook, onClose }: { webhook: Webhook; onClose: () => void }) {
  const org = useOrgSlug();
  const queryClient = useQueryClient();
  const toast = useToast();
  const [page, setPage] = useState(0);
  const deliveries = useQuery({
    queryKey: keys.deliveries(org, webhook.id, page),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/webhooks/{id}/deliveries', { params: { path: { org, id: webhook.id }, query: { page, size: 25 } } })),
    refetchInterval: 10_000,
  });
  const redeliver = useMutation({
    mutationFn: (deliveryId: string) =>
      call(() => api.POST('/api/v1/orgs/{org}/webhooks/{id}/deliveries/{deliveryId}/redeliver', { params: { path: { org, id: webhook.id, deliveryId } } })),
    onSuccess: async () => {
      toast.success('Queued for redelivery');
      await queryClient.invalidateQueries({ queryKey: ['org', org, 'webhooks', webhook.id] });
    },
    onError: (e) => { toast.error(describeError(e)); },
  });
  return (
    <Dialog open size="lg" title="Delivery log" description={webhook.url} onClose={onClose}>
      {deliveries.isPending && <Skeleton lines={5} />}
      {deliveries.isError && <Alert tone="danger">{describeError(deliveries.error)}</Alert>}
      {deliveries.data?.items.length === 0 && <p className="muted">No deliveries yet.</p>}
      {deliveries.data && deliveries.data.items.length > 0 && (
        <SettingsCard flush>
          <SettingsList label="Deliveries">
            {deliveries.data.items.map((d) => (
              <SettingsListItem
                key={d.id}
                leading={<Badge tone={d.status === 'SUCCEEDED' ? 'success' : d.status === 'FAILED' ? 'danger' : 'neutral'}>{d.status.toLowerCase()}</Badge>}
                title={<code>{d.eventType}</code>}
                meta={
                  <span title={formatDateTime(d.createdAt)}>
                    {timeAgo(d.createdAt)} · {d.attempts} attempt(s)
                    {d.lastStatusCode ? ` · HTTP ${String(d.lastStatusCode)}` : ''}
                    {d.lastDurationMs !== null && d.lastDurationMs !== undefined ? ` · ${String(d.lastDurationMs)} ms` : ''}
                    {d.lastError ? ` · ${d.lastError}` : ''}
                  </span>
                }
              >
                {d.status !== 'PENDING' && (
                  <Button size="sm" variant="ghost" onClick={() => { redeliver.mutate(d.id); }}>
                    Redeliver
                  </Button>
                )}
              </SettingsListItem>
            ))}
          </SettingsList>
        </SettingsCard>
      )}
      {deliveries.data && deliveries.data.total > 25 && (
        <div className="row">
          <Button size="sm" disabled={page === 0} onClick={() => { setPage(page - 1); }}>
            Newer
          </Button>
          <Button size="sm" disabled={(page + 1) * 25 >= deliveries.data.total} onClick={() => { setPage(page + 1); }}>
            Older
          </Button>
        </div>
      )}
    </Dialog>
  );
}

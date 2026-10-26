import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../../api/call';
import { api } from '../../../api/client';
import { catalogQuery, fieldsQuery, keys, workflowQuery } from '../../../api/queries';
import { CATEGORIES, type FieldAccess, type FieldRule, type StatusCategory, type Transition, type WorkflowDefinition } from '../../../api/types';
import { useOrgSlug, useProject } from '../../../app/params';
import { describeError } from '../../../lib/errors';
import { timeAgo } from '../../../lib/format';
import { Alert } from '../../../ui/Alert';
import { Badge, StatusBadge } from '../../../ui/Badge';
import { Button } from '../../../ui/Button';
import { Dialog } from '../../../ui/Dialog';
import { Checkbox, Input, Select } from '../../../ui/Field';
import { Panel, Skeleton } from '../../../ui/Page';
import { useToast } from '../../../ui/Toast';
import styles from './ProjectSettings.module.css';

const SYSTEM_FIELDS: Record<string, string> = {
  description: 'Description',
  assignee: 'Assignee',
  priority: 'Priority',
  labels: 'Labels',
  dueDate: 'Due date',
  startDate: 'Start date',
  estimate: 'Estimate',
  milestone: 'Milestone',
  parent: 'Parent',
};
const ACCESS_LABELS: Record<FieldAccess, string> = {
  HIDDEN: 'Hidden',
  READ_ONLY: 'Read-only',
  OPTIONAL: 'Editable',
  REQUIRED: 'Required',
};
const CATEGORY_LABELS: Record<StatusCategory, string> = { TODO: 'To do', IN_PROGRESS: 'In progress', DONE: 'Done', CANCELED: 'Canceled' };

export function WorkflowSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const toast = useToast();
  const overview = useQuery(workflowQuery(org, project.key));
  const catalog = useQuery(catalogQuery(org, project.key));
  const fields = useQuery(fieldsQuery(org, project.key));
  const [draft, setDraft] = useState<WorkflowDefinition | null>(null);
  const [publishing, setPublishing] = useState(false);
  const [newStatus, setNewStatus] = useState({ name: '', category: 'TODO' as StatusCategory });
  const path = { params: { path: { org, key: project.key } } };
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.workflow(org, project.key) });

  const createStatus = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/workflow/statuses', { ...path, body: { name: newStatus.name.trim(), category: newStatus.category } })),
    onSuccess: async () => {
      setNewStatus({ name: '', category: 'TODO' });
      await refresh();
    },
    onError: (e) => { toast.error(describeError(e)); },
  });
  const updateStatus = useMutation({
    mutationFn: (s: { id: string; name: string; category: StatusCategory }) =>
      call(() => api.PATCH('/api/v1/orgs/{org}/projects/{key}/workflow/statuses/{statusId}', { params: { path: { org, key: project.key, statusId: s.id } }, body: { name: s.name, category: s.category } })),
    onSuccess: refresh,
    onError: (e) => { toast.error(describeError(e)); },
  });
  const saveDraft = useMutation({
    mutationFn: (definition: WorkflowDefinition) => call(() => api.PUT('/api/v1/orgs/{org}/projects/{key}/workflow/draft', { ...path, body: definition })),
    onSuccess: async () => {
      toast.success('Draft saved');
      await refresh();
    },
  });
  const discard = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/workflow/draft', path)),
    onSuccess: async () => {
      setDraft(null);
      await refresh();
    },
  });

  if (overview.isPending || catalog.isPending) {
    return <Skeleton lines={8} label="Loading workflow" />;
  }
  if (overview.isError) {
    return <Alert tone="danger">{describeError(overview.error)}</Alert>;
  }
  const data = overview.data;
  const statuses = data.statuses.filter((s) => !s.retired);
  const statusName = (id: string | null | undefined) => (id ? (data.statuses.find((s) => s.id === id)?.name ?? 'Unknown') : 'Any status');
  const roles = catalog.data?.roles ?? [];
  const roleName = (id: string | null | undefined) => (id ? (roles.find((r) => r.id === id)?.name ?? 'Unknown role') : 'Any role');
  const fieldOptions = { ...SYSTEM_FIELDS, ...Object.fromEntries((fields.data ?? []).map((f) => [`cf:${f.key}`, f.name])) };
  const editing = draft ?? data.draft?.definition ?? null;

  const startEditing = () => {
    setDraft(structuredClone(data.draft?.definition ?? data.published.definition));
  };
  const change = (next: Partial<WorkflowDefinition>) => {
    setDraft({ ...(editing ?? data.published.definition), ...next });
  };

  return (
    <div className="stack">
      <Panel title="Statuses">
        <ul className={styles.list}>
          {data.statuses.map((s) => (
            <li key={s.id}>
              <span className={styles.grow}>
                <StatusBadge name={s.name} category={s.category} /> {s.retired && <Badge>retired</Badge>}
                <span className="muted"> · {data.issueCounts[s.id] ?? 0} issues</span>
              </span>
              <Input
                label={`Rename ${s.name}`}
                hideLabel
                defaultValue={s.name}
                maxLength={40}
                onBlur={(e) => {
                  if (e.target.value.trim() && e.target.value.trim() !== s.name) {
                    updateStatus.mutate({ id: s.id, name: e.target.value.trim(), category: s.category });
                  }
                }}
              />
              <Select label={`Category of ${s.name}`} hideLabel value={s.category} onChange={(e) => { updateStatus.mutate({ id: s.id, name: s.name, category: e.target.value as StatusCategory }); }}>
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {CATEGORY_LABELS[c]}
                  </option>
                ))}
              </Select>
            </li>
          ))}
        </ul>
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault();
            if (newStatus.name.trim()) {
              createStatus.mutate();
            }
          }}
        >
          <Input label="New status name" hideLabel placeholder="New status name" maxLength={40} value={newStatus.name} onChange={(e) => { setNewStatus({ ...newStatus, name: e.target.value }); }} />
          <Select label="Category" hideLabel value={newStatus.category} onChange={(e) => { setNewStatus({ ...newStatus, category: e.target.value as StatusCategory }); }}>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABELS[c]}
              </option>
            ))}
          </Select>
          <Button type="submit" icon={<Plus size={14} aria-hidden="true" />} loading={createStatus.isPending}>
            Add status
          </Button>
        </form>
        <p className="muted">New statuses become available to the workflow once you add them to a draft and publish it.</p>
      </Panel>

      <Panel
        title={`Workflow · version ${String(data.published.versionNo)} published${data.published.publishedAt ? ` ${timeAgo(data.published.publishedAt)}` : ''}`}
        actions={
          editing ? (
            <div className="row">
              <Button size="sm" variant="ghost" loading={discard.isPending} onClick={() => { if (data.draft) { discard.mutate(); } else { setDraft(null); } }}>
                Discard draft
              </Button>
              <Button size="sm" loading={saveDraft.isPending} onClick={() => { saveDraft.mutate(editing); }}>
                Save draft
              </Button>
              <Button size="sm" variant="primary" disabled={!data.draft || draft !== null} onClick={() => { setPublishing(true); }} title={draft !== null ? 'Save the draft first' : undefined}>
                Review & publish
              </Button>
            </div>
          ) : (
            <Button size="sm" variant="primary" onClick={startEditing}>
              Edit workflow
            </Button>
          )
        }
      >
        {saveDraft.isError && <Alert tone="danger">{describeError(saveDraft.error)}</Alert>}
        {!editing ? (
          <DefinitionSummary definition={data.published.definition} statusName={statusName} roleName={roleName} fieldName={(f) => fieldOptions[f] ?? f} />
        ) : (
          <div className="stack">
            {data.draft && draft === null && <Alert tone="info">A saved draft (version {data.draft.versionNo}) is waiting to be published. Changes apply only after publishing.</Alert>}
            <fieldset className="field-group">
              <legend>Statuses in this workflow</legend>
              <div className="row">
                {statuses.map((s) => (
                  <Checkbox
                    key={s.id}
                    label={s.name}
                    checked={editing.statuses.includes(s.id)}
                    onChange={(e) => { change({ statuses: e.target.checked ? [...editing.statuses, s.id] : editing.statuses.filter((id) => id !== s.id) }); }}
                  />
                ))}
              </div>
            </fieldset>
            <Select label="New issues start in" value={editing.initialStatusId} onChange={(e) => { change({ initialStatusId: e.target.value }); }}>
              {editing.statuses.map((id) => (
                <option key={id} value={id}>
                  {statusName(id)}
                </option>
              ))}
            </Select>

            <h3 className={styles.subhead}>Transitions</h3>
            <ul className={styles.items}>
              {editing.transitions.map((t, index) => (
                <TransitionEditor
                  key={t.id}
                  transition={t}
                  statuses={editing.statuses.map((id) => ({ id, name: statusName(id) }))}
                  roles={roles}
                  onChange={(next) => { change({ transitions: editing.transitions.map((x, i) => (i === index ? next : x)) }); }}
                  onRemove={() => { change({ transitions: editing.transitions.filter((_, i) => i !== index) }); }}
                />
              ))}
            </ul>
            <div>
              <Button
                size="sm"
                icon={<Plus size={14} aria-hidden="true" />}
                onClick={() => {
                  change({
                    transitions: [
                      ...editing.transitions,
                      { id: `t-${String(Date.now())}`, name: 'New transition', from: [], to: editing.statuses[0] ?? '', roles: [] },
                    ],
                  });
                }}
              >
                Add transition
              </Button>
            </div>

            <h3 className={styles.subhead}>Field rules</h3>
            <p className="muted">
              Control who sees and edits each field, per status and role. The most specific rule wins (status + role, then status, then role); with several
              roles, the most permissive applies. Fields without a rule are editable.
            </p>
            <ul className={styles.items}>
              {editing.rules.map((rule, index) => (
                <RuleEditor
                  key={index}
                  rule={rule}
                  statuses={editing.statuses.map((id) => ({ id, name: statusName(id) }))}
                  roles={roles}
                  fields={fieldOptions}
                  onChange={(next) => { change({ rules: editing.rules.map((x, i) => (i === index ? next : x)) }); }}
                  onRemove={() => { change({ rules: editing.rules.filter((_, i) => i !== index) }); }}
                />
              ))}
            </ul>
            <div>
              <Button size="sm" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { change({ rules: [...editing.rules, { statusId: null, roleId: null, field: 'description', access: 'READ_ONLY' }] }); }}>
                Add field rule
              </Button>
            </div>
          </div>
        )}
      </Panel>

      {publishing && data.draft && (
        <PublishDialog
          statuses={data.statuses.filter((s) => data.draft?.definition.statuses.includes(s.id))}
          onClose={() => { setPublishing(false); }}
          onPublished={async () => {
            setPublishing(false);
            setDraft(null);
            toast.success('Workflow published');
            await refresh();
            await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
          }}
        />
      )}
    </div>
  );
}

function DefinitionSummary({
  definition,
  statusName,
  roleName,
  fieldName,
}: {
  definition: WorkflowDefinition;
  statusName: (id: string | null | undefined) => string;
  roleName: (id: string | null | undefined) => string;
  fieldName: (field: string) => string;
}) {
  return (
    <div className="stack">
      <p>
        New issues start in <strong>{statusName(definition.initialStatusId)}</strong>.
      </p>
      <table className={`${styles.matrix ?? ''} ${styles.rules ?? ''}`}>
        <caption className="visually-hidden">Transitions</caption>
        <thead>
          <tr>
            <th scope="col">Transition</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Allowed roles</th>
          </tr>
        </thead>
        <tbody>
          {definition.transitions.map((t) => (
            <tr key={t.id}>
              <th scope="row">{t.name}</th>
              <td>{t.from.length ? t.from.map(statusName).join(', ') : 'Any status'}</td>
              <td>{statusName(t.to)}</td>
              <td>{t.roles.length ? t.roles.map(roleName).join(', ') : 'Anyone who may change status'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {definition.rules.length > 0 && (
        <table className={`${styles.matrix ?? ''} ${styles.rules ?? ''}`}>
          <caption className="visually-hidden">Field rules</caption>
          <thead>
            <tr>
              <th scope="col">Field</th>
              <th scope="col">Status</th>
              <th scope="col">Role</th>
              <th scope="col">Access</th>
            </tr>
          </thead>
          <tbody>
            {definition.rules.map((r, i) => (
              <tr key={i}>
                <th scope="row">{fieldName(r.field)}</th>
                <td>{statusName(r.statusId)}</td>
                <td>{roleName(r.roleId)}</td>
                <td>{ACCESS_LABELS[r.access]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function TransitionEditor({
  transition,
  statuses,
  roles,
  onChange,
  onRemove,
}: {
  transition: Transition;
  statuses: { id: string; name: string }[];
  roles: { id: string; name: string }[];
  onChange: (next: Transition) => void;
  onRemove: () => void;
}) {
  return (
    <li className={styles.item}>
      <Input label="Name" maxLength={40} value={transition.name} onChange={(e) => { onChange({ ...transition, name: e.target.value }); }} />
      <Select label="To" value={transition.to} onChange={(e) => { onChange({ ...transition, to: e.target.value }); }}>
        {statuses.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </Select>
      <fieldset className="field-group">
        <legend>From (none = any status)</legend>
        <div className="row">
          {statuses.map((s) => (
            <Checkbox key={s.id} label={s.name} checked={transition.from.includes(s.id)}
              onChange={(e) => { onChange({ ...transition, from: e.target.checked ? [...transition.from, s.id] : transition.from.filter((id) => id !== s.id) }); }} />
          ))}
        </div>
      </fieldset>
      <fieldset className="field-group">
        <legend>Only these roles (none = anyone with “Change status”)</legend>
        <div className="row">
          {roles.map((r) => (
            <Checkbox key={r.id} label={r.name} checked={transition.roles.includes(r.id)}
              onChange={(e) => { onChange({ ...transition, roles: e.target.checked ? [...transition.roles, r.id] : transition.roles.filter((id) => id !== r.id) }); }} />
          ))}
        </div>
      </fieldset>
      <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Remove transition ${transition.name}`} onClick={onRemove} />
    </li>
  );
}

function RuleEditor({
  rule,
  statuses,
  roles,
  fields,
  onChange,
  onRemove,
}: {
  rule: FieldRule;
  statuses: { id: string; name: string }[];
  roles: { id: string; name: string }[];
  fields: Record<string, string>;
  onChange: (next: FieldRule) => void;
  onRemove: () => void;
}) {
  return (
    <li className={styles.item}>
      <Select label="Field" value={rule.field} onChange={(e) => { onChange({ ...rule, field: e.target.value }); }}>
        {Object.entries(fields).map(([key, name]) => (
          <option key={key} value={key}>
            {name}
          </option>
        ))}
      </Select>
      <Select label="In status" value={rule.statusId ?? ''} onChange={(e) => { onChange({ ...rule, statusId: e.target.value || null }); }}>
        <option value="">Any status</option>
        {statuses.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </Select>
      <Select label="For role" value={rule.roleId ?? ''} onChange={(e) => { onChange({ ...rule, roleId: e.target.value || null }); }}>
        <option value="">Any role</option>
        {roles.map((r) => (
          <option key={r.id} value={r.id}>
            {r.name}
          </option>
        ))}
      </Select>
      <Select label="Access" value={rule.access} onChange={(e) => { onChange({ ...rule, access: e.target.value as FieldAccess }); }}>
        {Object.entries(ACCESS_LABELS).map(([value, label]) => (
          <option key={value} value={value}>
            {label}
          </option>
        ))}
      </Select>
      <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label="Remove rule" onClick={onRemove} />
    </li>
  );
}

function PublishDialog({ statuses, onClose, onPublished }: { statuses: { id: string; name: string }[]; onClose: () => void; onPublished: () => Promise<void> }) {
  const org = useOrgSlug();
  const project = useProject();
  const path = { params: { path: { org, key: project.key } } };
  const impact = useQuery({
    queryKey: [...keys.workflow(org, project.key), 'impact'],
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/projects/{key}/workflow/draft/impact', path)),
  });
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const publish = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/workflow/draft/publish', { ...path, body: { mapping } })),
    onSuccess: onPublished,
  });
  const affected = (impact.data?.removedStatuses ?? []).filter((r) => r.issueCount > 0);
  const complete = affected.every((r) => mapping[r.statusId]);
  return (
    <Dialog
      open
      title="Publish workflow"
      description="Publishing replaces the current workflow for every issue in this project."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={publish.isPending} disabled={impact.isPending || !complete} onClick={() => { publish.mutate(); }}>
            Publish
          </Button>
        </>
      }
    >
      {publish.isError && <Alert tone="danger">{describeError(publish.error)}</Alert>}
      {impact.isPending && <Skeleton lines={2} label="Checking which issues are affected" />}
      {impact.data && affected.length === 0 && <Alert tone="success">No issues need to move. Existing issues keep their status.</Alert>}
      {affected.length > 0 && (
        <>
          <p>These statuses are removed but still hold issues. Choose where each group of issues moves; every move is recorded in the issue history.</p>
          {affected.map((r) => (
            <Select
              key={r.statusId}
              label={`${r.name} (${String(r.issueCount)} issues) → `}
              value={mapping[r.statusId] ?? ''}
              onChange={(e) => { setMapping({ ...mapping, [r.statusId]: e.target.value }); }}
            >
              <option value="">Choose a status…</option>
              {statuses.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </Select>
          ))}
        </>
      )}
    </Dialog>
  );
}

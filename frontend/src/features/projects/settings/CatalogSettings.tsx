import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, FileSpreadsheet, FileUp, Plus, Trash2, Upload, X } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../../api/call';
import { api } from '../../../api/client';
import { catalogQuery, fieldsQuery, keys } from '../../../api/queries';
import type { FieldDefinition, FieldType, ImportResult } from '../../../api/types';
import { useOrgSlug, useProject } from '../../../app/params';
import { describeError, fieldErrors } from '../../../lib/errors';
import { formatDate } from '../../../lib/format';
import { Alert } from '../../../ui/Alert';
import { Badge } from '../../../ui/Badge';
import { Button } from '../../../ui/Button';
import { Dialog } from '../../../ui/Dialog';
import { Checkbox, Input, Select, TextArea } from '../../../ui/Field';
import { Panel, Skeleton } from '../../../ui/Page';
import { SettingsCard, SettingsSection } from '../../../ui/Settings';
import { useToast } from '../../../ui/Toast';
import styles from './ProjectSettings.module.css';

const TYPE_LABELS: Record<FieldType, string> = {
  TEXT: 'Short text',
  LONG_TEXT: 'Long text',
  NUMBER: 'Number',
  DATE: 'Date',
  SELECT: 'Single choice',
  MULTI_SELECT: 'Multiple choice',
  USER: 'Person',
  CHECKBOX: 'Checkbox',
  URL: 'Link',
};

export function FieldsSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const fields = useQuery(fieldsQuery(org, project.key));
  const [editing, setEditing] = useState<FieldDefinition | 'new' | null>(null);
  if (fields.isPending) {
    return <Skeleton lines={5} />;
  }
  if (fields.isError) {
    return <Alert tone="danger">{describeError(fields.error)}</Alert>;
  }
  return (
    <Panel
      title="Custom fields"
      actions={
        <Button size="sm" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setEditing('new'); }}>
          New field
        </Button>
      }
    >
      <p className="muted">Custom fields appear on every issue of this project. Use workflow field rules to hide them or make them required in some statuses.</p>
      {fields.data.length === 0 ? (
        <p className="muted">No custom fields yet.</p>
      ) : (
        <ul className={styles.list}>
          {fields.data.map((f) => (
            <li key={f.id}>
              <span className={styles.grow}>
                <strong>{f.name}</strong> <span className="issue-key">cf:{f.key}</span> <Badge>{TYPE_LABELS[f.type]}</Badge>{' '}
                {f.archived && <Badge tone="warning">archived</Badge>}
              </span>
              <Button size="sm" onClick={() => { setEditing(f); }}>
                Edit
              </Button>
            </li>
          ))}
        </ul>
      )}
      {editing && <FieldDialog field={editing === 'new' ? undefined : editing} onClose={() => { setEditing(null); }} />}
    </Panel>
  );
}

function FieldDialog({ field, onClose }: { field: FieldDefinition | undefined; onClose: () => void }) {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const [name, setName] = useState(field?.name ?? '');
  const [key, setKey] = useState(field?.key ?? '');
  const [type, setType] = useState<FieldType>(field?.type ?? 'TEXT');
  const [description, setDescription] = useState(field?.description ?? '');
  const [archived, setArchived] = useState(field?.archived ?? false);
  const [options, setOptions] = useState<{ id?: string | null; label: string }[]>(field?.options.map((o) => ({ id: o.id, label: o.label })) ?? []);
  const hasOptions = type === 'SELECT' || type === 'MULTI_SELECT';
  const save = useMutation({
    mutationFn: () => {
      const cleanOptions = hasOptions ? options.filter((o) => o.label.trim()).map((o) => ({ id: o.id ?? null, label: o.label.trim() })) : [];
      return call(() =>
        field
          ? api.PATCH('/api/v1/orgs/{org}/projects/{key}/fields/{fieldId}', {
              params: { path: { org, key: project.key, fieldId: field.id } },
              body: { name: name.trim(), description: description.trim() || null, options: cleanOptions, archived, version: field.version },
            })
          : api.POST('/api/v1/orgs/{org}/projects/{key}/fields', {
              params: { path: { org, key: project.key } },
              body: { name: name.trim(), key: key || undefined, type, description: description.trim() || undefined, options: cleanOptions },
            }),
      );
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.fields(org, project.key) });
      onClose();
    },
  });
  const errors = fieldErrors(save.error);
  return (
    <Dialog
      open
      title={field ? `Field: ${field.name}` : 'New custom field'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={!name.trim()} onClick={() => { save.mutate(); }}>
            Save
          </Button>
        </>
      }
    >
      {save.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(save.error)}</Alert>}
      <div className="form-grid">
        <Input label="Name" maxLength={60} value={name} error={errors.name} onChange={(e) => { setName(e.target.value); }} />
        <Input
          label="Key"
          maxLength={40}
          disabled={Boolean(field)}
          hint={field ? 'Keys are permanent (used in rules, search, CSV and the API).' : 'Optional; derived from the name.'}
          value={key}
          error={errors.key}
          onChange={(e) => { setKey(e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, '')); }}
        />
        <Select label="Type" value={type} disabled={Boolean(field)} onChange={(e) => { setType(e.target.value as FieldType); }}>
          {Object.entries(TYPE_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Select>
      </div>
      <TextArea label="Description" rows={2} value={description} onChange={(e) => { setDescription(e.target.value); }} />
      {hasOptions && (
        <fieldset className="field-group">
          <legend>Options</legend>
          {options.map((option, index) => (
            <div key={option.id ?? `new-${String(index)}`} className="row">
              <Input label={`Option ${String(index + 1)}`} hideLabel value={option.label} maxLength={60}
                onChange={(e) => { setOptions(options.map((o, i) => (i === index ? { ...o, label: e.target.value } : o))); }} />
              <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Remove option ${option.label}`}
                onClick={() => { setOptions(options.filter((_, i) => i !== index)); }} />
            </div>
          ))}
          {errors.options && <p className="field-error">{errors.options}</p>}
          <div>
            <Button size="sm" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setOptions([...options, { label: '' }]); }}>
              Add option
            </Button>
          </div>
        </fieldset>
      )}
      {field && <Checkbox label="Archived" hint="Archived fields keep their values but are no longer shown for editing." checked={archived} onChange={(e) => { setArchived(e.target.checked); }} />}
    </Dialog>
  );
}

export function CatalogSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const toast = useToast();
  const catalog = useQuery(catalogQuery(org, project.key));
  const [typeName, setTypeName] = useState('');
  const [labelName, setLabelName] = useState('');
  const [labelColor, setLabelColor] = useState('#8c6d4f');
  const [milestone, setMilestone] = useState({ name: '', dueDate: '' });
  const p = { org, key: project.key };
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.catalog(org, project.key) });
  const onError = (e: unknown) => { toast.error(describeError(e)); };

  const addType = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/issue-types', { params: { path: p }, body: { name: typeName.trim() } })),
    onSuccess: async () => { setTypeName(''); await refresh(); },
    onError,
  });
  const updateType = useMutation({
    mutationFn: (t: { id: string; name: string; subtask: boolean; archived: boolean; icon?: string | null; color?: string | null }) =>
      call(() => api.PATCH('/api/v1/orgs/{org}/projects/{key}/issue-types/{typeId}', { params: { path: { ...p, typeId: t.id } }, body: { name: t.name, subtask: t.subtask, archived: t.archived, icon: t.icon ?? undefined, color: t.color ?? undefined } })),
    onSuccess: refresh,
    onError,
  });
  const addLabel = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/labels', { params: { path: p }, body: { name: labelName.trim(), color: labelColor } })),
    onSuccess: async () => { setLabelName(''); await refresh(); },
    onError,
  });
  const deleteLabel = useMutation({
    mutationFn: (labelId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/labels/{labelId}', { params: { path: { ...p, labelId } } })),
    onSuccess: refresh,
    onError,
  });
  const addMilestone = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/milestones', { params: { path: p }, body: { name: milestone.name.trim(), dueDate: milestone.dueDate || null } })),
    onSuccess: async () => { setMilestone({ name: '', dueDate: '' }); await refresh(); },
    onError,
  });
  const updateMilestone = useMutation({
    mutationFn: (m: { id: string; name: string; description?: string | null; startDate?: string | null; dueDate?: string | null; state: 'OPEN' | 'CLOSED'; version: number }) =>
      call(() => api.PATCH('/api/v1/orgs/{org}/projects/{key}/milestones/{milestoneId}', { params: { path: { ...p, milestoneId: m.id } }, body: { name: m.name, description: m.description ?? null, startDate: m.startDate ?? null, dueDate: m.dueDate ?? null, state: m.state, version: m.version } })),
    onSuccess: refresh,
    onError,
  });
  const deleteMilestone = useMutation({
    mutationFn: (milestoneId: string) => call(() => api.DELETE('/api/v1/orgs/{org}/projects/{key}/milestones/{milestoneId}', { params: { path: { ...p, milestoneId } } })),
    onSuccess: refresh,
    onError,
  });

  if (catalog.isPending) {
    return <Skeleton lines={6} />;
  }
  if (catalog.isError) {
    return <Alert tone="danger">{describeError(catalog.error)}</Alert>;
  }
  return (
    <div className="stack">
      <Panel title="Issue types">
        <ul className={styles.list}>
          {catalog.data.issueTypes.map((t) => (
            <li key={t.id}>
              <span className={styles.grow}>
                <strong>{t.name}</strong> {t.subtask && <Badge>sub-task type</Badge>} {t.archived && <Badge tone="warning">archived</Badge>}
              </span>
              <Button size="sm" variant="ghost" onClick={() => { updateType.mutate({ ...t, archived: !t.archived }); }}>
                {t.archived ? 'Restore' : 'Archive'}
              </Button>
            </li>
          ))}
        </ul>
        <form className="row" onSubmit={(e) => { e.preventDefault(); if (typeName.trim()) { addType.mutate(); } }}>
          <Input label="New issue type" hideLabel placeholder="New issue type" maxLength={40} value={typeName} onChange={(e) => { setTypeName(e.target.value); }} />
          <Button type="submit" icon={<Plus size={14} aria-hidden="true" />} loading={addType.isPending}>
            Add type
          </Button>
        </form>
      </Panel>

      <Panel title="Labels">
        <ul className={styles.list}>
          {catalog.data.labels.length === 0 && <li className="muted">No labels yet.</li>}
          {catalog.data.labels.map((l) => (
            <li key={l.id}>
              <span className={styles.swatch} style={{ background: l.color ?? undefined }} aria-hidden="true" />
              <span className={styles.grow}>{l.name}</span>
              <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Delete label ${l.name}`} onClick={() => { deleteLabel.mutate(l.id); }} />
            </li>
          ))}
        </ul>
        <form className="row" onSubmit={(e) => { e.preventDefault(); if (labelName.trim()) { addLabel.mutate(); } }}>
          <Input label="New label" hideLabel placeholder="New label" maxLength={40} value={labelName} onChange={(e) => { setLabelName(e.target.value); }} />
          <Input label="Color" hideLabel type="color" value={labelColor} onChange={(e) => { setLabelColor(e.target.value); }} />
          <Button type="submit" icon={<Plus size={14} aria-hidden="true" />} loading={addLabel.isPending}>
            Add label
          </Button>
        </form>
      </Panel>

      <Panel title="Milestones">
        <ul className={styles.list}>
          {catalog.data.milestones.length === 0 && <li className="muted">No milestones yet.</li>}
          {catalog.data.milestones.map((m) => (
            <li key={m.id}>
              <span className={styles.grow}>
                <strong>{m.name}</strong> {m.dueDate && <span className="muted">due {formatDate(m.dueDate)}</span>}{' '}
                {m.state === 'CLOSED' && <Badge tone="success">closed</Badge>}
              </span>
              <Button size="sm" variant="ghost" onClick={() => { updateMilestone.mutate({ ...m, state: m.state === 'OPEN' ? 'CLOSED' : 'OPEN' }); }}>
                {m.state === 'OPEN' ? 'Close' : 'Reopen'}
              </Button>
              <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} aria-label={`Delete milestone ${m.name}`} onClick={() => { deleteMilestone.mutate(m.id); }} />
            </li>
          ))}
        </ul>
        <form className="row" onSubmit={(e) => { e.preventDefault(); if (milestone.name.trim()) { addMilestone.mutate(); } }}>
          <Input label="New milestone" hideLabel placeholder="New milestone" maxLength={80} value={milestone.name} onChange={(e) => { setMilestone({ ...milestone, name: e.target.value }); }} />
          <Input label="Due date" hideLabel type="date" value={milestone.dueDate} onChange={(e) => { setMilestone({ ...milestone, dueDate: e.target.value }); }} />
          <Button type="submit" icon={<Plus size={14} aria-hidden="true" />} loading={addMilestone.isPending}>
            Add milestone
          </Button>
        </form>
      </Panel>
    </div>
  );
}

/** Mirrors the server limit (CsvController.MAX_IMPORT_BYTES) for instant feedback; the server still enforces it. */
const MAX_IMPORT_BYTES = 2 * 1024 * 1024;

const IMPORT_COLUMNS: { name: string; format: string; required?: boolean; literal?: false }[] = [
  { name: 'title', format: 'Text', required: true },
  { name: 'description', format: 'Text (Markdown)' },
  { name: 'type', format: 'Issue type name' },
  { name: 'priority', format: 'URGENT, HIGH, MEDIUM, LOW or NONE' },
  { name: 'assignee', format: 'Email or handle of a member' },
  { name: 'labels', format: 'Label names separated by ; or ,' },
  { name: 'milestone', format: 'Milestone name' },
  { name: 'parent', format: 'Issue key, for example KEY-12' },
  { name: 'startDate, dueDate', format: 'YYYY-MM-DD' },
  { name: 'estimate', format: 'Number' },
  { name: 'Custom fields', format: 'One column per field, named by the field key or name', literal: false },
];

function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${String(bytes)} bytes`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function ImportSettings() {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const run = useMutation({
    mutationFn: (dryRun: boolean) =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/projects/{key}/import', {
          params: { path: { org, key: project.key }, query: { dryRun } },
          body: { file: '' },
          bodySerializer: () => {
            const data = new FormData();
            if (file) {
              data.append('file', file);
            }
            return data;
          },
        }),
      ),
    onSuccess: async (data) => {
      setResult(data);
      if (!data.dryRun && data.createdKeys.length > 0) {
        await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
      }
    },
  });

  const choose = (next: File | null) => {
    setResult(null);
    run.reset();
    if (next && next.size > MAX_IMPORT_BYTES) {
      setFile(null);
      setFileError(`${next.name} is ${formatBytes(next.size)}. The limit is 2 MB.`);
      return;
    }
    setFileError(null);
    setFile(next);
  };

  const checked = result?.dryRun === true && result.errors.length === 0;
  const imported = result?.dryRun === false && result.errors.length === 0;
  const step = imported ? 4 : checked ? 3 : file ? 2 : 1;
  const steps = ['Choose a file', 'Check it', 'Import'];

  return (
    <SettingsSection
      title="Import issues from CSV"
      layout="stacked"
      description="Create many issues at once from a spreadsheet. Check the file first; imports are all-or-nothing, so if any row is invalid nothing is created."
    >
      <SettingsCard
        footer={
          <>
            <span className={styles.importLimits}>One issue per row · up to 1,000 rows · 2 MB · UTF-8</span>
            <Button size="sm" disabled={!file} loading={run.isPending && run.variables} onClick={() => { run.mutate(true); }}>
              Check file
            </Button>
            <Button
              size="sm"
              variant="primary"
              icon={<Upload size={14} aria-hidden="true" />}
              disabled={!file || !checked}
              loading={run.isPending && !run.variables}
              onClick={() => { run.mutate(false); }}
            >
              Import {checked ? `${String(result.rows)} issues` : ''}
            </Button>
          </>
        }
      >
        <ol className={styles.steps} aria-label="Import steps">
          {steps.map((label, i) => {
            const n = i + 1;
            const state = step > n ? 'done' : step === n ? 'current' : 'todo';
            return (
              <li key={label} className={styles.step} data-state={state} aria-current={state === 'current' ? 'step' : undefined}>
                <span className={styles.stepMark} aria-hidden="true">
                  {state === 'done' ? <Check size={13} /> : n}
                </span>
                {label}
              </li>
            );
          })}
        </ol>

        {file ? (
          <div className={styles.fileChip}>
            <span className={styles.fileIcon}>
              <FileSpreadsheet size={20} aria-hidden="true" />
            </span>
            <span className={styles.fileText}>
              <span className={styles.fileName}>{file.name}</span>
              <span className={styles.fileMeta}>{formatBytes(file.size)}</span>
            </span>
            <label className={styles.fileReplace}>
              Replace
              <input type="file" accept=".csv,text/csv" className="visually-hidden" onChange={(e) => { choose(e.target.files?.[0] ?? null); e.target.value = ''; }} />
            </label>
            <Button size="sm" variant="ghost" icon={<X size={14} aria-hidden="true" />} aria-label={`Remove ${file.name}`} onClick={() => { choose(null); }} />
          </div>
        ) : (
          <label className={styles.dropzone} data-dragging={dragging || undefined}>
            <span className={styles.dropIcon}>
              <FileUp size={22} aria-hidden="true" />
            </span>
            <span className={styles.dropTitle}>
              Drop a CSV file here, or <span className={styles.dropLink}>browse</span>
            </span>
            <span className={styles.dropHint}>The first row must hold the column names.</span>
            {/* The native input covers the whole zone, so both clicks and dropped files go through it. */}
            <input
              type="file"
              accept=".csv,text/csv"
              className={styles.dropInput}
              onDragEnter={() => { setDragging(true); }}
              onDragLeave={() => { setDragging(false); }}
              onDrop={() => { setDragging(false); }}
              onChange={(e) => { choose(e.target.files?.[0] ?? null); e.target.value = ''; }}
            />
          </label>
        )}

        {fileError && <Alert tone="danger">{fileError}</Alert>}
        {run.isError && <Alert tone="danger">{describeError(run.error)}</Alert>}
        {result && result.errors.length > 0 && (
          <Alert tone="danger" title={`${String(result.errors.length)} ${result.errors.length === 1 ? 'problem' : 'problems'} found. Nothing was imported.`}>
            <ul className={styles.problems}>
              {result.errors.slice(0, 50).map((e, i) => (
                <li key={i}>
                  <span className={styles.problemWhere}>
                    Line {e.row}
                    {e.column ? ` · ${e.column}` : ''}
                  </span>
                  {e.message}
                </li>
              ))}
            </ul>
            {result.errors.length > 50 && <p>Showing the first 50. Fix these and check again.</p>}
          </Alert>
        )}
        {result?.errors.length === 0 && (
          <Alert tone="success" title={result.dryRun ? `All ${String(result.rows)} rows are valid` : `Imported ${String(result.createdKeys.length)} issues`}>
            {result.dryRun
              ? 'Nothing has been created yet. Press Import to create them.'
              : `Created ${result.createdKeys.slice(0, 10).join(', ')}${result.createdKeys.length > 10 ? ` and ${String(result.createdKeys.length - 10)} more` : ''}.`}
            {result.ignoredColumns.length > 0 && ` Ignored columns: ${result.ignoredColumns.join(', ')}.`}
          </Alert>
        )}
      </SettingsCard>

      <SettingsCard title="CSV columns" meta="Only title is required. Column names are case-insensitive." flush>
        <div className={styles.scroll}>
          <table className={styles.columns}>
            <caption className="visually-hidden">Supported CSV columns</caption>
            <thead>
              <tr>
                <th scope="col">Column</th>
                <th scope="col">Format</th>
              </tr>
            </thead>
            <tbody>
              {IMPORT_COLUMNS.map((c) => (
                <tr key={c.name}>
                  <th scope="row">
                    {c.literal === false ? <em>{c.name}</em> : <code>{c.name}</code>} {c.required && <Badge tone="accent">required</Badge>}
                  </th>
                  <td>{c.format}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </SettingsCard>
    </SettingsSection>
  );
}

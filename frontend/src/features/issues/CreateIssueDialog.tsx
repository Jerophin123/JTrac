import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { catalogQuery, fieldsQuery, keys, membersQuery, projectsQuery } from '../../api/queries';
import { PRIORITIES, type Priority } from '../../api/types';
import { describeError, fieldErrors } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { priorityLabel } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Checkbox, Input, Select, TextArea } from '../../ui/Field';
import { useToast } from '../../ui/Toast';
import { CustomFieldInput } from './CustomFieldInput';
import styles from './CreateIssueDialog.module.css';

interface CreateIssueDialogProps {
  org: string;
  open: boolean;
  onClose: () => void;
  defaultProject?: string | undefined;
  parentKey?: string | undefined;
}

interface Draft {
  project: string;
  typeId: string;
  title: string;
  description: string;
  priority: NonNullable<Priority>;
  assigneeId: string;
  milestoneId: string;
  startDate: string;
  dueDate: string;
  estimate: string;
  labelIds: string[];
  customValues: Record<string, unknown>;
}

const empty = (project: string): Draft => ({
  project,
  typeId: '',
  title: '',
  description: '',
  priority: 'NONE',
  assigneeId: '',
  milestoneId: '',
  startDate: '',
  dueDate: '',
  estimate: '',
  labelIds: [],
  customValues: {},
});

/** Mounted only while open, so every opening starts from a fresh draft. */
export function CreateIssueDialog(props: CreateIssueDialogProps) {
  return props.open ? <CreateIssueForm {...props} /> : null;
}

function CreateIssueForm({ org, open, onClose, defaultProject, parentKey }: CreateIssueDialogProps) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const projects = useQuery({ ...projectsQuery(org), enabled: open });
  const creatable = (projects.data ?? []).filter((p) => !p.archived && p.myPermissions.includes('CREATE_ISSUES'));
  const [draft, setDraft] = useState<Draft>(() => empty(defaultProject ?? ''));
  const [createAnother, setCreateAnother] = useState(false);
  const project = draft.project || (creatable[0]?.key ?? '');
  const catalog = useQuery({ ...catalogQuery(org, project), enabled: open && Boolean(project) });
  const fields = useQuery({ ...fieldsQuery(org, project), enabled: open && Boolean(project) });
  const members = useQuery({ ...membersQuery(org, '', 0, 200), enabled: open });

  const types = (catalog.data?.issueTypes ?? []).filter((t) => !t.archived && (parentKey ? t.subtask : !t.subtask));
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setDraft((current) => ({ ...current, [key]: value }));
  };

  const create = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/projects/{key}/issues', {
          params: { path: { org, key: project } },
          body: {
            title: draft.title.trim(),
            typeId: draft.typeId || (types[0]?.id ?? null),
            description: draft.description.trim() || null,
            priority: draft.priority,
            assigneeId: draft.assigneeId || null,
            milestoneId: draft.milestoneId || null,
            startDate: draft.startDate || null,
            dueDate: draft.dueDate || null,
            estimate: draft.estimate === '' ? null : Number(draft.estimate),
            labelIds: draft.labelIds,
            customValues: Object.fromEntries(Object.entries(draft.customValues).filter(([, v]) => v !== null && v !== undefined)),
            parentKey: parentKey ?? null,
          },
        }),
      ),
    onSuccess: async (issue) => {
      toast.success(`Created ${issue.key}`);
      await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
      if (parentKey) {
        await queryClient.invalidateQueries({ queryKey: keys.issue(org, parentKey) });
      }
      if (createAnother) {
        setDraft((current) => ({ ...empty(current.project), typeId: current.typeId }));
      } else {
        onClose();
        await navigate({ to: '/$org/issue/$issueKey', params: { org, issueKey: issue.key } });
      }
    },
  });

  const errors = create.isError ? fieldErrors(create.error) : {};
  const titleError = errors.title ?? (create.isError && !draft.title.trim() ? 'Enter a title.' : undefined);

  return (
    <Dialog
      open={open}
      onClose={onClose}
      size="lg"
      title={parentKey ? `New sub-task of ${parentKey}` : 'Create issue'}
      footer={
        <>
          <Checkbox label="Create another" checked={createAnother} onChange={(e) => { setCreateAnother(e.target.checked); }} className={styles.another} />
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            type="submit"
            form="create-issue-form"
            loading={create.isPending}
            disabled={!project || creatable.length === 0}
          >
            Create
          </Button>
        </>
      }
    >
      {projects.isSuccess && creatable.length === 0 && (
        <Alert tone="info">You can’t create issues in any project yet. Ask a project administrator for access.</Alert>
      )}
      {create.isError && Object.keys(errors).length === 0 && <Alert tone="danger">{describeError(create.error)}</Alert>}
      {create.isError && Object.keys(errors).length > 0 && <Alert tone="danger">Check the highlighted fields.</Alert>}
      <form
        id="create-issue-form"
        className={styles.form}
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          if (draft.title.trim()) {
            create.mutate();
          }
        }}
      >
        <div className="form-grid">
          {!parentKey && (
            <Select label="Project" value={project} onChange={(e) => { setDraft({ ...empty(e.target.value), title: draft.title, description: draft.description }); }}>
              {creatable.map((p) => (
                <option key={p.key} value={p.key}>
                  {p.name} ({p.key})
                </option>
              ))}
            </Select>
          )}
          <Select label="Type" value={draft.typeId || (types[0]?.id ?? '')} onChange={(e) => { set('typeId', e.target.value); }} error={errors.typeId}>
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </Select>
        </div>
        <Input
          label="Title"
          required
          maxLength={255}
          data-autofocus
          value={draft.title}
          error={titleError}
          onChange={(e) => { set('title', e.target.value); }}
        />
        <TextArea
          label="Description"
          hint="Markdown is supported. Mention people with @handle."
          rows={5}
          value={draft.description}
          error={errors.description}
          onChange={(e) => { set('description', e.target.value); }}
        />
        <div className="form-grid">
          <Select label="Priority" value={draft.priority} error={errors.priority} onChange={(e) => { set('priority', e.target.value as NonNullable<Priority>); }}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {priorityLabel(p)}
              </option>
            ))}
          </Select>
          <Select label="Assignee" value={draft.assigneeId} error={errors.assigneeId} onChange={(e) => { set('assigneeId', e.target.value); }}>
            <option value="">Unassigned</option>
            {(members.data?.items ?? []).map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.displayName} (@{m.handle})
              </option>
            ))}
          </Select>
          <Select label="Milestone" value={draft.milestoneId} error={errors.milestoneId} onChange={(e) => { set('milestoneId', e.target.value); }}>
            <option value="">None</option>
            {(catalog.data?.milestones ?? []).filter((m) => m.state === 'OPEN').map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
          </Select>
          <Input label="Start date" type="date" value={draft.startDate} error={errors.startDate} onChange={(e) => { set('startDate', e.target.value); }} />
          <Input label="Due date" type="date" value={draft.dueDate} error={errors.dueDate} onChange={(e) => { set('dueDate', e.target.value); }} />
          <Input label="Estimate" type="number" min={0} step="0.5" inputMode="decimal" hint="Points or hours, as your team prefers." value={draft.estimate}
            error={errors.estimate} onChange={(e) => { set('estimate', e.target.value); }} />
        </div>
        {(catalog.data?.labels.length ?? 0) > 0 && (
          <fieldset className={`field-group ${styles.labels ?? ''}`}>
            <legend>Labels</legend>
            <div className={styles.labelGrid}>
              {catalog.data?.labels.map((label) => (
                <Checkbox
                  key={label.id}
                  label={label.name}
                  checked={draft.labelIds.includes(label.id)}
                  onChange={(e) => {
                    set('labelIds', e.target.checked ? [...draft.labelIds, label.id] : draft.labelIds.filter((id) => id !== label.id));
                  }}
                />
              ))}
            </div>
          </fieldset>
        )}
        {(fields.data ?? []).filter((f) => !f.archived).length > 0 && (
          <div className="form-grid">
            {(fields.data ?? []).filter((f) => !f.archived).map((field) => (
              <CustomFieldInput
                key={field.id}
                field={field}
                members={members.data?.items ?? []}
                value={draft.customValues[field.key]}
                error={errors[`customValues.${field.key}`]}
                onChange={(value) => { set('customValues', { ...draft.customValues, [field.key]: value }); }}
              />
            ))}
          </div>
        )}
      </form>
    </Dialog>
  );
}

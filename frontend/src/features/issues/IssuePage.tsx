import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { Bell, BellOff, Copy, Pencil, Trash2 } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { catalogQuery, fieldsQuery, issueQuery, keys, membersQuery } from '../../api/queries';
import { PRIORITIES, type FieldAccess, type FieldDefinition, type IssueView, type Member } from '../../api/types';
import { useOrgSlug } from '../../app/params';
import { describeError, isNotFound } from '../../lib/errors';
import { focusOnMount } from '../../lib/focus';
import { formatDateTime, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { LabelChip, priorityLabel, StatusBadge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { EmptyState } from '../../ui/EmptyState';
import { Checkbox, Input, Select, TextArea } from '../../ui/Field';
import { Markdown } from '../../ui/Markdown';
import { Page, Skeleton } from '../../ui/Page';
import { useToast } from '../../ui/Toast';
import { CustomFieldInput, formatCustomValue } from './CustomFieldInput';
import { IssueActivity } from './IssueActivity';
import { IssueAttachments } from './IssueAttachments';
import { IssueComments } from './IssueComments';
import { IssueRelations } from './IssueRelations';
import styles from './IssuePage.module.css';
import { useIssueUpdate } from './useIssueUpdate';

export function IssuePage({ issueKey }: { issueKey: string }) {
  const org = useOrgSlug();
  const issue = useQuery(issueQuery(org, issueKey));
  if (issue.isPending) {
    return (
      <Page>
        <Skeleton lines={10} label={`Loading ${issueKey}`} />
      </Page>
    );
  }
  if (issue.isError) {
    return (
      <Page>
        {isNotFound(issue.error) ? (
          <EmptyState icon={<Trash2 size={20} />} title={`${issueKey} isn’t available`}>
            It may have been deleted or moved, or you don’t have access to its project.{' '}
            <Link to="/$org/search" params={{ org }}>
              Search issues
            </Link>
          </EmptyState>
        ) : (
          <Alert tone="danger">{describeError(issue.error)}</Alert>
        )}
      </Page>
    );
  }
  return <IssueDetail org={org} issue={issue.data} />;
}

function access(issue: IssueView, field: string): FieldAccess {
  return issue.fieldAccess[field] ?? 'OPTIONAL';
}

function IssueDetail({ org, issue }: { org: string; issue: IssueView }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const update = useIssueUpdate(org, issue);
  const projectKey = issue.key.slice(0, issue.key.lastIndexOf('-'));
  const catalog = useQuery(catalogQuery(org, projectKey));
  const fields = useQuery(fieldsQuery(org, projectKey));
  const members = useQuery(membersQuery(org, '', 0, 200));
  const [editingTitle, setEditingTitle] = useState(false);
  const [title, setTitle] = useState(issue.title);
  const [editingDescription, setEditingDescription] = useState(false);
  const [description, setDescription] = useState(issue.description ?? '');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const can = issue.permissions;
  const editable = (field: string) => can.edit && access(issue, field) !== 'READ_ONLY' && access(issue, field) !== 'HIDDEN';
  const conflict = update.error && describeError(update.error).startsWith('Someone else');

  const watch = useMutation({
    mutationFn: (on: boolean) =>
      call(() =>
        on
          ? api.PUT('/api/v1/orgs/{org}/issues/{issueKey}/watch', { params: { path: { org, issueKey: issue.key } } })
          : api.DELETE('/api/v1/orgs/{org}/issues/{issueKey}/watch', { params: { path: { org, issueKey: issue.key } } }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.issue(org, issue.key) }),
  });
  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}/issues/{issueKey}', { params: { path: { org, issueKey: issue.key } } })),
    onSuccess: async () => {
      toast.success(`Deleted ${issue.key}`);
      queryClient.removeQueries({ queryKey: keys.issue(org, issue.key) });
      await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
      await navigate({ to: '/$org/p/$key', params: { org, key: projectKey } });
    },
  });

  const save = (changes: Record<string, unknown>, after?: () => void) => {
    update.mutate(changes, { onSuccess: after });
  };

  return (
    <Page wide>
      <div className={styles.header}>
        <nav aria-label="Breadcrumb" className={styles.breadcrumb}>
          <Link to="/$org/p/$key" params={{ org, key: projectKey }}>
            {issue.project.name}
          </Link>
          {issue.parent && (
            <>
              <span aria-hidden="true">/</span>
              <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.parent.key }}>
                {issue.parent.key}
              </Link>
            </>
          )}
          <span aria-hidden="true">/</span>
          <span className="issue-key" aria-current="page">
            {issue.key}
          </span>
        </nav>
        <div className="row">
          <Button
            size="sm"
            variant="ghost"
            icon={issue.watching ? <BellOff size={14} aria-hidden="true" /> : <Bell size={14} aria-hidden="true" />}
            loading={watch.isPending}
            onClick={() => { watch.mutate(!issue.watching); }}
          >
            {issue.watching ? 'Unwatch' : 'Watch'}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            icon={<Copy size={14} aria-hidden="true" />}
            onClick={() => {
              void navigator.clipboard.writeText(window.location.href).then(
                () => { toast.success('Link copied'); },
                () => { toast.error('Copying is not allowed in this browser.'); },
              );
            }}
          >
            Copy link
          </Button>
          {can.delete && (
            <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirmDelete(true); }}>
              Delete
            </Button>
          )}
        </div>
      </div>

      {conflict && (
        <Alert tone="warning" title="This issue changed while you were editing">
          Your last change was not saved.{' '}
          <Button size="sm" onClick={() => void queryClient.invalidateQueries({ queryKey: keys.issue(org, issue.key) }).then(() => { update.reset(); })}>
            Load the latest version
          </Button>
        </Alert>
      )}

      <div className={styles.layout}>
        <article className={styles.main}>
          {editingTitle ? (
            <form
              className="row"
              onSubmit={(e) => {
                e.preventDefault();
                if (title.trim()) {
                  save({ title: title.trim() }, () => { setEditingTitle(false); });
                }
              }}
            >
              <Input label="Title" hideLabel value={title} maxLength={255} ref={focusOnMount} onChange={(e) => { setTitle(e.target.value); }} className={styles.titleInput} />
              <Button type="submit" variant="primary" size="sm" loading={update.isPending}>
                Save
              </Button>
              <Button size="sm" onClick={() => { setEditingTitle(false); setTitle(issue.title); }}>
                Cancel
              </Button>
            </form>
          ) : (
            <h1 className={styles.title}>
              {issue.title}
              {can.edit && (
                <Button size="sm" variant="ghost" icon={<Pencil size={14} aria-hidden="true" />} aria-label="Edit title" onClick={() => { setTitle(issue.title); setEditingTitle(true); }} />
              )}
            </h1>
          )}

          {access(issue, 'description') !== 'HIDDEN' && (
            <section aria-labelledby="desc-heading" className={styles.section}>
              <div className={styles.sectionHeader}>
                <h2 id="desc-heading" className={styles.sectionTitle}>
                  Description {access(issue, 'description') === 'REQUIRED' && <span className="muted">(required)</span>}
                </h2>
                {editable('description') && !editingDescription && (
                  <Button size="sm" variant="ghost" icon={<Pencil size={14} aria-hidden="true" />} onClick={() => { setDescription(issue.description ?? ''); setEditingDescription(true); }}>
                    Edit
                  </Button>
                )}
              </div>
              {editingDescription ? (
                <form
                  className="stack"
                  onSubmit={(e) => {
                    e.preventDefault();
                    save({ description: description.trim() || null }, () => { setEditingDescription(false); });
                  }}
                >
                  <TextArea label="Description" hideLabel rows={8} value={description} ref={focusOnMount} hint="Markdown is supported." onChange={(e) => { setDescription(e.target.value); }} />
                  <div className="row">
                    <Button type="submit" variant="primary" size="sm" loading={update.isPending}>
                      Save
                    </Button>
                    <Button size="sm" onClick={() => { setEditingDescription(false); }}>
                      Cancel
                    </Button>
                  </div>
                </form>
              ) : issue.description ? (
                <Markdown source={issue.description} />
              ) : (
                <p className="muted">No description.</p>
              )}
            </section>
          )}

          <IssueRelations org={org} issue={issue} />
          <IssueAttachments org={org} issue={issue} />
          <IssueComments org={org} issue={issue} />
          <IssueActivity org={org} issueKey={issue.key} />
        </article>

        <aside className={styles.side} aria-label="Issue details">
          <section className={styles.statusBox} aria-label="Status">
            <StatusBadge name={issue.status.name} category={issue.status.category} />
            {can.transition && issue.transitions.length > 0 && (
              <div className={styles.transitions}>
                {issue.transitions.map((t) => (
                  <Button key={t.id} size="sm" loading={update.isPending && update.variables.statusId === t.to.id} onClick={() => { save({ statusId: t.to.id }); }}>
                    {t.name}
                  </Button>
                ))}
              </div>
            )}
          </section>

          <dl className={styles.fields}>
            <Field label="Assignee" access={access(issue, 'assignee')}>
              {can.assign && editable('assignee') ? (
                <Select label="Assignee" hideLabel value={issue.assignee?.id ?? ''} onChange={(e) => { save({ assigneeId: e.target.value || null }); }}>
                  <option value="">Unassigned</option>
                  {(members.data?.items ?? []).map((m) => (
                    <option key={m.userId} value={m.userId}>
                      {m.displayName}
                    </option>
                  ))}
                </Select>
              ) : (
                (issue.assignee?.displayName ?? 'Unassigned')
              )}
            </Field>
            <Field label="Priority" access={access(issue, 'priority')}>
              {editable('priority') ? (
                <Select label="Priority" hideLabel value={issue.priority ?? 'NONE'} onChange={(e) => { save({ priority: e.target.value }); }}>
                  {PRIORITIES.map((p) => (
                    <option key={p} value={p}>
                      {priorityLabel(p)}
                    </option>
                  ))}
                </Select>
              ) : (
                priorityLabel(issue.priority)
              )}
            </Field>
            <Field label="Type" access="OPTIONAL">
              {can.edit ? (
                <Select label="Type" hideLabel value={issue.type.id} onChange={(e) => { save({ typeId: e.target.value }); }}>
                  {(catalog.data?.issueTypes ?? [issue.type]).filter((t) => !t.archived && t.subtask === issue.type.subtask).map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </Select>
              ) : (
                issue.type.name
              )}
            </Field>
            <Field label="Labels" access={access(issue, 'labels')}>
              <LabelEditor issue={issue} labels={catalog.data?.labels ?? []} editable={editable('labels')} onSave={(ids) => { save({ labelIds: ids }); }} />
            </Field>
            <Field label="Milestone" access={access(issue, 'milestone')}>
              {editable('milestone') ? (
                <Select label="Milestone" hideLabel value={issue.milestone?.id ?? ''} onChange={(e) => { save({ milestoneId: e.target.value || null }); }}>
                  <option value="">None</option>
                  {(catalog.data?.milestones ?? []).map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.name}
                    </option>
                  ))}
                </Select>
              ) : (
                (issue.milestone?.name ?? 'None')
              )}
            </Field>
            <Field label="Start date" access={access(issue, 'startDate')}>
              {editable('startDate') ? (
                <Input label="Start date" hideLabel type="date" defaultValue={issue.startDate ?? ''} key={`s-${String(issue.version)}`}
                  onBlur={(e) => { if (e.target.value !== (issue.startDate ?? '')) { save({ startDate: e.target.value || null }); } }} />
              ) : (
                (issue.startDate ?? 'Not set')
              )}
            </Field>
            <Field label="Due date" access={access(issue, 'dueDate')}>
              {editable('dueDate') ? (
                <Input label="Due date" hideLabel type="date" defaultValue={issue.dueDate ?? ''} key={`d-${String(issue.version)}`}
                  onBlur={(e) => { if (e.target.value !== (issue.dueDate ?? '')) { save({ dueDate: e.target.value || null }); } }} />
              ) : (
                (issue.dueDate ?? 'Not set')
              )}
            </Field>
            <Field label="Estimate" access={access(issue, 'estimate')}>
              {editable('estimate') ? (
                <Input label="Estimate" hideLabel type="number" min={0} step="0.5" defaultValue={issue.estimate ?? ''} key={`e-${String(issue.version)}`}
                  onBlur={(e) => {
                    const next = e.target.value === '' ? null : Number(e.target.value);
                    if (next !== (issue.estimate ?? null)) {
                      save({ estimate: next });
                    }
                  }} />
              ) : (
                (issue.estimate ?? 'Not set')
              )}
            </Field>
            {(fields.data ?? []).filter((f) => !f.archived && access(issue, `cf:${f.key}`) !== 'HIDDEN').map((field) => (
              <Field key={field.id} label={field.name} access={access(issue, `cf:${field.key}`)}>
                {editable(`cf:${field.key}`) ? (
                  <InlineCustomField
                    field={field}
                    value={issue.customValues[field.key]}
                    members={members.data?.items ?? []}
                    onSave={(value) => { save({ customValues: { [field.key]: value } }); }}
                  />
                ) : (
                  formatCustomValue(field, issue.customValues[field.key], members.data?.items ?? [])
                )}
              </Field>
            ))}
            <Field label="Reporter" access="READ_ONLY">
              {issue.reporter?.displayName ?? 'Former member'}
            </Field>
            <Field label="Watchers" access="READ_ONLY">
              {issue.watchers.length === 0 ? 'None' : issue.watchers.map((w) => w.displayName).join(', ')}
            </Field>
            <Field label="Created" access="READ_ONLY">
              <time dateTime={issue.createdAt} title={formatDateTime(issue.createdAt)}>
                {timeAgo(issue.createdAt)}
              </time>
            </Field>
            <Field label="Updated" access="READ_ONLY">
              <time dateTime={issue.updatedAt} title={formatDateTime(issue.updatedAt)}>
                {timeAgo(issue.updatedAt)}
              </time>
            </Field>
            {issue.resolvedAt && (
              <Field label="Resolved" access="READ_ONLY">
                {formatDateTime(issue.resolvedAt)}
              </Field>
            )}
          </dl>
        </aside>
      </div>

      <Dialog
        open={confirmDelete}
        size="sm"
        title={`Delete ${issue.key}?`}
        description="The issue, its sub-tasks, comments and attachments will be permanently deleted."
        onClose={() => { setConfirmDelete(false); }}
        footer={
          <>
            <Button onClick={() => { setConfirmDelete(false); }}>Cancel</Button>
            <Button variant="primary" loading={remove.isPending} onClick={() => { remove.mutate(); }}>
              Delete issue
            </Button>
          </>
        }
      >
        {remove.isError && <Alert tone="danger">{describeError(remove.error)}</Alert>}
        <p>This cannot be undone.</p>
      </Dialog>
    </Page>
  );
}

const IMMEDIATE_TYPES = new Set(['SELECT', 'MULTI_SELECT', 'CHECKBOX', 'USER']);

/** Choice fields save at once; typed fields save when focus leaves the field. */
function InlineCustomField({
  field,
  value,
  members,
  onSave,
}: {
  field: FieldDefinition;
  value: unknown;
  members: Member[];
  onSave: (value: unknown) => void;
}) {
  const [local, setLocal] = useState<unknown>(value);
  const [synced, setSynced] = useState<unknown>(value);
  if (synced !== value) {
    // The saved value changed (e.g. after a save or a realtime refresh): show it.
    setSynced(value);
    setLocal(value);
  }
  const immediate = IMMEDIATE_TYPES.has(field.type);
  return (
    <CustomFieldInput
      hideLabel
      field={field}
      value={local}
      members={members}
      onBlur={() => {
        if (!immediate && JSON.stringify(local ?? null) !== JSON.stringify(value ?? null)) {
          onSave(local);
        }
      }}
      onChange={(next) => {
        setLocal(next);
        if (immediate) {
          onSave(next);
        }
      }}
    />
  );
}

function Field({ label, access: fieldAccess, children }: { label: string; access: FieldAccess; children: ReactNode }) {
  if (fieldAccess === 'HIDDEN') {
    return null;
  }
  return (
    <div className={styles.field}>
      <dt>
        {label}
        {fieldAccess === 'REQUIRED' && <span className={styles.required}> required</span>}
      </dt>
      <dd>{children}</dd>
    </div>
  );
}

function LabelEditor({
  issue,
  labels,
  editable,
  onSave,
}: {
  issue: IssueView;
  labels: { id: string; name: string; color?: string | null }[];
  editable: boolean;
  onSave: (ids: string[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const current = issue.labels ?? [];
  const [selected, setSelected] = useState<string[]>([]);
  return (
    <div className={styles.labels}>
      {current.length === 0 && <span className="muted">None</span>}
      {current.map((l) => (
        <LabelChip key={l.id} name={l.name} color={l.color} />
      ))}
      {editable && labels.length > 0 && (
        <Button size="sm" variant="ghost" onClick={() => { setSelected(current.map((l) => l.id)); setOpen(true); }}>
          Edit
        </Button>
      )}
      <Dialog
        open={open}
        size="sm"
        title="Labels"
        onClose={() => { setOpen(false); }}
        footer={
          <>
            <Button onClick={() => { setOpen(false); }}>Cancel</Button>
            <Button variant="primary" onClick={() => { onSave(selected); setOpen(false); }}>
              Apply
            </Button>
          </>
        }
      >
        <fieldset className="field-group">
          <legend className="visually-hidden">Labels</legend>
          {labels.map((label) => (
            <Checkbox
              key={label.id}
              label={label.name}
              checked={selected.includes(label.id)}
              onChange={(e) => { setSelected(e.target.checked ? [...selected, label.id] : selected.filter((id) => id !== label.id)); }}
            />
          ))}
        </fieldset>
      </Dialog>
    </div>
  );
}

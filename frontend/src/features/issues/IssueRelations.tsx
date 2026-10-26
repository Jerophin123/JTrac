import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { Link2, Plus, X } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { IssueView } from '../../api/types';
import { describeError } from '../../lib/errors';
import { Alert } from '../../ui/Alert';
import { StatusBadge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Input, Select } from '../../ui/Field';
import { CreateIssueDialog } from './CreateIssueDialog';
import styles from './IssueSections.module.css';

const LINK_LABELS: Record<string, { outgoing: string; incoming: string }> = {
  BLOCKS: { outgoing: 'Blocks', incoming: 'Blocked by' },
  RELATES: { outgoing: 'Relates to', incoming: 'Relates to' },
  DUPLICATES: { outgoing: 'Duplicates', incoming: 'Duplicated by' },
};

export function IssueRelations({ org, issue }: { org: string; issue: IssueView }) {
  const queryClient = useQueryClient();
  const [creatingSubtask, setCreatingSubtask] = useState(false);
  const [linking, setLinking] = useState(false);
  const [target, setTarget] = useState('');
  const [type, setType] = useState<'BLOCKS' | 'RELATES' | 'DUPLICATES'>('RELATES');
  const projectKey = issue.key.slice(0, issue.key.lastIndexOf('-'));
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.issue(org, issue.key) });

  const addLink = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/issues/{issueKey}/links', {
          params: { path: { org, issueKey: issue.key } },
          body: { targetKey: target.trim().toUpperCase(), type },
        }),
      ),
    onSuccess: async (updated) => {
      queryClient.setQueryData(keys.issue(org, issue.key), updated);
      setLinking(false);
      setTarget('');
      await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
    },
  });
  const removeLink = useMutation({
    mutationFn: (linkId: string) =>
      call(() => api.DELETE('/api/v1/orgs/{org}/issues/{issueKey}/links/{linkId}', { params: { path: { org, issueKey: issue.key, linkId } } })),
    onSuccess: refresh,
  });

  const canEdit = issue.permissions.edit;
  return (
    <>
      {!issue.type.subtask && (
        <section aria-labelledby="subtasks-heading" className={styles.section}>
          <div className={styles.header}>
            <h2 id="subtasks-heading" className={styles.title}>
              Sub-tasks
              {issue.subtasks.length > 0 && (
                <span className="muted">
                  {' '}
                  {issue.subtasks.filter((s) => s.status.category === 'DONE' || s.status.category === 'CANCELED').length}/{issue.subtasks.length} done
                </span>
              )}
            </h2>
            {issue.permissions.edit && (
              <Button size="sm" variant="ghost" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setCreatingSubtask(true); }}>
                Add sub-task
              </Button>
            )}
          </div>
          {issue.subtasks.length === 0 ? (
            <p className="muted">No sub-tasks.</p>
          ) : (
            <ul className={styles.rows}>
              {issue.subtasks.map((sub) => (
                <li key={sub.key} className={styles.row}>
                  <span className="issue-key">{sub.key}</span>
                  <Link to="/$org/issue/$issueKey" params={{ org, issueKey: sub.key }} className={styles.rowTitle}>
                    {sub.title}
                  </Link>
                  <span className="muted">{sub.assignee?.displayName ?? ''}</span>
                  <StatusBadge name={sub.status.name} category={sub.status.category} />
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <section aria-labelledby="links-heading" className={styles.section}>
        <div className={styles.header}>
          <h2 id="links-heading" className={styles.title}>
            Linked issues
          </h2>
          {canEdit && (
            <Button size="sm" variant="ghost" icon={<Link2 size={14} aria-hidden="true" />} onClick={() => { setLinking(true); }}>
              Link issue
            </Button>
          )}
        </div>
        {issue.links.length === 0 ? (
          <p className="muted">No linked issues.</p>
        ) : (
          <ul className={styles.rows}>
            {issue.links.map((link) => (
              <li key={link.id} className={styles.row}>
                <span className={styles.linkType}>{LINK_LABELS[link.type]?.[link.direction] ?? link.type}</span>
                <span className="issue-key">{link.key}</span>
                <Link to="/$org/issue/$issueKey" params={{ org, issueKey: link.key }} className={styles.rowTitle}>
                  {link.title}
                </Link>
                <StatusBadge name={link.status.name} category={link.status.category} />
                {canEdit && (
                  <Button
                    size="sm"
                    variant="ghost"
                    icon={<X size={14} aria-hidden="true" />}
                    aria-label={`Remove link to ${link.key}`}
                    loading={removeLink.isPending && removeLink.variables === link.id}
                    onClick={() => { removeLink.mutate(link.id); }}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <CreateIssueDialog org={org} open={creatingSubtask} defaultProject={projectKey} parentKey={issue.key} onClose={() => { setCreatingSubtask(false); }} />
      <Dialog
        open={linking}
        size="sm"
        title={`Link ${issue.key} to another issue`}
        onClose={() => { setLinking(false); }}
        footer={
          <>
            <Button onClick={() => { setLinking(false); }}>Cancel</Button>
            <Button variant="primary" loading={addLink.isPending} disabled={!target.trim()} type="submit" form="link-form">
              Link
            </Button>
          </>
        }
      >
        {addLink.isError && <Alert tone="danger">{describeError(addLink.error)}</Alert>}
        <form
          id="link-form"
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            addLink.mutate();
          }}
        >
          <Select label="Relationship" value={type} onChange={(e) => { setType(e.target.value as typeof type); }}>
            <option value="RELATES">Relates to</option>
            <option value="BLOCKS">Blocks</option>
            <option value="DUPLICATES">Duplicates</option>
          </Select>
          <Input label="Issue key" placeholder={`${projectKey}-42`} value={target} onChange={(e) => { setTarget(e.target.value); }} data-autofocus />
        </form>
      </Dialog>
    </>
  );
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2, X } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys, membersQuery } from '../../api/queries';
import { PRIORITIES, type BulkResult } from '../../api/types';
import { describeError } from '../../lib/errors';
import { pluralize } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { priorityLabel } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { Select } from '../../ui/Field';
import { useToast } from '../../ui/Toast';
import styles from './BulkBar.module.css';

interface BulkBarProps {
  org: string;
  selected: Set<string>;
  onClear: () => void;
}

/** Applies one change to every selected issue; each issue is checked individually by the server. */
export function BulkBar({ org, selected, onClear }: BulkBarProps) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const members = useQuery(membersQuery(org, '', 0, 200));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [failures, setFailures] = useState<BulkResult[]>([]);

  const bulk = useMutation({
    mutationFn: (body: { changes?: Record<string, unknown>; delete?: boolean }) =>
      call(() => api.POST('/api/v1/orgs/{org}/issues/bulk', { params: { path: { org } }, body: { keys: [...selected], ...body } })),
    onSuccess: async (results) => {
      const failed = results.filter((r) => !r.ok);
      const done = results.length - failed.length;
      setFailures(failed);
      if (done > 0) {
        toast.success(`Updated ${pluralize(done, 'issue')}`);
      }
      setConfirmDelete(false);
      await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
      if (failed.length === 0) {
        onClear();
      }
    },
  });

  if (selected.size === 0) {
    return null;
  }
  return (
    <div className={styles.bar} role="region" aria-label="Bulk actions">
      <strong>{pluralize(selected.size, 'issue')} selected</strong>
      <Select
        label="Set priority"
        hideLabel
        value=""
        disabled={bulk.isPending}
        onChange={(e) => {
          if (e.target.value) {
            bulk.mutate({ changes: { priority: e.target.value } });
          }
        }}
      >
        <option value="">Set priority…</option>
        {PRIORITIES.map((p) => (
          <option key={p} value={p}>
            {priorityLabel(p)}
          </option>
        ))}
      </Select>
      <Select
        label="Set assignee"
        hideLabel
        value=""
        disabled={bulk.isPending}
        onChange={(e) => {
          if (e.target.value) {
            bulk.mutate({ changes: { assigneeId: e.target.value === 'none' ? null : e.target.value } });
          }
        }}
      >
        <option value="">Assign to…</option>
        <option value="none">Unassigned</option>
        {(members.data?.items ?? []).map((m) => (
          <option key={m.userId} value={m.userId}>
            {m.displayName}
          </option>
        ))}
      </Select>
      <Button variant="ghost" size="sm" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirmDelete(true); }}>
        Delete
      </Button>
      <Button variant="ghost" size="sm" icon={<X size={14} aria-hidden="true" />} onClick={onClear}>
        Clear selection
      </Button>
      {bulk.isError && <Alert tone="danger">{describeError(bulk.error)}</Alert>}
      {failures.length > 0 && (
        <Alert tone="warning" title={`${pluralize(failures.length, 'issue')} could not be changed`}>
          <ul>
            {failures.map((f) => (
              <li key={f.key}>
                <span className="issue-key">{f.key}</span>: {f.error ?? 'Not allowed'}
              </li>
            ))}
          </ul>
        </Alert>
      )}
      <Dialog
        open={confirmDelete}
        size="sm"
        title={`Delete ${pluralize(selected.size, 'issue')}?`}
        description="Deleted issues, their sub-tasks, comments and attachments cannot be restored."
        onClose={() => { setConfirmDelete(false); }}
        footer={
          <>
            <Button onClick={() => { setConfirmDelete(false); }}>Cancel</Button>
            <Button variant="primary" loading={bulk.isPending} onClick={() => { bulk.mutate({ delete: true }); }}>
              Delete issues
            </Button>
          </>
        }
      >
        <p>Only issues you are allowed to delete will be removed.</p>
      </Dialog>
    </div>
  );
}

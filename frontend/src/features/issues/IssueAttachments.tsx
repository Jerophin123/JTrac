import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, FileText, Paperclip, Trash2 } from 'lucide-react';
import { useRef, useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { attachmentsQuery, keys } from '../../api/queries';
import type { IssueView } from '../../api/types';
import { describeError } from '../../lib/errors';
import { formatBytes, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { useToast } from '../../ui/Toast';
import styles from './IssueSections.module.css';

const MAX_BYTES = 25 * 1024 * 1024;

export function IssueAttachments({ org, issue }: { org: string; issue: IssueView }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const attachments = useQuery(attachmentsQuery(org, issue.key));
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const base = `/api/v1/orgs/${encodeURIComponent(org)}/issues/${encodeURIComponent(issue.key)}/attachments`;

  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (file.size > MAX_BYTES) {
        throw new Error(`${file.name} is larger than 25 MB.`);
      }
      return call(() =>
        api.POST('/api/v1/orgs/{org}/issues/{issueKey}/attachments', {
          params: { path: { org, issueKey: issue.key } },
          body: { file: file as unknown as string },
          bodySerializer: () => {
            const data = new FormData();
            data.append('file', file);
            return data;
          },
        }),
      );
    },
    onSuccess: async (attachment) => {
      toast.success(`Attached ${attachment.fileName}`);
      await queryClient.invalidateQueries({ queryKey: keys.attachments(org, issue.key) });
      await queryClient.invalidateQueries({ queryKey: keys.activity(org, issue.key) });
    },
    onError: (error) => {
      toast.error(error instanceof Error && !('status' in error) ? error.message : describeError(error));
    },
  });

  const remove = useMutation({
    mutationFn: (id: string) =>
      call(() =>
        api.DELETE('/api/v1/orgs/{org}/issues/{issueKey}/attachments/{attachmentId}', {
          params: { path: { org, issueKey: issue.key, attachmentId: id } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.attachments(org, issue.key) }),
    onError: (error) => {
      toast.error(describeError(error));
    },
  });

  const uploadAll = (files: FileList | null) => {
    for (const file of Array.from(files ?? [])) {
      upload.mutate(file);
    }
  };

  return (
    <section aria-labelledby="attachments-heading" className={styles.section}>
      <div className={styles.header}>
        <h2 id="attachments-heading" className={styles.title}>
          Attachments {attachments.data && attachments.data.length > 0 && <span className="muted">({attachments.data.length})</span>}
        </h2>
        {issue.permissions.attach && (
          <Button size="sm" variant="ghost" icon={<Paperclip size={14} aria-hidden="true" />} loading={upload.isPending} onClick={() => input.current?.click()}>
            Attach files
          </Button>
        )}
      </div>
      <input
        ref={input}
        type="file"
        multiple
        hidden
        onChange={(e) => {
          uploadAll(e.target.files);
          e.target.value = '';
        }}
      />
      {attachments.isError && <Alert tone="danger">{describeError(attachments.error)}</Alert>}
      {attachments.data && attachments.data.length > 0 && (
        <ul className={styles.rows}>
          {attachments.data.map((file) => (
            <li key={file.id} className={styles.row}>
              <FileText size={16} aria-hidden="true" />
              <a className={styles.rowTitle} href={`${base}/${file.id}/content${file.inline ? '?inline=true' : ''}`} target={file.inline ? '_blank' : undefined} rel="noopener">
                {file.fileName}
              </a>
              <span className="muted">
                {formatBytes(file.sizeBytes)} · {file.uploadedBy ?? 'Former member'} · {timeAgo(file.createdAt)}
              </span>
              <a href={`${base}/${file.id}/content`} aria-label={`Download ${file.fileName}`} className="muted">
                <Download size={15} aria-hidden="true" />
              </a>
              {file.canDelete && (
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<Trash2 size={14} aria-hidden="true" />}
                  aria-label={`Delete ${file.fileName}`}
                  loading={remove.isPending && remove.variables === file.id}
                  onClick={() => { remove.mutate(file.id); }}
                />
              )}
            </li>
          ))}
        </ul>
      )}
      {issue.permissions.attach && (
        <button
          type="button"
          className={styles.drop}
          data-active={dragging || undefined}
          onClick={() => { input.current?.click(); }}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => { setDragging(false); }}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            uploadAll(e.dataTransfer.files);
          }}
        >
          Drop files here to attach them (up to 25 MB each), or click to browse.
        </button>
      )}
    </section>
  );
}

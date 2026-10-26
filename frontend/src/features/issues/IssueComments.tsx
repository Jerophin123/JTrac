import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { commentsQuery, keys } from '../../api/queries';
import type { Comment, IssueView } from '../../api/types';
import { describeError } from '../../lib/errors';
import { focusOnMount } from '../../lib/focus';
import { formatDateTime, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { Button } from '../../ui/Button';
import { TextArea } from '../../ui/Field';
import { Markdown } from '../../ui/Markdown';
import { Skeleton } from '../../ui/Page';
import { useToast } from '../../ui/Toast';
import styles from './IssueSections.module.css';

export function IssueComments({ org, issue }: { org: string; issue: IssueView }) {
  const queryClient = useQueryClient();
  const comments = useQuery(commentsQuery(org, issue.key));
  const [body, setBody] = useState('');
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.comments(org, issue.key) });
    await queryClient.invalidateQueries({ queryKey: keys.issue(org, issue.key) });
  };
  const add = useMutation({
    mutationFn: () =>
      call(() => api.POST('/api/v1/orgs/{org}/issues/{issueKey}/comments', { params: { path: { org, issueKey: issue.key } }, body: { body: body.trim() } })),
    onSuccess: async () => {
      setBody('');
      await refresh();
    },
  });

  return (
    <section aria-labelledby="comments-heading" className={styles.section}>
      <div className={styles.header}>
        <h2 id="comments-heading" className={styles.title}>
          Comments {(comments.data?.length ?? 0) > 0 && <span className="muted">({comments.data?.length})</span>}
        </h2>
      </div>
      {comments.isPending && <Skeleton lines={3} label="Loading comments" />}
      {comments.isError && <Alert tone="danger">{describeError(comments.error)}</Alert>}
      {comments.data?.length === 0 && <p className="muted">No comments yet.</p>}
      {comments.data && comments.data.length > 0 && (
        <ol className={styles.comments}>
          {comments.data.map((comment) => (
            <CommentItem key={comment.id} org={org} issueKey={issue.key} comment={comment} onChanged={refresh} />
          ))}
        </ol>
      )}
      {issue.permissions.comment && (
        <form
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (body.trim()) {
              add.mutate();
            }
          }}
        >
          {add.isError && <Alert tone="danger">{describeError(add.error)}</Alert>}
          <TextArea
            label="Add a comment"
            rows={4}
            hint="Markdown supported. Mention someone with @handle to notify them. Ctrl+Enter to send."
            value={body}
            maxLength={50_000}
            onChange={(e) => { setBody(e.target.value); }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && body.trim()) {
                add.mutate();
              }
            }}
          />
          <div>
            <Button type="submit" variant="primary" loading={add.isPending} disabled={!body.trim()}>
              Comment
            </Button>
          </div>
        </form>
      )}
    </section>
  );
}

function CommentItem({ org, issueKey, comment, onChanged }: { org: string; issueKey: string; comment: Comment; onChanged: () => Promise<void> }) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(comment.body);
  const path = { org, issueKey, commentId: comment.id };
  const edit = useMutation({
    mutationFn: () => call(() => api.PATCH('/api/v1/orgs/{org}/issues/{issueKey}/comments/{commentId}', { params: { path }, body: { body: text.trim(), version: comment.version } })),
    onSuccess: async () => {
      setEditing(false);
      await onChanged();
    },
  });
  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}/issues/{issueKey}/comments/{commentId}', { params: { path } })),
    onSuccess: onChanged,
    onError: (error) => {
      toast.error(describeError(error));
    },
  });
  const author = comment.author?.displayName ?? 'Former member';
  return (
    <li className={styles.comment}>
      <Avatar name={author} />
      <div className={styles.commentBody}>
        <div className={styles.commentMeta}>
          <strong>{author}</strong>
          <time dateTime={comment.createdAt} title={formatDateTime(comment.createdAt)}>
            {timeAgo(comment.createdAt)}
          </time>
          {comment.editedAt && <span title={formatDateTime(comment.editedAt)}>(edited)</span>}
          <span className={styles.commentActions}>
            {comment.canEdit && !editing && (
              <Button size="sm" variant="ghost" onClick={() => { setText(comment.body); setEditing(true); }}>
                Edit
              </Button>
            )}
            {comment.canDelete && (
              <Button size="sm" variant="ghost" loading={remove.isPending} onClick={() => { remove.mutate(); }}>
                Delete
              </Button>
            )}
          </span>
        </div>
        {editing ? (
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              edit.mutate();
            }}
          >
            {edit.isError && <Alert tone="danger">{describeError(edit.error)}</Alert>}
            <TextArea label="Edit comment" hideLabel rows={4} value={text} onChange={(e) => { setText(e.target.value); }} ref={focusOnMount} />
            <div className="row">
              <Button type="submit" size="sm" variant="primary" loading={edit.isPending} disabled={!text.trim()}>
                Save
              </Button>
              <Button size="sm" onClick={() => { setEditing(false); }}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <Markdown source={comment.body} />
        )}
      </div>
    </li>
  );
}

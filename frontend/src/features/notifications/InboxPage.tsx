import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { AtSign, Bell, CheckCheck, GitCommitHorizontal, MessageSquare, UserPlus } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { Notification } from '../../api/types';
import { useOrgSlug } from '../../app/params';
import { describeError } from '../../lib/errors';
import { formatDateTime, timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { EmptyState } from '../../ui/EmptyState';
import { Page, PageHeader, Skeleton } from '../../ui/Page';
import { Segmented } from '../../ui/Tabs';
import styles from './InboxPage.module.css';

const ICONS: Record<string, ReactNode> = {
  ASSIGNED: <UserPlus size={16} aria-hidden="true" />,
  MENTIONED: <AtSign size={16} aria-hidden="true" />,
  COMMENTED: <MessageSquare size={16} aria-hidden="true" />,
  STATUS_CHANGED: <GitCommitHorizontal size={16} aria-hidden="true" />,
  UPDATED: <Bell size={16} aria-hidden="true" />,
};

export function InboxPage() {
  const org = useOrgSlug();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [unreadOnly, setUnreadOnly] = useState(true);
  const [page, setPage] = useState(0);
  const list = useQuery({
    queryKey: [...keys.notifications(org, unreadOnly), page],
    queryFn: () =>
      call(() => api.GET('/api/v1/orgs/{org}/notifications', { params: { path: { org }, query: { unread: unreadOnly, page, size: 30 } } })),
    placeholderData: keepPreviousData,
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['org', org, 'notifications'] });
  const markRead = useMutation({
    mutationFn: (id: string) => call(() => api.POST('/api/v1/orgs/{org}/notifications/{id}/read', { params: { path: { org, id } } })),
    onSuccess: refresh,
  });
  const markAll = useMutation({
    mutationFn: () => call(() => api.POST('/api/v1/orgs/{org}/notifications/read-all', { params: { path: { org } } })),
    onSuccess: refresh,
  });

  const open = (n: Notification) => {
    if (!n.read) {
      markRead.mutate(n.id);
    }
    if (n.issueKey) {
      void navigate({ to: '/$org/issue/$issueKey', params: { org, issueKey: n.issueKey } });
    }
  };

  return (
    <Page>
      <PageHeader
        title="Inbox"
        description="Assignments, mentions and updates on issues you watch. Choose what you receive in Settings → Notifications."
        actions={
          <Button icon={<CheckCheck size={16} aria-hidden="true" />} loading={markAll.isPending} onClick={() => { markAll.mutate(); }}>
            Mark all as read
          </Button>
        }
      />
      <Segmented
        label="Show"
        value={unreadOnly ? 'unread' : 'all'}
        onChange={(value) => {
          setUnreadOnly(value === 'unread');
          setPage(0);
        }}
        options={[
          { value: 'unread', label: 'Unread' },
          { value: 'all', label: 'All' },
        ]}
      />
      {list.isPending && <Skeleton lines={6} label="Loading notifications" />}
      {list.isError && <Alert tone="danger">{describeError(list.error)}</Alert>}
      {list.data?.items.length === 0 && (
        <EmptyState icon={<Bell size={20} />} title={unreadOnly ? 'You’re all caught up' : 'No notifications yet'}>
          You’ll be notified when someone assigns you an issue, mentions you, or updates an issue you watch.
        </EmptyState>
      )}
      {list.data && list.data.items.length > 0 && (
        <ul className={styles.list}>
          {list.data.items.map((n) => (
            <li key={n.id} className={styles.item} data-unread={!n.read || undefined}>
              <span className={styles.icon}>{ICONS[n.kind] ?? <Bell size={16} aria-hidden="true" />}</span>
              <button type="button" className={styles.body} onClick={() => { open(n); }}>
                <span className={styles.title}>
                  {!n.read && <span className="visually-hidden">Unread: </span>}
                  {n.title}
                </span>
                {n.body && <span className={styles.text}>{n.body}</span>}
              </button>
              <time className={styles.time} dateTime={n.createdAt} title={formatDateTime(n.createdAt)}>
                {timeAgo(n.createdAt)}
              </time>
              {!n.read && (
                <Button size="sm" variant="ghost" onClick={() => { markRead.mutate(n.id); }} aria-label={`Mark “${n.title}” as read`}>
                  Mark read
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {list.data && list.data.total > 30 && (
        <div className="row">
          <Button size="sm" disabled={page === 0} onClick={() => { setPage(page - 1); }}>
            Newer
          </Button>
          <Button size="sm" disabled={(page + 1) * 30 >= list.data.total} onClick={() => { setPage(page + 1); }}>
            Older
          </Button>
        </div>
      )}
    </Page>
  );
}

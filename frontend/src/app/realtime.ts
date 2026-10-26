import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { keys } from '../api/queries';

interface IssueChangedEvent {
  issueKey: string;
  kind: string;
}

/**
 * Subscribes to the organization's server-sent events. Events only say *what* changed;
 * the affected queries are refetched through the normal, permission-checked API.
 * EventSource reconnects automatically after network interruptions.
 */
export function useRealtime(org: string): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (typeof EventSource === 'undefined') {
      return undefined;
    }
    const source = new EventSource(`/api/v1/orgs/${encodeURIComponent(org)}/events`, { withCredentials: true });
    const onIssue = (event: MessageEvent<string>) => {
      let data: IssueChangedEvent;
      try {
        data = JSON.parse(event.data) as IssueChangedEvent;
      } catch {
        return;
      }
      void queryClient.invalidateQueries({ queryKey: keys.issue(org, data.issueKey) });
      void queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
    };
    const onNotification = () => {
      void queryClient.invalidateQueries({ queryKey: ['org', org, 'notifications'] });
    };
    source.addEventListener('issue.changed', onIssue);
    source.addEventListener('notification.created', onNotification);
    return () => {
      source.removeEventListener('issue.changed', onIssue);
      source.removeEventListener('notification.created', onNotification);
      source.close();
    };
  }, [org, queryClient]);
}

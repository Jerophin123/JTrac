import { useQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Command } from 'cmdk';
import { Bell, FolderKanban, LayoutDashboard, Plus, Search, Settings, UserRound } from 'lucide-react';
import { useState } from 'react';
import { call } from '../api/call';
import { api } from '../api/client';
import { keys } from '../api/queries';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { StatusBadge } from '../ui/Badge';
import { Dialog } from '../ui/Dialog';
import styles from './CommandPalette.module.css';

interface CommandPaletteProps {
  org: string;
  open: boolean;
  onClose: () => void;
  onCreateIssue: () => void;
}

/** Ctrl/⌘ + K: jump to issues, projects and people, or run common actions. */
export function CommandPalette({ org, open, onClose, onCreateIssue }: CommandPaletteProps) {
  const [query, setQuery] = useState('');
  const debounced = useDebouncedValue(query.trim(), 150);
  const navigate = useNavigate();
  const results = useQuery({
    queryKey: keys.globalSearch(org, debounced),
    enabled: open && debounced.length > 0,
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/search', { params: { path: { org }, query: { q: debounced } } })),
    staleTime: 10_000,
  });

  const close = () => {
    setQuery('');
    onClose();
  };

  const go = (action: () => Promise<void>) => {
    close();
    void action();
  };

  const data = debounced ? results.data : undefined;
  return (
    <Dialog open={open} title="Go to…" onClose={close} size="lg">
      <Command label="Command palette" shouldFilter={false} className={styles.command}>
        <div className={styles.inputRow}>
          <Search size={16} aria-hidden="true" />
          <Command.Input
            value={query}
            onValueChange={setQuery}
            placeholder="Search issues by key or text, projects, people…"
            className={styles.input}
            data-autofocus
          />
        </div>
        <Command.List className={styles.list}>
          {debounced && results.isFetching && !data && <Command.Loading>Searching…</Command.Loading>}
          {debounced && data && data.issues.length + data.projects.length + data.people.length === 0 && (
            <Command.Empty className={styles.empty}>No results for “{debounced}”.</Command.Empty>
          )}
          {data && data.issues.length > 0 && (
            <Command.Group heading="Issues" className={styles.group}>
              {data.issues.map((issue) => (
                <Command.Item
                  key={issue.key}
                  value={`issue-${issue.key}`}
                  className={styles.item}
                  onSelect={() => { go(() => navigate({ to: '/$org/issue/$issueKey', params: { org, issueKey: issue.key } })); }}
                >
                  <span className={styles.key}>{issue.key}</span>
                  <span className={styles.label}>{issue.title}</span>
                  {issue.status && <StatusBadge name={issue.status} category={issue.statusCategory ?? 'TODO'} />}
                </Command.Item>
              ))}
            </Command.Group>
          )}
          {data && data.projects.length > 0 && (
            <Command.Group heading="Projects" className={styles.group}>
              {data.projects.map((project) => (
                <Command.Item
                  key={project.key}
                  value={`project-${project.key}`}
                  className={styles.item}
                  onSelect={() => { go(() => navigate({ to: '/$org/p/$key', params: { org, key: project.key } })); }}
                >
                  <FolderKanban size={15} aria-hidden="true" />
                  <span className={styles.label}>{project.name}</span>
                  <span className={styles.key}>{project.key}</span>
                </Command.Item>
              ))}
            </Command.Group>
          )}
          {data && data.people.length > 0 && (
            <Command.Group heading="People" className={styles.group}>
              {data.people.map((person) => (
                <Command.Item
                  key={person.id}
                  value={`person-${person.id}`}
                  className={styles.item}
                  onSelect={() => {
                    go(() => navigate({ to: '/$org/search', params: { org }, search: { assignee: person.id } }));
                  }}
                >
                  <UserRound size={15} aria-hidden="true" />
                  <span className={styles.label}>{person.displayName}</span>
                  <span className={styles.key}>@{person.handle}</span>
                </Command.Item>
              ))}
            </Command.Group>
          )}
          {!debounced && (
            <Command.Group heading="Actions" className={styles.group}>
              <Command.Item value="create-issue" className={styles.item} onSelect={() => { close(); onCreateIssue(); }}>
                <Plus size={15} aria-hidden="true" />
                <span className={styles.label}>Create issue</span>
                <kbd className={styles.kbd}>C</kbd>
              </Command.Item>
              <Command.Item value="inbox" className={styles.item} onSelect={() => { go(() => navigate({ to: '/$org/inbox', params: { org } })); }}>
                <Bell size={15} aria-hidden="true" />
                <span className={styles.label}>Open inbox</span>
              </Command.Item>
              <Command.Item value="search" className={styles.item} onSelect={() => { go(() => navigate({ to: '/$org/search', params: { org } })); }}>
                <Search size={15} aria-hidden="true" />
                <span className={styles.label}>Search all issues</span>
              </Command.Item>
              <Command.Item value="dashboards" className={styles.item} onSelect={() => { go(() => navigate({ to: '/$org/dashboards', params: { org } })); }}>
                <LayoutDashboard size={15} aria-hidden="true" />
                <span className={styles.label}>Dashboards</span>
              </Command.Item>
              <Command.Item value="settings" className={styles.item} onSelect={() => { go(() => navigate({ to: '/$org/settings', params: { org } })); }}>
                <Settings size={15} aria-hidden="true" />
                <span className={styles.label}>Organization settings</span>
              </Command.Item>
            </Command.Group>
          )}
        </Command.List>
      </Command>
    </Dialog>
  );
}

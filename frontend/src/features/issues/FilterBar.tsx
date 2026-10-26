import { useQuery } from '@tanstack/react-query';
import { Search, SlidersHorizontal, X } from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { catalogQuery, membersQuery, projectsQuery } from '../../api/queries';
import { CATEGORIES, PRIORITIES, type StatusCategory } from '../../api/types';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { priorityLabel } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Input, Select } from '../../ui/Field';
import { Segmented } from '../../ui/Tabs';
import { activeFilterCount, type FilterState } from './filters';
import styles from './FilterBar.module.css';

const CATEGORY_LABELS: Record<StatusCategory, string> = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  CANCELED: 'Canceled',
};

interface FilterBarProps {
  org: string;
  state: FilterState;
  onChange: (next: FilterState) => void;
  /** Fixed project (project views): hides the project picker and enables project filters. */
  project?: string;
  /** Page actions (for example "Create issue"), shown at the end of the search row. */
  actions?: ReactNode;
}

export function FilterBar({ org, state, onChange, project, actions }: FilterBarProps) {
  const [text, setText] = useState(state.q ?? '');
  const debounced = useDebouncedValue(text, 300);
  const projectKey = project ?? state.project;
  const projects = useQuery({ ...projectsQuery(org), enabled: !project });
  const catalog = useQuery({ ...catalogQuery(org, projectKey ?? ''), enabled: Boolean(projectKey) });
  const members = useQuery(membersQuery(org, '', 0, 200));

  useEffect(() => {
    if ((state.q ?? '') !== debounced) {
      onChange({ ...state, q: debounced || undefined, page: undefined });
    }
    // Only react to typing; state changes from elsewhere update the input below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced]);

  const [syncedQ, setSyncedQ] = useState(state.q);
  if (syncedQ !== state.q) {
    // The query changed elsewhere (cleared, saved view opened): reflect it in the input.
    setSyncedQ(state.q);
    if ((state.q ?? '') !== text) {
      setText(state.q ?? '');
    }
  }

  const set = (patch: Partial<FilterState>) => {
    onChange({ ...state, ...patch, page: undefined });
  };

  return (
    <div className={styles.bar} role="search" aria-label="Filter issues">
      <div className={styles.primaryRow}>
      <div className={styles.searchBox}>
        <Search size={15} aria-hidden="true" className={styles.searchIcon} />
        <Input
          label="Search"
          hideLabel
          type="search"
          placeholder="Search title, description or key…"
          value={text}
          onChange={(e) => {
            setText(e.target.value);
          }}
          className={styles.search}
        />
      </div>
      <Segmented
        label="Resolution"
        value={state.state ?? 'open'}
        onChange={(value) => { set({ state: value }); }}
        options={[
          { value: 'open', label: 'Open' },
          { value: 'resolved', label: 'Resolved' },
          { value: 'all', label: 'All' },
        ]}
      />
      {actions && <div className={styles.actions}>{actions}</div>}
      </div>
      <div className={styles.filterRow}>
      <span className={styles.filterLabel}>
        <SlidersHorizontal size={14} aria-hidden="true" />
        Filters
        {activeFilterCount(state) > 0 && <span className={styles.filterCount}>{activeFilterCount(state)}</span>}
      </span>
      {!project && (
        <Select label="Project" hideLabel value={state.project ?? ''} onChange={(e) => { set({ project: e.target.value || undefined, label: undefined, milestone: undefined, type: undefined }); }}>
          <option value="">All projects</option>
          {(projects.data ?? []).map((p) => (
            <option key={p.key} value={p.key}>
              {p.name}
            </option>
          ))}
        </Select>
      )}
      <Select label="Status" hideLabel value={state.category ?? ''} onChange={(e) => { set({ category: (e.target.value || undefined) as StatusCategory | undefined }); }}>
        <option value="">Any status</option>
        {CATEGORIES.map((c) => (
          <option key={c} value={c}>
            {CATEGORY_LABELS[c]}
          </option>
        ))}
      </Select>
      <Select label="Priority" hideLabel value={state.priority ?? ''} onChange={(e) => { set({ priority: e.target.value || undefined }); }}>
        <option value="">Any priority</option>
        {PRIORITIES.map((p) => (
          <option key={p} value={p}>
            {priorityLabel(p)}
          </option>
        ))}
      </Select>
      <Select label="Assignee" hideLabel value={state.assignee ?? ''} onChange={(e) => { set({ assignee: e.target.value || undefined }); }}>
        <option value="">Anyone</option>
        <option value="me">Assigned to me</option>
        <option value="none">Unassigned</option>
        {(members.data?.items ?? []).map((m) => (
          <option key={m.userId} value={m.userId}>
            {m.displayName}
          </option>
        ))}
      </Select>
      {projectKey && catalog.data && (
        <>
          {catalog.data.labels.length > 0 && (
            <Select label="Label" hideLabel value={state.label ?? ''} onChange={(e) => { set({ label: e.target.value || undefined }); }}>
              <option value="">Any label</option>
              {catalog.data.labels.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </Select>
          )}
          {catalog.data.milestones.length > 0 && (
            <Select label="Milestone" hideLabel value={state.milestone ?? ''} onChange={(e) => { set({ milestone: e.target.value || undefined }); }}>
              <option value="">Any milestone</option>
              <option value="none">No milestone</option>
              {catalog.data.milestones.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </Select>
          )}
          <Select label="Type" hideLabel value={state.type ?? ''} onChange={(e) => { set({ type: e.target.value || undefined }); }}>
            <option value="">Any type</option>
            {catalog.data.issueTypes.filter((t) => !t.archived).map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </Select>
        </>
      )}
      {activeFilterCount(state) > 0 && (
        <Button
          className={styles.clear}
          variant="ghost"
          size="sm"
          icon={<X size={14} aria-hidden="true" />}
          onClick={() => {
            setText('');
            onChange({ project: state.project, state: state.state, sort: state.sort, dir: state.dir });
          }}
        >
          Clear filters
        </Button>
      )}
      </div>
    </div>
  );
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { Bookmark, Columns3, Download, Plus, Share2, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { searchQuery, viewsQuery } from '../../api/queries';
import type { IssueFilter, SavedView } from '../../api/types';
import { useOrgSlug } from '../../app/params';
import { describeError } from '../../lib/errors';
import { downloadBlob } from '../../lib/download';
import { Alert } from '../../ui/Alert';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { EmptyState } from '../../ui/EmptyState';
import { Checkbox, Input } from '../../ui/Field';
import { Page, PageHeader, Skeleton } from '../../ui/Page';
import { useToast } from '../../ui/Toast';
import { BulkBar } from '../issues/BulkBar';
import { FilterBar } from '../issues/FilterBar';
import { fromIssueFilter, toIssueFilter, type FilterState } from '../issues/filters';
import { ALL_COLUMNS, COLUMN_LABELS, DEFAULT_COLUMNS, IssueTable, type Column } from '../issues/IssueTable';
import styles from './SearchPage.module.css';

const PAGE_SIZE = 50;

interface IssueListProps {
  state: FilterState;
  onChange: (next: FilterState) => void;
  /** Set for a project's list tab. */
  project?: string;
  title: string;
}

/** A filtered, sortable, paginated issue list with saved views; used by Issues and project lists. */
export function IssueListView({ state, onChange, project, title }: IssueListProps) {
  const org = useOrgSlug();
  const toast = useToast();
  const queryClient = useQueryClient();
  const filter = toIssueFilter(state, project);
  const page = (state.page ?? 1) - 1;
  const results = useQuery(searchQuery(org, filter, page, PAGE_SIZE));
  const views = useQuery(viewsQuery(org, project));
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [columns, setColumns] = useState<Column[]>(DEFAULT_COLUMNS);
  const [columnsOpen, setColumnsOpen] = useState(false);
  const [saveOpen, setSaveOpen] = useState(false);
  const activeView = views.data?.find((v) => v.id === state.view);

  const exportCsv = useMutation({
    mutationFn: async () => {
      const result = await api.POST('/api/v1/orgs/{org}/issues/export', {
        params: { path: { org } },
        body: { filter },
        parseAs: 'blob',
      });
      if (!result.response.ok || !result.data) {
        throw new Error('Export failed');
      }
      const disposition = result.response.headers.get('Content-Disposition') ?? '';
      const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? 'issues.csv';
      downloadBlob(result.data, name);
    },
    onError: () => {
      toast.error('The export could not be created.');
    },
  });

  const deleteView = useMutation({
    mutationFn: (view: SavedView) => call(() => api.DELETE('/api/v1/orgs/{org}/views/{id}', { params: { path: { org, id: view.id } } })),
    onSuccess: async () => {
      toast.success('View deleted');
      onChange({ ...state, view: undefined });
      await queryClient.invalidateQueries({ queryKey: ['org', org, 'views'] });
    },
    onError: (error) => {
      toast.error(describeError(error));
    },
  });

  const openView = (view: SavedView) => {
    const display = view.display as { columns?: unknown };
    if (Array.isArray(display.columns)) {
      setColumns(display.columns.filter((c): c is Column => (ALL_COLUMNS as readonly string[]).includes(c as string)));
    }
    onChange({ ...fromIssueFilter(view.query), project: project ? undefined : view.projectKey ?? undefined, view: view.id });
  };

  return (
    <div className="stack">
      <div className={styles.viewsBar}>
        <span className={styles.viewsLabel}>
          <Bookmark size={14} aria-hidden="true" />
          Views
        </span>
        {(views.data ?? []).length > 0 ? (
          <div className={styles.views} role="list" aria-label="Saved views">
            {(views.data ?? []).map((view) => (
              <span role="listitem" key={view.id}>
                <button
                  type="button"
                  className={styles.viewChip}
                  aria-pressed={view.id === state.view}
                  onClick={() => { openView(view); }}
                >
                  {view.shared ? <Share2 size={12} aria-label="Shared" /> : <Bookmark size={12} aria-hidden="true" />}
                  {view.name}
                </button>
              </span>
            ))}
          </div>
        ) : (
          <span className={styles.viewsHint}>Save the current filters and columns to reopen them in one click.</span>
        )}
        <div className={styles.viewActions}>
          {activeView?.editable && (
            <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { deleteView.mutate(activeView); }}>
              Delete view
            </Button>
          )}
          <Button size="sm" icon={<Plus size={14} aria-hidden="true" />} onClick={() => { setSaveOpen(true); }}>
            Save view
          </Button>
        </div>
      </div>

      <FilterBar org={org} state={state} onChange={(next) => { onChange({ ...next, view: undefined }); }} {...(project ? { project } : {})} />
      <BulkBar org={org} selected={selected} onClear={() => { setSelected(new Set()); }} />

      <div className={styles.results}>
        <div className={styles.resultsBar}>
          <p className={styles.count} aria-live="polite">
            {results.data ? (
              <>
                <strong>{results.data.total}</strong> {results.data.total === 1 ? 'issue' : 'issues'}
                {results.isFetching && <span className={styles.updating}> · updating…</span>}
              </>
            ) : results.isError ? (
              'Issues could not be loaded'
            ) : (
              'Loading issues…'
            )}
          </p>
          <div className={styles.resultActions}>
            <Button size="sm" variant="ghost" icon={<Columns3 size={14} aria-hidden="true" />} onClick={() => { setColumnsOpen(true); }}>
              Columns
            </Button>
            <Button size="sm" variant="ghost" icon={<Download size={14} aria-hidden="true" />} loading={exportCsv.isPending} disabled={!results.data} onClick={() => { exportCsv.mutate(); }}>
              Export CSV
            </Button>
          </div>
        </div>
      {results.isPending && <Skeleton lines={8} label="Loading issues" />}
      {results.isError && <Alert tone="danger">{describeError(results.error)}</Alert>}
      {results.data && (
        <>
          <IssueTable
            org={org}
            caption={title}
            issues={results.data.items}
            columns={columns}
            sort={state.sort}
            direction={state.dir}
            onSort={(sort, dir) => { onChange({ ...state, sort, dir, page: undefined }); }}
            selected={selected}
            onSelect={setSelected}
            page={page}
            pageSize={PAGE_SIZE}
            total={results.data.total}
            onPage={(next) => { onChange({ ...state, page: next + 1 }); }}
            empty={
              <EmptyState icon={<Bookmark size={20} />} title="No matching issues">
                Try fewer filters, or include resolved issues.
              </EmptyState>
            }
          />
        </>
      )}
      </div>

      <Dialog open={columnsOpen} size="sm" title="Columns" onClose={() => { setColumnsOpen(false); }}>
        <fieldset className="field-group">
          <legend>Show these columns</legend>
          {ALL_COLUMNS.map((column) => (
            <Checkbox
              key={column}
              label={COLUMN_LABELS[column]}
              checked={columns.includes(column)}
              disabled={column === 'title'}
              onChange={(e) => {
                setColumns(e.target.checked ? ALL_COLUMNS.filter((c) => c === column || columns.includes(c)) : columns.filter((c) => c !== column));
              }}
            />
          ))}
        </fieldset>
      </Dialog>

      <SaveViewDialog
        org={org}
        open={saveOpen}
        onClose={() => { setSaveOpen(false); }}
        filter={filter}
        project={project}
        columns={columns}
        onSaved={(view) => { onChange({ ...state, view: view.id }); }}
      />
    </div>
  );
}

function SaveViewDialog({
  org,
  open,
  onClose,
  filter,
  project,
  columns,
  onSaved,
}: {
  org: string;
  open: boolean;
  onClose: () => void;
  filter: IssueFilter;
  project: string | undefined;
  columns: Column[];
  onSaved: (view: SavedView) => void;
}) {
  const [name, setName] = useState('');
  const [shared, setShared] = useState(false);
  const queryClient = useQueryClient();
  const toast = useToast();
  const save = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/views', {
          params: { path: { org } },
          body: { name: name.trim(), kind: 'LIST', projectKey: project ?? filter.projectKeys?.[0] ?? null, query: filter, display: { columns }, shared },
        }),
      ),
    onSuccess: async (view) => {
      toast.success(`Saved “${view.name}”`);
      setName('');
      onClose();
      onSaved(view);
      await queryClient.invalidateQueries({ queryKey: ['org', org, 'views'] });
    },
  });
  return (
    <Dialog
      open={open}
      size="sm"
      title="Save view"
      description="Saves the current filters, sort order and columns."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={!name.trim()} onClick={() => { save.mutate(); }}>
            Save
          </Button>
        </>
      }
    >
      {save.isError && <Alert tone="danger">{describeError(save.error)}</Alert>}
      <Input label="Name" maxLength={80} value={name} onChange={(e) => { setName(e.target.value); }} data-autofocus />
      <Checkbox label="Share with the organization" hint="Everyone who can see the project can use a shared view." checked={shared} onChange={(e) => { setShared(e.target.checked); }} />
    </Dialog>
  );
}

export function SearchPage({ state }: { state: FilterState }) {
  const navigate = useNavigate();
  return (
    <Page wide>
      <PageHeader title="Issues" description="All issues you can see across your projects." />
      <IssueListView
        title="Issues"
        state={state}
        onChange={(next) => void navigate({ to: '.', search: next as Record<string, unknown>, replace: true })}
      />
    </Page>
  );
}


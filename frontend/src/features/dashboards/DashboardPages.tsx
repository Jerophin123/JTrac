import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { ArrowDown, ArrowRight, ArrowUp, Clock, LayoutDashboard, Lock, Pencil, Plus, Share2, Trash2, UserRound } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { dashboardsQuery, keys, projectsQuery } from '../../api/queries';
import type { Dashboard, Widget, WidgetInput, WidgetKind } from '../../api/types';
import { useOrgSlug } from '../../app/params';
import { describeError } from '../../lib/errors';
import { timeAgo } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Badge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Dialog } from '../../ui/Dialog';
import { EmptyState } from '../../ui/EmptyState';
import { Checkbox, Input, Select } from '../../ui/Field';
import { Page, PageHeader, Panel, Skeleton } from '../../ui/Page';
import { useToast } from '../../ui/Toast';
import { sessionQueryOptions } from '../auth/session';
import { IssueTable } from '../issues/IssueTable';
import { BreakdownChart, MilestoneMeters, TrendChart } from './charts';
import styles from './DashboardPages.module.css';

const KIND_LABELS: Record<WidgetKind, string> = {
  BREAKDOWN: 'Breakdown (bar chart)',
  COUNT: 'Count',
  CREATED_VS_RESOLVED: 'Created vs resolved (trend)',
  MILESTONE_PROGRESS: 'Milestone progress',
  ISSUE_LIST: 'Issue list',
};

const GROUP_LABELS: Record<string, string> = {
  status: 'Status',
  category: 'Status category',
  assignee: 'Assignee',
  priority: 'Priority',
  type: 'Type',
  label: 'Label',
  milestone: 'Milestone',
  project: 'Project',
};

export function DashboardsPage() {
  const org = useOrgSlug();
  const dashboards = useQuery(dashboardsQuery(org));
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const create = useMutation({
    mutationFn: () =>
      call(() =>
        api.POST('/api/v1/orgs/{org}/dashboards', {
          params: { path: { org } },
          body: {
            name: 'My dashboard',
            widgets: [
              { kind: 'COUNT', title: 'Open issues assigned to me', config: { filter: { assignees: ['me'], resolved: false } } },
              { kind: 'BREAKDOWN', title: 'Open issues by status', config: { groupBy: 'status', filter: { resolved: false } } },
              { kind: 'CREATED_VS_RESOLVED', title: 'Created vs resolved, last 30 days', width: 2, config: { days: 30 } },
            ],
          },
        }),
      ),
    onSuccess: async (dashboard) => {
      await queryClient.invalidateQueries({ queryKey: keys.dashboards(org) });
      await navigate({ to: '/$org/dashboards/$id', params: { org, id: dashboard.id } });
    },
  });
  return (
    <Page>
      <PageHeader
        title="Dashboards"
        description="Charts and lists computed for whoever views them: a shared dashboard never shows issues the viewer can’t see."
        actions={
          <Button variant="primary" icon={<Plus size={16} aria-hidden="true" />} loading={create.isPending} onClick={() => { create.mutate(); }}>
            New dashboard
          </Button>
        }
      />
      {create.isError && <Alert tone="danger">{describeError(create.error)}</Alert>}
      {dashboards.isPending && <Skeleton lines={4} label="Loading dashboards" />}
      {dashboards.isError && <Alert tone="danger">{describeError(dashboards.error)}</Alert>}
      {dashboards.data?.length === 0 && (
        <EmptyState icon={<LayoutDashboard size={20} />} title="No dashboards yet">
          Create one to track workload, flow and milestone progress at a glance.
        </EmptyState>
      )}
      {dashboards.data && dashboards.data.length > 0 && (
        <ul className={styles.cardGrid} aria-label="Dashboards">
          {dashboards.data.map((d) => (
            <li key={d.id}>
              <Link to="/$org/dashboards/$id" params={{ org, id: d.id }} className={styles.dashCard}>
                <span className={styles.dashTop}>
                  <span className={styles.dashIcon}>
                    <LayoutDashboard size={18} aria-hidden="true" />
                  </span>
                  <span className={styles.dashStrokes} aria-hidden="true" />
                </span>
                <span className={styles.dashName}>{d.name}</span>
                <span className={styles.dashMeta}>
                  <span>
                    <UserRound size={13} aria-hidden="true" />
                    {d.ownerName}
                  </span>
                  <span>
                    <Clock size={13} aria-hidden="true" />
                    <time dateTime={d.updatedAt}>Updated {timeAgo(d.updatedAt)}</time>
                  </span>
                </span>
                <span className={styles.dashTags}>
                  {d.shared ? (
                    <Badge tone="info">
                      <Share2 size={11} aria-hidden="true" /> Shared
                    </Badge>
                  ) : (
                    <Badge>
                      <Lock size={11} aria-hidden="true" /> Private
                    </Badge>
                  )}
                  {!d.editable && <Badge>View only</Badge>}
                  <ArrowRight size={16} aria-hidden="true" className={styles.dashArrow} />
                </span>
              </Link>
            </li>
          ))}
          <li>
            <button type="button" className={styles.newCard} disabled={create.isPending} onClick={() => { create.mutate(); }}>
              <span className={styles.newIcon}>
                <Plus size={18} aria-hidden="true" />
              </span>
              <span className={styles.dashName}>New dashboard</span>
              <span className={styles.newHint}>Starts with workload, status and trend widgets you can change.</span>
            </button>
          </li>
        </ul>
      )}
    </Page>
  );
}

export function DashboardPage({ id }: { id: string }) {
  const org = useOrgSlug();
  const dashboard = useQuery({
    queryKey: keys.dashboard(org, id),
    queryFn: () => call(() => api.GET('/api/v1/orgs/{org}/dashboards/{id}', { params: { path: { org, id } } })),
  });
  const [editing, setEditing] = useState(false);
  if (dashboard.isPending) {
    return (
      <Page>
        <Skeleton lines={8} label="Loading dashboard" />
      </Page>
    );
  }
  if (dashboard.isError) {
    return (
      <Page>
        <Alert tone="danger">{describeError(dashboard.error)}</Alert>
      </Page>
    );
  }
  const d = dashboard.data;
  return (
    <Page wide>
      <PageHeader
        eyebrow={
          <Link to="/$org/dashboards" params={{ org }}>
            Dashboards
          </Link>
        }
        title={d.name}
        description={`${d.shared ? 'Shared with the organization' : 'Private'} · by ${d.ownerName}`}
        actions={
          d.editable && (
            <Button icon={<Pencil size={14} aria-hidden="true" />} onClick={() => { setEditing(true); }}>
              Edit dashboard
            </Button>
          )
        }
      />
      {d.widgets.length === 0 ? (
        <EmptyState icon={<LayoutDashboard size={20} />} title="This dashboard is empty">
          {d.editable ? 'Use “Edit dashboard” to add widgets.' : 'The owner hasn’t added widgets yet.'}
        </EmptyState>
      ) : (
        <div className={styles.grid}>
          {d.widgets.map((w) => (
            <div key={w.id} className={w.width === 2 ? styles.wide : undefined}>
              <WidgetCard org={org} dashboardId={d.id} widget={w} />
            </div>
          ))}
        </div>
      )}
      {editing && <DashboardEditor org={org} dashboard={d} onClose={() => { setEditing(false); }} />}
    </Page>
  );
}

function WidgetCard({ org, dashboardId, widget }: { org: string; dashboardId: string; widget: Widget }) {
  const data = useQuery({
    queryKey: keys.widgetData(org, dashboardId, widget.id),
    queryFn: () =>
      call(() =>
        api.GET('/api/v1/orgs/{org}/dashboards/{id}/widgets/{widgetId}/data', { params: { path: { org, id: dashboardId, widgetId: widget.id } } }),
      ),
  });
  return (
    <Panel title={widget.title} padded={widget.kind !== 'ISSUE_LIST'}>
      {data.isPending && <Skeleton lines={4} label={`Loading ${widget.title}`} />}
      {data.isError && <Alert tone="danger">{describeError(data.error)}</Alert>}
      {data.data?.kind === 'COUNT' && (
        <p className={styles.count}>
          <span>{data.data.count ?? 0}</span> <span className="muted">issues</span>
        </p>
      )}
      {data.data?.kind === 'BREAKDOWN' && <BreakdownChart title={widget.title} buckets={data.data.buckets ?? []} />}
      {data.data?.kind === 'CREATED_VS_RESOLVED' && <TrendChart title={widget.title} days={data.data.days ?? []} />}
      {data.data?.kind === 'MILESTONE_PROGRESS' && <MilestoneMeters milestones={data.data.milestones ?? []} />}
      {data.data?.kind === 'ISSUE_LIST' && (
        <IssueTable
          org={org}
          framed={false}
          caption={widget.title}
          issues={data.data.issues ?? []}
          columns={['key', 'title', 'status', 'assignee', 'dueDate']}
          empty={<p className={`muted ${styles.pad ?? ''}`}>No matching issues.</p>}
        />
      )}
    </Panel>
  );
}

function toInput(w: Widget): WidgetInput {
  return { kind: w.kind, title: w.title, config: w.config, width: w.width };
}

function DashboardEditor({ org, dashboard, onClose }: { org: string; dashboard: Dashboard; onClose: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const projects = useQuery(projectsQuery(org));
  const session = useQuery(sessionQueryOptions);
  const [name, setName] = useState(dashboard.name);
  const [shared, setShared] = useState(dashboard.shared);
  const [widgets, setWidgets] = useState<WidgetInput[]>(dashboard.widgets.map(toInput));
  const [confirmDelete, setConfirmDelete] = useState(false);
  const save = useMutation({
    mutationFn: () =>
      call(() =>
        api.PUT('/api/v1/orgs/{org}/dashboards/{id}', {
          params: { path: { org, id: dashboard.id } },
          body: { version: dashboard.version, dashboard: { name: name.trim(), shared, widgets } },
        }),
      ),
    onSuccess: async (saved) => {
      queryClient.setQueryData(keys.dashboard(org, dashboard.id), saved);
      await queryClient.invalidateQueries({ queryKey: keys.dashboards(org) });
      await queryClient.invalidateQueries({ queryKey: ['org', org, 'issues', 'widget', dashboard.id] });
      toast.success('Dashboard saved');
      onClose();
    },
  });
  const remove = useMutation({
    mutationFn: () => call(() => api.DELETE('/api/v1/orgs/{org}/dashboards/{id}', { params: { path: { org, id: dashboard.id } } })),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.dashboards(org) });
      await navigate({ to: '/$org/dashboards', params: { org } });
    },
  });
  const update = (index: number, patch: Partial<WidgetInput>) => {
    setWidgets(widgets.map((w, i) => (i === index ? { ...w, ...patch } : w)));
  };
  const moveWidget = (index: number, delta: number) => {
    const next = [...widgets];
    const [item] = next.splice(index, 1);
    if (item) {
      next.splice(index + delta, 0, item);
      setWidgets(next);
    }
  };
  const firstProject = projects.data?.find((p) => !p.archived)?.key;

  return (
    <Dialog
      open
      size="lg"
      title="Edit dashboard"
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setConfirmDelete(true); }}>
            Delete dashboard
          </Button>
          <span className={styles.grow} />
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" loading={save.isPending} disabled={!name.trim()} onClick={() => { save.mutate(); }}>
            Save
          </Button>
        </>
      }
    >
      {save.isError && <Alert tone="danger">{describeError(save.error)}</Alert>}
      {confirmDelete && (
        <Alert tone="warning" title="Delete this dashboard?">
          <div className="row">
            <Button size="sm" variant="primary" loading={remove.isPending} onClick={() => { remove.mutate(); }}>
              Delete
            </Button>
            <Button size="sm" onClick={() => { setConfirmDelete(false); }}>
              Keep it
            </Button>
          </div>
        </Alert>
      )}
      <Input label="Name" maxLength={80} value={name} onChange={(e) => { setName(e.target.value); }} />
      {dashboard.ownerId === session.data?.user.id && (
        <Checkbox label="Share with the organization" hint="Members see it with their own permissions." checked={shared} onChange={(e) => { setShared(e.target.checked); }} />
      )}
      <ol className={styles.editorList}>
        {widgets.map((w, index) => (
          <li key={index} className={styles.editorItem}>
            <div className="form-grid">
              <Input label="Title" maxLength={80} value={w.title} onChange={(e) => { update(index, { title: e.target.value }); }} />
              <Select label="Kind" value={w.kind} onChange={(e) => { update(index, { kind: e.target.value as WidgetKind, config: {} }); }}>
                {Object.entries(KIND_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </Select>
              <Select label="Width" value={String(w.width ?? 1)} onChange={(e) => { update(index, { width: Number(e.target.value) as 1 | 2 }); }}>
                <option value="1">Half</option>
                <option value="2">Full</option>
              </Select>
              {w.kind !== 'MILESTONE_PROGRESS' && (
                <Select
                  label="Project"
                  value={w.config?.filter?.projectKeys?.[0] ?? ''}
                  onChange={(e) => {
                    update(index, { config: { ...w.config, filter: { ...w.config?.filter, projectKeys: e.target.value ? [e.target.value] : undefined } } });
                  }}
                >
                  <option value="">All projects</option>
                  {(projects.data ?? []).map((p) => (
                    <option key={p.key} value={p.key}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              )}
              {w.kind === 'BREAKDOWN' && (
                <Select label="Group by" value={w.config?.groupBy ?? 'status'} onChange={(e) => { update(index, { config: { ...w.config, groupBy: e.target.value as 'status' } }); }}>
                  {Object.entries(GROUP_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </Select>
              )}
              {w.kind === 'CREATED_VS_RESOLVED' && (
                <Select label="Period" value={String(w.config?.days ?? 30)} onChange={(e) => { update(index, { config: { ...w.config, days: Number(e.target.value) } }); }}>
                  {[7, 14, 30, 90, 180, 365].map((d) => (
                    <option key={d} value={d}>
                      Last {d} days
                    </option>
                  ))}
                </Select>
              )}
              {w.kind === 'MILESTONE_PROGRESS' && (
                <Select label="Project" value={w.config?.projectKey ?? firstProject ?? ''} onChange={(e) => { update(index, { config: { ...w.config, projectKey: e.target.value } }); }}>
                  {(projects.data ?? []).map((p) => (
                    <option key={p.key} value={p.key}>
                      {p.name}
                    </option>
                  ))}
                </Select>
              )}
              {w.kind === 'ISSUE_LIST' && (
                <Select label="Rows" value={String(w.config?.limit ?? 10)} onChange={(e) => { update(index, { config: { ...w.config, limit: Number(e.target.value) } }); }}>
                  {[5, 10, 15, 25].map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </Select>
              )}
              {w.kind !== 'MILESTONE_PROGRESS' && (
                <Checkbox
                  label="Open issues only"
                  checked={w.config?.filter?.resolved === false}
                  onChange={(e) => { update(index, { config: { ...w.config, filter: { ...w.config?.filter, resolved: e.target.checked ? false : undefined } } }); }}
                />
              )}
            </div>
            <div className="row">
              <Button size="sm" variant="ghost" icon={<ArrowUp size={14} aria-hidden="true" />} disabled={index === 0} onClick={() => { moveWidget(index, -1); }} aria-label={`Move “${w.title}” up`} />
              <Button size="sm" variant="ghost" icon={<ArrowDown size={14} aria-hidden="true" />} disabled={index === widgets.length - 1} onClick={() => { moveWidget(index, 1); }} aria-label={`Move “${w.title}” down`} />
              <Button size="sm" variant="ghost" icon={<Trash2 size={14} aria-hidden="true" />} onClick={() => { setWidgets(widgets.filter((_, i) => i !== index)); }}>
                Remove
              </Button>
            </div>
          </li>
        ))}
      </ol>
      <div>
        <Button
          icon={<Plus size={14} aria-hidden="true" />}
          disabled={widgets.length >= 24}
          onClick={() => { setWidgets([...widgets, { kind: 'BREAKDOWN', title: 'New widget', config: { groupBy: 'status' }, width: 1 }]); }}
        >
          Add widget
        </Button>
      </div>
    </Dialog>
  );
}

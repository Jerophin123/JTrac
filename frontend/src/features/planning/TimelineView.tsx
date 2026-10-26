import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { CalendarRange } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { keys } from '../../api/queries';
import type { IssueSummary } from '../../api/types';
import { useOrgSlug, useProject } from '../../app/params';
import { describeError } from '../../lib/errors';
import { formatDate, formatShortDate, todayIso } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { StatusBadge } from '../../ui/Badge';
import { EmptyState } from '../../ui/EmptyState';
import { Panel, Skeleton } from '../../ui/Page';
import { Segmented } from '../../ui/Tabs';
import { MilestoneMeters } from '../dashboards/charts';
import { FilterBar } from '../issues/FilterBar';
import { toIssueFilter, type FilterState } from '../issues/filters';
import styles from './TimelineView.module.css';

const DAY = 86_400_000;
const ROW = 36;
const HEADER = 28;

function toDays(iso: string): number {
  return Math.round(new Date(`${iso}T00:00:00Z`).getTime() / DAY);
}

function fromDays(days: number): string {
  return new Date(days * DAY).toISOString().slice(0, 10);
}

interface Bar {
  issue: IssueSummary;
  start: number;
  end: number;
  /** Only a due date: drawn as a point, not a span. */
  point: boolean;
}

export function layoutBars(issues: IssueSummary[]): Bar[] {
  return issues.map((issue) => {
    const start = issue.startDate ? toDays(issue.startDate) : toDays(issue.dueDate ?? todayIso());
    const end = issue.dueDate ? toDays(issue.dueDate) : start;
    return { issue, start, end: Math.max(start, end), point: !issue.startDate };
  });
}

export function TimelineView({ state, onChange }: { state: FilterState; onChange: (next: FilterState) => void }) {
  const org = useOrgSlug();
  const project = useProject();
  const [mode, setMode] = useState<'chart' | 'table'>('chart');
  const [scale, setScale] = useState<'week' | 'month'>('week');
  const filter = { ...toIssueFilter({ ...state, state: state.state ?? 'all' }, project.key), sort: undefined, direction: undefined };
  const timeline = useQuery({
    queryKey: keys.timeline(org, project.key, filter),
    queryFn: () => call(() => api.POST('/api/v1/orgs/{org}/projects/{key}/timeline', { params: { path: { org, key: project.key } }, body: { filter } })),
  });

  if (timeline.isPending) {
    return <Skeleton lines={8} label="Loading timeline" />;
  }
  if (timeline.isError) {
    return <Alert tone="danger">{describeError(timeline.error)}</Alert>;
  }
  const data = timeline.data;
  const bars = layoutBars(data.scheduled);
  const today = toDays(todayIso());
  const milestoneDays = data.milestones.filter((m) => m.dueDate).map((m) => ({ name: m.name, day: toDays(m.dueDate ?? '') }));
  const min = Math.min(today, ...bars.map((b) => b.start), ...milestoneDays.map((m) => m.day)) - 3;
  const max = Math.max(today, ...bars.map((b) => b.end), ...milestoneDays.map((m) => m.day)) + 4;
  const pxPerDay = scale === 'week' ? 28 : 8;
  const width = (max - min + 1) * pxPerDay;
  const x = (day: number) => (day - min) * pxPerDay;
  const rowOf = new Map(bars.map((b, i) => [b.issue.id, i]));
  const ticks: number[] = [];
  for (let d = min; d <= max; d++) {
    const date = new Date(d * DAY);
    if (scale === 'week' ? date.getUTCDay() === 1 : date.getUTCDate() === 1) {
      ticks.push(d);
    }
  }
  const height = HEADER + bars.length * ROW;

  return (
    <div className="stack">
      <FilterBar org={org} state={{ ...state, state: state.state ?? 'all' }} onChange={onChange} project={project.key} />
      <div className="row">
        <Segmented label="View" value={mode} onChange={setMode} options={[{ value: 'chart', label: 'Timeline' }, { value: 'table', label: 'Table' }]} />
        {mode === 'chart' && (
          <Segmented label="Scale" value={scale} onChange={setScale} options={[{ value: 'week', label: 'Weeks' }, { value: 'month', label: 'Months' }]} />
        )}
        <span className="muted">
          {data.scheduled.length} scheduled · {data.unscheduled} without dates
          {data.truncated ? ' · only the first 2,000 issues were considered' : ''}
        </span>
      </div>

      {bars.length === 0 ? (
        <EmptyState icon={<CalendarRange size={20} />} title="Nothing scheduled yet">
          Give issues a start or due date to place them on the timeline.
        </EmptyState>
      ) : mode === 'table' ? (
        <table className={styles.table}>
          <caption className="visually-hidden">Scheduled issues of {project.name}</caption>
          <thead>
            <tr>
              <th scope="col">Issue</th>
              <th scope="col">Status</th>
              <th scope="col">Start</th>
              <th scope="col">Due</th>
              <th scope="col">Blocked by</th>
            </tr>
          </thead>
          <tbody>
            {bars.map(({ issue }) => (
              <tr key={issue.id}>
                <th scope="row">
                  <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.key }}>
                    {issue.key} {issue.title}
                  </Link>
                </th>
                <td>
                  <StatusBadge name={issue.status.name} category={issue.status.category} />
                </td>
                <td>{formatDate(issue.startDate)}</td>
                <td>{formatDate(issue.dueDate)}</td>
                <td>{data.dependencies.filter((d) => d.blockedId === issue.id).map((d) => d.blockerKey).join(', ') || 'None'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className={styles.frame}>
          <ul className={styles.labels} style={{ paddingBlockStart: HEADER }}>
            {bars.map(({ issue }) => (
              <li key={issue.id} style={{ blockSize: ROW }}>
                <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.key }} className={styles.label}>
                  <span className="issue-key">{issue.key}</span> {issue.title}
                </Link>
              </li>
            ))}
          </ul>
          <div className={styles.scroller}>
            <svg width={width} height={height} className={styles.svg} role="img" aria-label={`Timeline of ${String(bars.length)} scheduled issues; use the table view for details.`}>
              {ticks.map((d) => (
                <g key={d}>
                  <line x1={x(d)} x2={x(d)} y1={HEADER - 6} y2={height} className={styles.grid} />
                  <text x={x(d) + 4} y={16} className={styles.tick}>
                    {formatShortDate(fromDays(d))}
                  </text>
                </g>
              ))}
              {milestoneDays.map((m) => (
                <g key={m.name}>
                  <line x1={x(m.day) + pxPerDay / 2} x2={x(m.day) + pxPerDay / 2} y1={HEADER} y2={height} className={styles.milestone} />
                  <text x={x(m.day) + pxPerDay / 2 + 3} y={HEADER + 10} className={styles.milestoneLabel}>
                    ◆ {m.name}
                  </text>
                </g>
              ))}
              <line x1={x(today) + pxPerDay / 2} x2={x(today) + pxPerDay / 2} y1={HEADER - 6} y2={height} className={styles.today} />
              {data.dependencies.map((dep) => {
                const from = rowOf.get(dep.blockerId);
                const to = rowOf.get(dep.blockedId);
                const a = bars[from ?? -1];
                const b = bars[to ?? -1];
                if (from === undefined || to === undefined || !a || !b) {
                  return null;
                }
                const x1 = x(a.end) + pxPerDay;
                const y1 = HEADER + from * ROW + ROW / 2;
                const x2 = x(b.start);
                const y2 = HEADER + to * ROW + ROW / 2;
                const mid = Math.max(x1 + 6, Math.min(x2 - 6, (x1 + x2) / 2));
                return (
                  <path
                    key={`${dep.blockerId}-${dep.blockedId}`}
                    d={`M${String(x1)},${String(y1)} H${String(mid)} V${String(y2)} H${String(x2 - 2)}`}
                    className={x2 < x1 ? styles.conflict : styles.dependency}
                    markerEnd="url(#arrow)"
                  >
                    <title>
                      {dep.blockerKey} blocks {dep.blockedKey}
                      {x2 < x1 ? ' (scheduled to start before its blocker ends)' : ''}
                    </title>
                  </path>
                );
              })}
              <defs>
                <marker id="arrow" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto">
                  <path d="M0,0 L6,3 L0,6 z" className={styles.arrow} />
                </marker>
              </defs>
              {bars.map((bar, i) => {
                const y = HEADER + i * ROW + 10;
                const resolved = Boolean(bar.issue.resolvedAt);
                return bar.point ? (
                  <rect
                    key={bar.issue.id}
                    x={x(bar.start) + pxPerDay / 2 - 7}
                    y={y + 1}
                    width={14}
                    height={14}
                    rx={3}
                    transform={`rotate(45 ${String(x(bar.start) + pxPerDay / 2)} ${String(y + 8)})`}
                    className={resolved ? styles.barDone : styles.bar}
                  >
                    <title>{`${bar.issue.key}: due ${formatDate(bar.issue.dueDate)}`}</title>
                  </rect>
                ) : (
                  <rect key={bar.issue.id} x={x(bar.start)} y={y} width={(bar.end - bar.start + 1) * pxPerDay} height={16} rx={4} className={resolved ? styles.barDone : styles.bar}>
                    <title>{`${bar.issue.key}: ${formatDate(bar.issue.startDate)} – ${formatDate(bar.issue.dueDate)}`}</title>
                  </rect>
                );
              })}
            </svg>
          </div>
        </div>
      )}

      {data.milestones.length > 0 && (
        <Panel title="Milestones">
          <MilestoneMeters milestones={data.milestones} />
        </Panel>
      )}
    </div>
  );
}

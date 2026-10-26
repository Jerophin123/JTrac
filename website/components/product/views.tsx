'use client';

import { CalendarClock } from 'lucide-react';
import type { CSSProperties } from 'react';
import { assigneeRequiredIn, type DemoIssue, statusById, statuses, transitions } from './data';
import { Avatar, Button, LabelChip, PriorityIcon, StatusBadge } from './primitives';
import base from './styles/Base.module.css';
import board from './styles/BoardView.module.css';
import issuePage from './styles/IssuePage.module.css';
import sections from './styles/IssueSections.module.css';
import page from './styles/Page.module.css';
import table from './styles/IssueTable.module.css';
import timeline from './styles/TimelineView.module.css';

/*
 * Replicas of JTRAC's Board, List, Timeline and Issue views (jtac/frontend/src/features),
 * rendered from demo data. Issue titles open the issue view; nothing talks to a server.
 */

type Open = (key: string) => void;

function IssueTitle({ issue, onOpen, className }: { issue: DemoIssue; onOpen?: Open | undefined; className: string | undefined }) {
  if (!onOpen) return <span className={className}>{issue.title}</span>;
  return (
    <button type="button" className={`${className ?? ''} cursor-pointer text-start`} onClick={() => onOpen(issue.key)}>
      {issue.title}
    </button>
  );
}

interface BoardViewProps {
  issues: DemoIssue[];
  onOpen?: Open;
  style?: CSSProperties;
  /** Limit the columns shown (the app shows every status). */
  columns?: string[];
}

export function BoardView({ issues, onOpen, style, columns }: BoardViewProps) {
  return (
    <div className={board.board} role="list" aria-label="Board" style={style}>
      {statuses.filter((s) => !columns || columns.includes(s.id)).map((status) => {
        const columnIssues = issues.filter((i) => i.statusId === status.id);
        return (
          <section key={status.id} role="listitem" className={board.column} aria-label={status.name}>
            <header className={board.columnHeader}>
              <span className={board.marker} data-category={status.category} aria-hidden="true" />
              <p data-heading className={board.columnTitle}>{status.name}</p>
              <span className={board.count}>{columnIssues.length}</span>
            </header>
            <ul className={board.cards}>
              {columnIssues.length === 0 ? <li className={board.emptyColumn}>No issues</li> : null}
              {columnIssues.map((issue) => (
                <li key={issue.key} className={board.card}>
                  <div className={board.cardBody}>
                    <div className={board.cardTop}>
                      <span className={base.issueKey}>{issue.key}</span>
                      <PriorityIcon priority={issue.priority} />
                    </div>
                    <IssueTitle issue={issue} onOpen={onOpen} className={board.cardTitle} />
                    {issue.labels.length > 0 ? (
                      <div className={board.cardLabels}>
                        {issue.labels.slice(0, 3).map((l) => (
                          <LabelChip key={l.name} {...l} />
                        ))}
                      </div>
                    ) : null}
                    <div className={board.cardMeta}>
                      {issue.due ? (
                        <span className={issue.overdue ? base.overdue : undefined}>
                          <CalendarClock size={12} aria-hidden="true" /> {issue.due}
                        </span>
                      ) : null}
                      <span className={board.assignee}>
                        {issue.assignee ? (
                          <span title={issue.assignee}>
                            <Avatar name={issue.assignee} />
                          </span>
                        ) : null}
                      </span>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

export function IssueTable({ issues, onOpen }: { issues: DemoIssue[]; onOpen?: Open }) {
  return (
    <div className={table.wrap}>
      <div className={table.scroller}>
        <table className={table.table}>
          <caption className={base.visuallyHidden}>Demo issues</caption>
          <thead>
            <tr>
              <th scope="col">Key</th>
              <th scope="col" className={table.titleCol}>
                Title
              </th>
              <th scope="col">Status</th>
              <th scope="col">Priority</th>
              <th scope="col">Assignee</th>
              <th scope="col">Due</th>
              <th scope="col">Updated</th>
            </tr>
          </thead>
          <tbody>
            {issues.map((issue) => {
              const status = statusById[issue.statusId]!;
              return (
                <tr key={issue.key}>
                  <td>
                    <span className={base.issueKey}>{issue.key}</span>
                  </td>
                  <td className={table.titleCol}>
                    <IssueTitle issue={issue} onOpen={onOpen} className={table.titleLink} />
                  </td>
                  <td>
                    <StatusBadge name={status.name} category={status.category} />
                  </td>
                  <td>
                    <PriorityIcon priority={issue.priority} withLabel />
                  </td>
                  <td>{issue.assignee ?? <span className={base.muted}>Unassigned</span>}</td>
                  <td>
                    <span className={issue.overdue ? base.overdue : undefined}>{issue.due ?? ''}</span>
                  </td>
                  <td>{issue.updated}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

const DAY_PX = 30;
const ROW = 36;
const HEADER = 28;
const RANGE: [number, number] = [-7, 15];
const TICKS = [
  { day: -3, label: 'Nov 2' },
  { day: 4, label: 'Nov 9' },
  { day: 11, label: 'Nov 16' },
];
const MILESTONE = { day: 12, name: 'Spring release' };

export function TimelineView({ issues, onOpen }: { issues: DemoIssue[]; onOpen?: Open }) {
  const bars = issues.filter((i): i is DemoIssue & { span: [number, number] } => i.span !== null);
  const x = (day: number) => (day - RANGE[0]) * DAY_PX;
  const width = (RANGE[1] - RANGE[0] + 1) * DAY_PX;
  const height = HEADER + bars.length * ROW + 8;
  const rowOf = (key: string) => bars.findIndex((b) => b.key === key);
  const blocker = rowOf('JTRAC-104');
  const blocked = rowOf('JTRAC-101');
  return (
    <div className={timeline.frame}>
      <ul className={timeline.labels} style={{ paddingBlockStart: HEADER }}>
        {bars.map((issue) => (
          <li key={issue.key} style={{ blockSize: ROW }}>
            <IssueTitle
              issue={{ ...issue, title: `${issue.key} ${issue.title}` }}
              onOpen={onOpen ? () => onOpen(issue.key) : undefined}
              className={timeline.label}
            />
          </li>
        ))}
      </ul>
      <div className={timeline.scroller}>
        <svg width={width} height={height} className={timeline.svg} role="img" aria-label={`Timeline of ${String(bars.length)} scheduled demo issues`}>
          {TICKS.map((t) => (
            <g key={t.day}>
              <line x1={x(t.day)} x2={x(t.day)} y1={HEADER - 6} y2={height} className={timeline.grid} />
              <text x={x(t.day) + 4} y={16} className={timeline.tick}>
                {t.label}
              </text>
            </g>
          ))}
          <line x1={x(MILESTONE.day) + DAY_PX / 2} x2={x(MILESTONE.day) + DAY_PX / 2} y1={HEADER} y2={height} className={timeline.milestone} />
          <text x={x(MILESTONE.day) + DAY_PX / 2 + 3} y={HEADER + 10} className={timeline.milestoneLabel}>
            ◆ {MILESTONE.name}
          </text>
          <line x1={x(0) + DAY_PX / 2} x2={x(0) + DAY_PX / 2} y1={HEADER - 6} y2={height} className={timeline.today} />
          {blocker >= 0 && blocked >= 0 ? (
            <path
              d={(() => {
                const a = bars[blocker]!;
                const b = bars[blocked]!;
                const x1 = x(a.span[1]) + DAY_PX;
                const y1 = HEADER + blocker * ROW + ROW / 2;
                const x2 = x(b.span[0]);
                const y2 = HEADER + blocked * ROW + ROW / 2;
                const mid = Math.max(x1 + 6, Math.min(x2 - 6, (x1 + x2) / 2));
                return `M${String(x1)},${String(y1)} H${String(mid)} V${String(y2)} H${String(x2 - 2)}`;
              })()}
              className={timeline.dependency}
              markerEnd="url(#demo-arrow)"
            >
              <title>JTRAC-104 blocks JTRAC-101</title>
            </path>
          ) : null}
          <defs>
            <marker id="demo-arrow" viewBox="0 0 6 6" refX="5" refY="3" markerWidth="6" markerHeight="6" orient="auto">
              <path d="M0,0 L6,3 L0,6 z" className={timeline.arrow} />
            </marker>
          </defs>
          {bars.map((issue, i) => (
            <rect
              key={issue.key}
              x={x(issue.span[0])}
              y={HEADER + i * ROW + 10}
              width={(issue.span[1] - issue.span[0] + 1) * DAY_PX}
              height={16}
              rx={4}
              className={issue.statusId === 'done' ? timeline.barDone : timeline.bar}
            >
              <title>{`${issue.key}: due ${issue.due ?? ''}`}</title>
            </rect>
          ))}
        </svg>
      </div>
    </div>
  );
}

interface IssueViewProps {
  issue: DemoIssue;
  projectName: string;
  onBack?: () => void;
  onTransition?: (to: string, name: string) => void;
  compact?: boolean;
}

/** The issue page: breadcrumb, title, description, history and the details side panel. */
export function IssueView({ issue, projectName, onBack, onTransition, compact = false }: IssueViewProps) {
  const status = statusById[issue.statusId]!;
  const available = transitions.filter((t) => t.from.includes(issue.statusId));
  const side = (
    <aside className={issuePage.side} aria-label="Issue details">
      <section className={issuePage.statusBox} aria-label="Status">
        <StatusBadge name={status.name} category={status.category} />
        {available.length > 0 ? (
          <div className={issuePage.transitions}>
            {available.map((t) => (
              <Button
                key={t.name}
                variant="secondary"
                disabled={!onTransition}
                onClick={() => onTransition?.(t.to, t.name)}
                title={t.adminOnly ? 'Only Administrators can use this transition' : undefined}
              >
                {t.name}
              </Button>
            ))}
          </div>
        ) : null}
      </section>
      <dl className={issuePage.fields}>
        <div className={issuePage.field}>
          <dt>
            Assignee
            {assigneeRequiredIn.has(issue.statusId) ? <span className={issuePage.required}> required</span> : null}
          </dt>
          <dd>{issue.assignee ?? <span className={base.muted}>Unassigned</span>}</dd>
        </div>
        <div className={issuePage.field}>
          <dt>Priority</dt>
          <dd>
            <PriorityIcon priority={issue.priority} withLabel />
          </dd>
        </div>
        <div className={issuePage.field}>
          <dt>Labels</dt>
          <dd>
            <div className={issuePage.labels}>
              {issue.labels.length ? issue.labels.map((l) => <LabelChip key={l.name} {...l} />) : <span className={base.muted}>None</span>}
            </div>
          </dd>
        </div>
        <div className={issuePage.field}>
          <dt>Milestone</dt>
          <dd>{issue.milestone ?? <span className={base.muted}>None</span>}</dd>
        </div>
        <div className={issuePage.field}>
          <dt>Due date</dt>
          <dd className={issue.overdue ? base.overdue : undefined}>{issue.due ?? <span className={base.muted}>None</span>}</dd>
        </div>
      </dl>
    </aside>
  );

  if (compact) return side;

  return (
    <div className={page.wide}>
      <div className={issuePage.header}>
        <nav aria-label="Breadcrumb" className={issuePage.breadcrumb}>
          {onBack ? (
            <button type="button" onClick={onBack} className="cursor-pointer underline underline-offset-[0.18em]">
              {projectName}
            </button>
          ) : (
            <span>{projectName}</span>
          )}
          <span aria-hidden="true">/</span>
          <span className={base.issueKey} aria-current="page">
            {issue.key}
          </span>
        </nav>
      </div>
      <div className={issuePage.layout}>
        <article className={issuePage.main}>
          <p data-heading className={issuePage.title}>{issue.title}</p>
          <section className={issuePage.section} aria-label="Description">
            <div className={issuePage.sectionHeader}>
              <p data-heading className={issuePage.sectionTitle}>Description</p>
            </div>
            <div className="grid gap-3">
              {issue.description.map((p) => (
                <p key={p}>{p}</p>
              ))}
            </div>
          </section>
          <section className={sections.section} aria-label="History">
            <div className={sections.header}>
              <p data-heading className={sections.title}>History</p>
            </div>
            <ol className={sections.activity}>
              {issue.history.map((h, i) => (
                <li key={i}>
                  <strong>{h.who}</strong>
                  <span>{h.what}</span>
                  <span>· {h.when}</span>
                </li>
              ))}
            </ol>
          </section>
        </article>
        {side}
      </div>
    </div>
  );
}

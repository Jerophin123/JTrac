import { Link } from '@tanstack/react-router';
import { ArrowDown, ArrowUp, ChevronLeft, ChevronRight } from 'lucide-react';
import type { ReactNode } from 'react';
import type { IssueSummary } from '../../api/types';
import { formatDate, isOverdue, timeAgo } from '../../lib/format';
import { LabelChip, PriorityIcon, StatusBadge } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import styles from './IssueTable.module.css';

export const ALL_COLUMNS = [
  'key',
  'title',
  'type',
  'status',
  'priority',
  'assignee',
  'labels',
  'milestone',
  'startDate',
  'dueDate',
  'estimate',
  'updated',
  'created',
] as const;
export type Column = (typeof ALL_COLUMNS)[number];
export const DEFAULT_COLUMNS: Column[] = ['key', 'title', 'status', 'priority', 'assignee', 'dueDate', 'updated'];

export const COLUMN_LABELS: Record<Column, string> = {
  key: 'Key',
  title: 'Title',
  type: 'Type',
  status: 'Status',
  priority: 'Priority',
  assignee: 'Assignee',
  labels: 'Labels',
  milestone: 'Milestone',
  startDate: 'Start',
  dueDate: 'Due',
  estimate: 'Estimate',
  updated: 'Updated',
  created: 'Created',
};

/** Columns whose header sorts the list, mapped to the API's sort field. */
const SORTABLE: Partial<Record<Column, string>> = {
  key: 'key',
  title: 'title',
  status: 'status',
  priority: 'priority',
  assignee: 'assignee',
  startDate: 'startDate',
  dueDate: 'dueDate',
  estimate: 'estimate',
  updated: 'updated',
  created: 'created',
};

interface IssueTableProps {
  org: string;
  issues: IssueSummary[];
  columns?: Column[];
  caption: string;
  sort?: string | undefined;
  direction?: 'asc' | 'desc' | undefined;
  onSort?: (sort: string, direction: 'asc' | 'desc') => void;
  selected?: Set<string>;
  onSelect?: (keys: Set<string>) => void;
  page?: number;
  pageSize?: number;
  total?: number;
  onPage?: (page: number) => void;
  empty?: ReactNode;
  /** Draw the table's own border (off when embedded in a panel). */
  framed?: boolean;
}

const hidden = <span className={styles.hidden} title="Hidden by your project’s field rules">Hidden</span>;

function cell(org: string, issue: IssueSummary, column: Column): ReactNode {
  const isHidden = (field: string) => issue.hiddenFields.includes(field);
  switch (column) {
    case 'key':
      return <span className="issue-key">{issue.key}</span>;
    case 'title':
      return (
        <Link to="/$org/issue/$issueKey" params={{ org, issueKey: issue.key }} className={styles.titleLink}>
          {issue.title}
        </Link>
      );
    case 'type':
      return issue.type.name;
    case 'status':
      return <StatusBadge name={issue.status.name} category={issue.status.category} />;
    case 'priority':
      return isHidden('priority') ? hidden : <PriorityIcon priority={issue.priority} withLabel />;
    case 'assignee':
      return isHidden('assignee') ? hidden : (issue.assignee?.displayName ?? <span className="muted">Unassigned</span>);
    case 'labels':
      return isHidden('labels') ? hidden : (
        <span className={styles.labels}>
          {(issue.labels ?? []).map((l) => (
            <LabelChip key={l.id} name={l.name} color={l.color} />
          ))}
        </span>
      );
    case 'milestone':
      return isHidden('milestone') ? hidden : (issue.milestone?.name ?? '');
    case 'startDate':
      return isHidden('startDate') ? hidden : formatDate(issue.startDate);
    case 'dueDate':
      return isHidden('dueDate') ? hidden : (
        <span className={isOverdue(issue.dueDate, Boolean(issue.resolvedAt)) ? 'overdue' : undefined}>{formatDate(issue.dueDate)}</span>
      );
    case 'estimate':
      return isHidden('estimate') ? hidden : (issue.estimate ?? '');
    case 'updated':
      return <time dateTime={issue.updatedAt}>{timeAgo(issue.updatedAt)}</time>;
    case 'created':
      return <time dateTime={issue.createdAt}>{formatDate(issue.createdAt)}</time>;
  }
}

export function IssueTable({
  org,
  issues,
  columns = DEFAULT_COLUMNS,
  caption,
  sort,
  direction = 'asc',
  onSort,
  selected,
  onSelect,
  page = 0,
  pageSize = 50,
  total,
  onPage,
  empty,
  framed = true,
}: IssueTableProps) {
  if (issues.length === 0 && empty) {
    return <>{empty}</>;
  }
  const allSelected = selected !== undefined && issues.length > 0 && issues.every((i) => selected.has(i.key));
  const pages = total === undefined ? 1 : Math.max(1, Math.ceil(total / pageSize));
  return (
    <div className={framed ? styles.wrap : styles.bare}>
      <div className={styles.scroller}>
        <table className={styles.table}>
          <caption className="visually-hidden">{caption}</caption>
          <thead>
            <tr>
              {onSelect && selected && (
                <th scope="col" className={styles.check}>
                  <input
                    type="checkbox"
                    aria-label="Select all issues on this page"
                    checked={allSelected}
                    onChange={(e) => {
                      const next = new Set(selected);
                      for (const issue of issues) {
                        if (e.target.checked) {
                          next.add(issue.key);
                        } else {
                          next.delete(issue.key);
                        }
                      }
                      onSelect(next);
                    }}
                  />
                </th>
              )}
              {columns.map((column) => {
                const sortKey = SORTABLE[column];
                const active = sortKey !== undefined && sortKey === sort;
                return (
                  <th
                    key={column}
                    scope="col"
                    className={column === 'title' ? styles.titleCol : undefined}
                    aria-sort={active ? (direction === 'asc' ? 'ascending' : 'descending') : undefined}
                  >
                    {sortKey && onSort ? (
                      <button
                        type="button"
                        className={styles.sortButton}
                        onClick={() => {
                          onSort(sortKey, active && direction === 'asc' ? 'desc' : 'asc');
                        }}
                      >
                        {COLUMN_LABELS[column]}
                        {active && (direction === 'asc' ? <ArrowUp size={12} aria-hidden="true" /> : <ArrowDown size={12} aria-hidden="true" />)}
                      </button>
                    ) : (
                      COLUMN_LABELS[column]
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {issues.map((issue) => (
              <tr key={issue.id} data-selected={selected?.has(issue.key) === true ? true : undefined}>
                {onSelect && selected && (
                  <td className={styles.check}>
                    <input
                      type="checkbox"
                      aria-label={`Select ${issue.key}`}
                      checked={selected.has(issue.key)}
                      onChange={(e) => {
                        const next = new Set(selected);
                        if (e.target.checked) {
                          next.add(issue.key);
                        } else {
                          next.delete(issue.key);
                        }
                        onSelect(next);
                      }}
                    />
                  </td>
                )}
                {columns.map((column) => (
                  <td key={column} className={column === 'title' ? styles.titleCol : undefined}>
                    {cell(org, issue, column)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {onPage && total !== undefined && total > pageSize && (
        <nav className={styles.pager} aria-label="Pagination">
          <span className="muted">
            {page * pageSize + 1}–{Math.min(total, (page + 1) * pageSize)} of {total}
          </span>
          <Button size="sm" variant="ghost" icon={<ChevronLeft size={14} aria-hidden="true" />} disabled={page === 0} onClick={() => { onPage(page - 1); }}>
            Previous
          </Button>
          <Button size="sm" variant="ghost" icon={<ChevronRight size={14} aria-hidden="true" />} disabled={page + 1 >= pages} onClick={() => { onPage(page + 1); }}>
            Next
          </Button>
        </nav>
      )}
    </div>
  );
}

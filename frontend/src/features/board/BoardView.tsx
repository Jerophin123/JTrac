import {
  closestCorners,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { CalendarClock, GitBranch, MoreHorizontal, Plus } from 'lucide-react';
import { useState } from 'react';
import { call } from '../../api/call';
import { api } from '../../api/client';
import { boardQuery, keys } from '../../api/queries';
import type { Board, IssueSummary, IssueView } from '../../api/types';
import { useOrgSlug, useProject } from '../../app/params';
import { describeError } from '../../lib/errors';
import { formatShortDate, isOverdue } from '../../lib/format';
import { Alert } from '../../ui/Alert';
import { Avatar } from '../../ui/Avatar';
import { LabelChip, PriorityIcon } from '../../ui/Badge';
import { Button } from '../../ui/Button';
import { Menu } from '../../ui/Menu';
import { Skeleton } from '../../ui/Page';
import { useToast } from '../../ui/Toast';
import { CreateIssueDialog } from '../issues/CreateIssueDialog';
import { FilterBar } from '../issues/FilterBar';
import { toIssueFilter, type FilterState } from '../issues/filters';
import styles from './BoardView.module.css';

interface MoveRequest {
  issue: IssueSummary;
  statusId: string;
  afterId: string | null;
  beforeId: string | null;
}

/** Places an issue into a column at an index, returning the new board (pure; used for optimistic updates). */
export function applyMove(board: Board, issueId: string, statusId: string, index: number): Board {
  let moving: IssueSummary | undefined;
  const without = board.columns.map((column) => {
    const found = column.issues.find((i) => i.id === issueId);
    if (found) {
      moving = found;
      return { ...column, total: column.total - 1, issues: column.issues.filter((i) => i.id !== issueId) };
    }
    return column;
  });
  if (!moving) {
    return board;
  }
  const moved = moving;
  return {
    ...board,
    columns: without.map((column) => {
      if (column.status.id !== statusId) {
        return column;
      }
      const issues = [...column.issues];
      const target = column.status;
      issues.splice(Math.max(0, Math.min(index, issues.length)), 0, {
        ...moved,
        status: { id: target.id, name: target.name, category: target.category },
      });
      return { ...column, total: column.total + 1, issues };
    }),
  };
}

export function BoardView({ state, onChange }: { state: FilterState; onChange: (next: FilterState) => void }) {
  const org = useOrgSlug();
  const project = useProject();
  const queryClient = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const filter = { ...toIssueFilter({ ...state, state: state.state ?? 'all' }, project.key), sort: undefined, direction: undefined };
  const queryKey = keys.board(org, project.key, filter);
  const board = useQuery(boardQuery(org, project.key, filter));
  const [activeId, setActiveId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const canEdit = project.myPermissions.includes('EDIT_ISSUES') || project.myPermissions.includes('TRANSITION_ISSUES');
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const move = useMutation({
    mutationFn: ({ issue, statusId, afterId, beforeId }: MoveRequest) =>
      call(async () => {
        // Use the freshest version we know of (the board may be a little stale).
        const current = queryClient.getQueryData<IssueView>(keys.issue(org, issue.key));
        const version = current?.version ?? (await call(() => api.GET('/api/v1/orgs/{org}/issues/{issueKey}', { params: { path: { org, issueKey: issue.key } } }))).version;
        return api.POST('/api/v1/orgs/{org}/issues/{issueKey}/move', {
          params: { path: { org, issueKey: issue.key } },
          body: { statusId: statusId === issue.status.id ? null : statusId, afterId, beforeId, version },
        });
      }),
    onError: (error, request) => {
      toast.error(`${request.issue.key} could not be moved: ${describeError(error)}`);
    },
    onSettled: async (_data, _error, request) => {
      await queryClient.invalidateQueries({ queryKey: keys.issuesRoot(org) });
      await queryClient.invalidateQueries({ queryKey: keys.issue(org, request.issue.key) });
    },
  });

  const moveTo = (issue: IssueSummary, statusId: string, index: number) => {
    const data = queryClient.getQueryData<Board>(queryKey);
    if (!data) {
      return;
    }
    const next = applyMove(data, issue.id, statusId, index);
    const column = next.columns.find((c) => c.status.id === statusId);
    const position = column?.issues.findIndex((i) => i.id === issue.id) ?? -1;
    queryClient.setQueryData(queryKey, next);
    move.mutate({
      issue,
      statusId,
      afterId: position > 0 ? (column?.issues[position - 1]?.id ?? null) : null,
      beforeId: column && position < column.issues.length - 1 ? (column.issues[position + 1]?.id ?? null) : null,
    });
  };

  const findIssue = (id: string) => board.data?.columns.flatMap((c) => c.issues).find((i) => i.id === id);
  const columnOf = (id: string) =>
    board.data?.columns.find((c) => c.status.id === id || c.issues.some((i) => i.id === id));

  const onDragStart = (event: DragStartEvent) => {
    setActiveId(String(event.active.id));
  };

  const onDragEnd = (event: DragEndEvent) => {
    setActiveId(null);
    const issue = findIssue(String(event.active.id));
    if (!issue || !event.over) {
      return;
    }
    const target = columnOf(String(event.over.id));
    if (!target) {
      return;
    }
    const overIndex = target.issues.findIndex((i) => i.id === event.over?.id);
    const index = overIndex === -1 ? target.issues.length : overIndex;
    const source = columnOf(issue.id);
    if (source?.status.id === target.status.id && source.issues.findIndex((i) => i.id === issue.id) === index) {
      return;
    }
    moveTo(issue, target.status.id, index);
  };

  const announcements: Announcements = {
    onDragStart: ({ active }) => `Picked up ${findIssue(String(active.id))?.key ?? 'issue'}.`,
    onDragOver: ({ active, over }) =>
      over ? `${findIssue(String(active.id))?.key ?? 'Issue'} is over ${columnOf(String(over.id))?.status.name ?? 'a column'}.` : undefined,
    onDragEnd: ({ active, over }) =>
      over ? `Dropped ${findIssue(String(active.id))?.key ?? 'issue'} in ${columnOf(String(over.id))?.status.name ?? 'the column'}.` : 'Move cancelled.',
    onDragCancel: ({ active }) => `Moving ${findIssue(String(active.id))?.key ?? 'issue'} was cancelled.`,
  };

  if (board.isPending) {
    return <Skeleton lines={8} label="Loading board" />;
  }
  if (board.isError) {
    return <Alert tone="danger">{describeError(board.error)}</Alert>;
  }
  const active = activeId ? findIssue(activeId) : undefined;

  return (
    <div className="stack">
      <FilterBar
        org={org}
        state={{ ...state, state: state.state ?? 'all' }}
        onChange={onChange}
        project={project.key}
        actions={
          project.myPermissions.includes('CREATE_ISSUES') && (
            <Button variant="primary" icon={<Plus size={16} aria-hidden="true" />} onClick={() => { setCreating(true); }}>
              Create issue
            </Button>
          )
        }
      />
      <p className="visually-hidden" id="board-help">
        To move an issue with the keyboard, focus its card, press Space to pick it up, use the arrow keys to move it, and press
        Space again to drop it. Or use the card’s “Move to” menu.
      </p>
      <DndContext
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
        onDragCancel={() => { setActiveId(null); }}
        accessibility={{ announcements, screenReaderInstructions: { draggable: 'Press Space to pick up the issue, arrows to move, Space to drop, Escape to cancel.' } }}
      >
        <div className={styles.board} role="list" aria-label={`${project.name} board`}>
          {board.data.columns.map((column) => (
            <Column
              key={column.status.id}
              id={column.status.id}
              name={column.status.name}
              category={column.status.category}
              total={column.total}
              issues={column.issues}
              disabled={!canEdit || project.archived}
              statuses={board.data.columns.map((c) => c.status)}
              onMoveTo={(issue, statusId) => {
                moveTo(issue, statusId, Number.MAX_SAFE_INTEGER);
              }}
              onOpen={(issue) => void navigate({ to: '/$org/issue/$issueKey', params: { org, issueKey: issue.key } })}
            />
          ))}
        </div>
        <DragOverlay>{active ? <CardBody issue={active} overlay /> : null}</DragOverlay>
      </DndContext>
      <CreateIssueDialog org={org} open={creating} defaultProject={project.key} onClose={() => { setCreating(false); }} />
    </div>
  );
}

interface ColumnProps {
  id: string;
  name: string;
  category: string;
  total: number;
  issues: IssueSummary[];
  disabled: boolean;
  statuses: { id: string; name: string }[];
  onMoveTo: (issue: IssueSummary, statusId: string) => void;
  onOpen: (issue: IssueSummary) => void;
}

function Column({ id, name, category, total, issues, disabled, statuses, onMoveTo, onOpen }: ColumnProps) {
  const { setNodeRef, isOver } = useDroppable({ id, disabled });
  return (
    <section role="listitem" className={styles.column} data-over={isOver || undefined} aria-labelledby={`col-${id}`}>
      <header className={styles.columnHeader}>
        <span className={styles.marker} data-category={category} aria-hidden="true" />
        <h2 id={`col-${id}`} className={styles.columnTitle}>
          {name}
        </h2>
        <span className={styles.count} aria-label={`${String(total)} issues`}>
          {total}
        </span>
      </header>
      <SortableContext id={id} items={issues.map((i) => i.id)} strategy={verticalListSortingStrategy} disabled={disabled}>
        <ul ref={setNodeRef} className={styles.cards} aria-describedby="board-help">
          {issues.map((issue) => (
            <SortableCard key={issue.id} issue={issue} disabled={disabled} statuses={statuses} onMoveTo={onMoveTo} onOpen={onOpen} />
          ))}
          {issues.length === 0 && <li className={styles.emptyColumn}>No issues</li>}
          {total > issues.length && <li className={styles.more}>{total - issues.length} more not shown. Use the list view to see them all.</li>}
        </ul>
      </SortableContext>
    </section>
  );
}

function SortableCard({
  issue,
  disabled,
  statuses,
  onMoveTo,
  onOpen,
}: {
  issue: IssueSummary;
  disabled: boolean;
  statuses: { id: string; name: string }[];
  onMoveTo: (issue: IssueSummary, statusId: string) => void;
  onOpen: (issue: IssueSummary) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: issue.id, disabled });
  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- dnd-kit sortable card with keyboard interactions
    <li
      ref={setNodeRef}
      className={styles.card}
      data-dragging={isDragging || undefined}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      {...attributes}
      {...listeners}
      aria-roledescription="draggable issue"
      aria-label={`${issue.key}: ${issue.title}, ${issue.status.name}`}
      onKeyDown={(event) => {
        listeners?.onKeyDown?.(event);
        if (event.key === 'Enter' && !event.defaultPrevented) {
          onOpen(issue);
        }
      }}
    >
      <CardBody issue={issue} />
      {!disabled && (
        <div className={styles.cardMenu}>
          <Menu
            items={statuses
              .filter((s) => s.id !== issue.status.id)
              .map((s) => ({ label: `Move to ${s.name}`, onSelect: () => { onMoveTo(issue, s.id); } }))}
            trigger={(props) => (
              <button
                type="button"
                className={styles.menuButton}
                aria-label={`Move ${issue.key} to…`}
                {...props}
                onPointerDown={(e) => { e.stopPropagation(); }}
                onKeyDown={(e) => {
                  e.stopPropagation();
                  props.onKeyDown(e);
                }}
              >
                <MoreHorizontal size={14} aria-hidden="true" />
              </button>
            )}
          />
        </div>
      )}
    </li>
  );
}

function CardBody({ issue, overlay = false }: { issue: IssueSummary; overlay?: boolean }) {
  const org = useOrgSlug();
  const overdue = isOverdue(issue.dueDate, Boolean(issue.resolvedAt));
  return (
    <div className={overlay ? `${styles.cardBody ?? ''} ${styles.overlay ?? ''}` : styles.cardBody}>
      <div className={styles.cardTop}>
        <span className="issue-key">{issue.key}</span>
        <PriorityIcon priority={issue.priority} />
      </div>
      <Link
        to="/$org/issue/$issueKey"
        params={{ org, issueKey: issue.key }}
        className={styles.cardTitle}
        tabIndex={-1}
        onPointerDown={(e) => { e.stopPropagation(); }}
      >
        {issue.title}
      </Link>
      {(issue.labels?.length ?? 0) > 0 && (
        <div className={styles.cardLabels}>
          {issue.labels?.slice(0, 3).map((l) => <LabelChip key={l.id} name={l.name} color={l.color} />)}
        </div>
      )}
      <div className={styles.cardMeta}>
        {issue.dueDate && (
          <span className={overdue ? 'overdue' : undefined}>
            <CalendarClock size={12} aria-hidden="true" /> {formatShortDate(issue.dueDate)}
            {overdue && <span className="visually-hidden"> (overdue)</span>}
          </span>
        )}
        {issue.subtaskCount > 0 && (
          <span>
            <GitBranch size={12} aria-hidden="true" /> {issue.subtasksDone}/{issue.subtaskCount}
          </span>
        )}
        <span className={styles.assignee}>
          {issue.assignee ? (
            <span title={issue.assignee.displayName}>
              <Avatar name={issue.assignee.displayName} />
              <span className="visually-hidden">Assigned to {issue.assignee.displayName}</span>
            </span>
          ) : null}
        </span>
      </div>
    </div>
  );
}

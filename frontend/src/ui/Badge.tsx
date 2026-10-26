import { ArrowDown, ArrowUp, ChevronsUp, Equal, Minus } from 'lucide-react';
import type { ReactNode } from 'react';
import type { Priority, StatusCategory } from '../api/types';
import styles from './Badge.module.css';

type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info';

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`${styles.badge ?? ''} ${styles[tone] ?? ''}`}>{children}</span>;
}

const CATEGORY_CLASS: Record<StatusCategory, string | undefined> = {
  TODO: styles.todo,
  IN_PROGRESS: styles.progress,
  DONE: styles.done,
  CANCELED: styles.canceled,
};

/** A workflow status. The category is conveyed by color and by a shape marker (not color alone). */
export function StatusBadge({ name, category }: { name: string; category: string }) {
  const cat = (category in CATEGORY_CLASS ? category : 'TODO') as StatusCategory;
  return (
    <span className={`${styles.status ?? ''} ${CATEGORY_CLASS[cat] ?? ''}`}>
      <span className={styles.marker} data-category={cat} aria-hidden="true" />
      {name}
    </span>
  );
}

const PRIORITY_META: Record<NonNullable<Priority>, { label: string; icon: ReactNode; className: string | undefined }> = {
  URGENT: { label: 'Urgent', icon: <ChevronsUp size={14} aria-hidden="true" />, className: styles.urgent },
  HIGH: { label: 'High', icon: <ArrowUp size={14} aria-hidden="true" />, className: styles.high },
  MEDIUM: { label: 'Medium', icon: <Equal size={14} aria-hidden="true" />, className: styles.medium },
  LOW: { label: 'Low', icon: <ArrowDown size={14} aria-hidden="true" />, className: styles.low },
  NONE: { label: 'No priority', icon: <Minus size={14} aria-hidden="true" />, className: styles.none },
};

export function priorityLabel(priority: Priority | null | undefined): string {
  return priority ? PRIORITY_META[priority].label : 'Hidden';
}

export function PriorityIcon({ priority, withLabel = false }: { priority: Priority | null | undefined; withLabel?: boolean }) {
  if (!priority) {
    return null;
  }
  const meta = PRIORITY_META[priority];
  return (
    <span className={`${styles.priority ?? ''} ${meta.className ?? ''}`} title={meta.label}>
      {meta.icon}
      {withLabel ? <span>{meta.label}</span> : <span className="visually-hidden">{meta.label}</span>}
    </span>
  );
}

export function LabelChip({ name, color }: { name: string; color?: string | null }) {
  return (
    <span className={styles.label}>
      <span className={styles.labelDot} style={color ? { background: color } : undefined} aria-hidden="true" />
      {name}
    </span>
  );
}

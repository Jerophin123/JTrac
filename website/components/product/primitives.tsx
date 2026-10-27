import { ArrowDown, ArrowUp, ChevronsUp, Equal, Minus } from 'lucide-react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import type { Category, Label, Priority } from './data';
import avatarStyles from './styles/Avatar.module.css';
import badgeStyles from './styles/Badge.module.css';
import base from './styles/Base.module.css';
import buttonStyles from './styles/Button.module.css';
import orgStyles from './styles/OrgMark.module.css';

/*
 * Markup replicas of Jtrac's UI primitives (jtac/frontend/src/ui): same structure,
 * same class names, same icons. Data comes from props only.
 */

const CATEGORY_CLASS: Record<Category, string | undefined> = {
  TODO: badgeStyles.todo,
  IN_PROGRESS: badgeStyles.progress,
  DONE: badgeStyles.done,
  CANCELED: badgeStyles.canceled,
};

export function StatusBadge({ name, category }: { name: string; category: Category }) {
  return (
    <span className={`${badgeStyles.status} ${CATEGORY_CLASS[category] ?? ''}`}>
      <span className={badgeStyles.marker} data-category={category} aria-hidden="true" />
      {name}
    </span>
  );
}

const PRIORITY_META: Record<Priority, { label: string; icon: ReactNode; className: string | undefined }> = {
  URGENT: { label: 'Urgent', icon: <ChevronsUp size={14} aria-hidden="true" />, className: badgeStyles.urgent },
  HIGH: { label: 'High', icon: <ArrowUp size={14} aria-hidden="true" />, className: badgeStyles.high },
  MEDIUM: { label: 'Medium', icon: <Equal size={14} aria-hidden="true" />, className: badgeStyles.medium },
  LOW: { label: 'Low', icon: <ArrowDown size={14} aria-hidden="true" />, className: badgeStyles.low },
  NONE: { label: 'No priority', icon: <Minus size={14} aria-hidden="true" />, className: badgeStyles.none },
};

export function PriorityIcon({ priority, withLabel = false }: { priority: Priority; withLabel?: boolean }) {
  const meta = PRIORITY_META[priority];
  return (
    <span className={`${badgeStyles.priority} ${meta.className ?? ''}`} title={meta.label}>
      {meta.icon}
      {withLabel ? <span>{meta.label}</span> : <span className={base.visuallyHidden}>{meta.label}</span>}
    </span>
  );
}

export function LabelChip({ name, color }: Label) {
  return (
    <span className={badgeStyles.label}>
      <span className={badgeStyles.labelDot} style={{ background: color }} aria-hidden="true" />
      {name}
    </span>
  );
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'accent'; children: ReactNode }) {
  return <span className={`${badgeStyles.badge} ${badgeStyles[tone] ?? ''}`}>{children}</span>;
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '?';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + last).toUpperCase();
}

export function Avatar({ name }: { name: string }) {
  return (
    <span className={avatarStyles.avatar} aria-hidden="true">
      {initialsOf(name)}
    </span>
  );
}

export function OrgMark({ name }: { name: string }) {
  return (
    <span className={`${orgStyles.mark} ${orgStyles.sm}`} aria-hidden="true">
      {name.charAt(0).toUpperCase()}
    </span>
  );
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost';
  size?: 'sm' | 'md';
  icon?: ReactNode;
}

/** Only used for controls that actually do something in the replica. */
export function Button({ variant = 'secondary', size = 'sm', icon, children, className, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      className={`${buttonStyles.button} ${buttonStyles[variant] ?? ''} ${buttonStyles[size] ?? ''} ${className ?? ''}`}
      {...rest}
    >
      {icon}
      {children ? <span className={buttonStyles.label}>{children}</span> : null}
    </button>
  );
}

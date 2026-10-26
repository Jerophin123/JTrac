import { CircleAlert, CircleCheck, Info, TriangleAlert } from 'lucide-react';
import type { ReactNode } from 'react';
import styles from './Alert.module.css';

type Tone = 'danger' | 'warning' | 'success' | 'info';

const ICONS = { danger: CircleAlert, warning: TriangleAlert, success: CircleCheck, info: Info } as const;

interface AlertProps {
  tone: Tone;
  title?: string;
  children?: ReactNode;
}

/** Errors interrupt (role=alert); other tones are polite status messages. */
export function Alert({ tone, title, children }: AlertProps) {
  const Icon = ICONS[tone];
  return (
    <div className={`${styles.alert ?? ''} ${styles[tone] ?? ''}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon className={styles.icon} size={16} strokeWidth={2} aria-hidden="true" />
      <div className={styles.body}>
        {title && <p className={styles.title}>{title}</p>}
        {children && <div className={styles.text}>{children}</div>}
      </div>
    </div>
  );
}

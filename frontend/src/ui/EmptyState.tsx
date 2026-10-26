import type { ReactNode } from 'react';
import styles from './EmptyState.module.css';

interface EmptyStateProps {
  icon: ReactNode;
  title: string;
  children: ReactNode;
}

export function EmptyState({ icon, title, children }: EmptyStateProps) {
  return (
    <section className={styles.empty} aria-label={title}>
      <div className={styles.icon} aria-hidden="true">
        {icon}
      </div>
      <h2 className={styles.title}>{title}</h2>
      <div className={styles.text}>{children}</div>
    </section>
  );
}

import styles from './Spinner.module.css';

interface SpinnerProps {
  size?: 'sm' | 'md';
  /** Accessible label; omit when the surrounding element already announces progress. */
  label?: string;
}

export function Spinner({ size = 'md', label }: SpinnerProps) {
  return (
    <span className={`${styles.spinner ?? ''} ${styles[size] ?? ''}`} role={label ? 'status' : undefined}>
      <span className={styles.ring} aria-hidden="true" />
      {label && <span className="visually-hidden">{label}</span>}
    </span>
  );
}

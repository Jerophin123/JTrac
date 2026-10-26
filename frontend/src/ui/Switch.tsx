import { useId, type InputHTMLAttributes, type Ref } from 'react';
import styles from './Switch.module.css';

interface SwitchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id' | 'role'> {
  label: string;
  /** Keep the label for screen readers only (when a row already shows it). */
  hideLabel?: boolean;
  ref?: Ref<HTMLInputElement>;
}

/** An on/off setting. A native checkbox with role="switch", drawn as a slanted track. */
export function Switch({ label, hideLabel = false, className, ref, ...rest }: SwitchProps) {
  const id = useId();
  return (
    <span className={`${styles.switch ?? ''} ${className ?? ''}`}>
      <input ref={ref} id={id} type="checkbox" role="switch" className={styles.input} {...rest} />
      <label htmlFor={id} className={styles.label}>
        <span className={styles.track} aria-hidden="true">
          <span className={styles.thumb} />
        </span>
        <span className={hideLabel ? 'visually-hidden' : styles.text}>{label}</span>
      </label>
    </span>
  );
}

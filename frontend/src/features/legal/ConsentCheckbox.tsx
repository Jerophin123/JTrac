import { useId, type InputHTMLAttributes, type Ref } from 'react';
import styles from './Consent.module.css';

interface ConsentCheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id'> {
  error?: string | undefined;
  ref?: Ref<HTMLInputElement>;
}

/**
 * The affirmative, unticked consent the DPDP Act asks for: it links to both documents, which
 * open in a new tab so a half-filled form is not lost.
 */
export function ConsentCheckbox({ error, className, ref, ...rest }: ConsentCheckboxProps) {
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div className={`${styles.consent ?? ''} ${className ?? ''}`} data-invalid={error ? true : undefined}>
      <input
        ref={ref}
        id={id}
        type="checkbox"
        className={styles.checkbox}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        {...rest}
      />
      <div className={styles.text}>
        <label htmlFor={id} className={styles.label}>
          I have read and agree to the{' '}
          <a href="/legal/terms" target="_blank" rel="noopener">
            Terms of Service
          </a>{' '}
          and the{' '}
          <a href="/legal/privacy" target="_blank" rel="noopener">
            Privacy Notice
          </a>
          , and I consent to my personal data being processed as described there.
        </label>
        <span className={styles.hint}>You can withdraw consent at any time from Account → Privacy &amp; data.</span>
        {error && (
          <p id={errorId} className={styles.error}>
            {error}
          </p>
        )}
      </div>
    </div>
  );
}

import {
  useId,
  type InputHTMLAttributes,
  type ReactNode,
  type Ref,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';
import styles from './Field.module.css';
import { SelectControl } from './SelectControl';

interface FieldShellProps {
  label: string;
  hint?: string | undefined;
  error?: string | undefined;
  hideLabel?: boolean;
  className?: string | undefined;
  children: (ids: { id: string; describedBy: string | undefined; invalid: true | undefined }) => ReactNode;
}

/** Label, hint and error wiring shared by every form control. */
export function FieldShell({ label, hint, error, hideLabel, className, children }: FieldShellProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`${styles.field ?? ''} ${className ?? ''}`}>
      <label className={hideLabel ? 'visually-hidden' : styles.label} htmlFor={id}>
        {label}
      </label>
      {children({ id, describedBy, invalid: error ? true : undefined })}
      {hint && !error && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className={styles.error}>
          {error}
        </p>
      )}
    </div>
  );
}

interface CommonProps {
  label: string;
  hint?: string | undefined;
  error?: string | undefined;
  hideLabel?: boolean;
}

export function TextArea({
  label,
  hint,
  error,
  hideLabel,
  className,
  ref,
  ...rest
}: CommonProps & Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> & { ref?: Ref<HTMLTextAreaElement> }) {
  return (
    <FieldShell label={label} hint={hint} error={error} hideLabel={hideLabel} className={className}>
      {({ id, describedBy, invalid }) => (
        <textarea
          ref={ref}
          id={id}
          className={styles.textarea}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          {...rest}
        />
      )}
    </FieldShell>
  );
}

export function Select({
  label,
  hint,
  error,
  hideLabel,
  className,
  children,
  ref,
  ...rest
}: CommonProps & Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> & { ref?: Ref<HTMLSelectElement> }) {
  return (
    <FieldShell label={label} hint={hint} error={error} hideLabel={hideLabel} className={className}>
      {({ id, describedBy, invalid }) => (
        <SelectControl id={id} describedBy={describedBy} invalid={invalid} selectRef={ref} {...rest}>
          {children}
        </SelectControl>
      )}
    </FieldShell>
  );
}

export function Input({
  label,
  hint,
  error,
  hideLabel,
  className,
  ref,
  ...rest
}: CommonProps & Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & { ref?: Ref<HTMLInputElement> }) {
  return (
    <FieldShell label={label} hint={hint} error={error} hideLabel={hideLabel} className={className}>
      {({ id, describedBy, invalid }) => (
        <input ref={ref} id={id} className={styles.input} aria-describedby={describedBy} aria-invalid={invalid} {...rest} />
      )}
    </FieldShell>
  );
}

export function Checkbox({
  label,
  hint,
  className,
  ref,
  ...rest
}: { label: string; hint?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'id'> & {
    ref?: Ref<HTMLInputElement>;
  }) {
  const id = useId();
  return (
    <div className={`${styles.check ?? ''} ${className ?? ''}`}>
      <input ref={ref} id={id} type="checkbox" className={styles.checkbox} aria-describedby={hint ? `${id}-hint` : undefined} {...rest} />
      <div>
        <label htmlFor={id} className={styles.checkLabel}>
          {label}
        </label>
        {hint && (
          <p id={`${id}-hint`} className={styles.hint}>
            {hint}
          </p>
        )}
      </div>
    </div>
  );
}

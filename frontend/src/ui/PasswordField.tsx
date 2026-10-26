import { Eye, EyeOff } from 'lucide-react';
import { useState, type InputHTMLAttributes, type Ref } from 'react';
import { FieldShell } from './Field';
import fieldStyles from './Field.module.css';
import styles from './PasswordField.module.css';

interface PasswordFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type'> {
  label: string;
  hint?: string | undefined;
  error?: string | undefined;
  ref?: Ref<HTMLInputElement>;
}

/** A password input with a button that shows or hides what was typed. */
export function PasswordField({ label, hint, error, className, ref, ...rest }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  return (
    <FieldShell label={label} hint={hint} error={error} className={className}>
      {({ id, describedBy, invalid }) => (
        <div className={styles.wrap}>
          <input
            ref={ref}
            id={id}
            type={visible ? 'text' : 'password'}
            className={`${fieldStyles.input ?? ''} ${styles.input ?? ''}`}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            {...rest}
          />
          <button
            type="button"
            className={styles.toggle}
            aria-label={visible ? `Hide ${label.toLowerCase()}` : `Show ${label.toLowerCase()}`}
            aria-pressed={visible}
            aria-controls={id}
            title={visible ? 'Hide' : 'Show'}
            disabled={rest.disabled}
            onClick={() => { setVisible((v) => !v); }}
          >
            {visible ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
          </button>
        </div>
      )}
    </FieldShell>
  );
}

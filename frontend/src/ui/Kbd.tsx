import { formatBinding, formatBindingText } from '../lib/shortcuts';
import styles from './Kbd.module.css';

interface KbdProps {
  /** A binding such as "mod+k" or "g i"; null shows that the shortcut is off. */
  binding: string | null;
  className?: string;
}

/** Key caps for a keyboard shortcut; screen readers hear the plain text ("Ctrl K", "G then I"). */
export function Kbd({ binding, className }: KbdProps) {
  if (!binding) {
    return <span className={`${styles.off ?? ''} ${className ?? ''}`}>Off</span>;
  }
  const chords = formatBinding(binding);
  return (
    <span className={`${styles.binding ?? ''} ${className ?? ''}`}>
      <span className="visually-hidden">{formatBindingText(binding)}</span>
      <span className={styles.caps} aria-hidden="true">
        {chords.map((keys, i) => (
          <span key={i} className={styles.chord}>
            {i > 0 && <span className={styles.then}>then</span>}
            {keys.map((key) => (
              <kbd key={key} className={styles.key}>
                {key}
              </kbd>
            ))}
          </span>
        ))}
      </span>
    </span>
  );
}

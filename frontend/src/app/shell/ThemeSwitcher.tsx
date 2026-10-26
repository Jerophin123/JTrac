import { Monitor, Moon, Sun } from 'lucide-react';
import { useTheme, type ThemePreference } from '../../lib/theme';
import styles from './ThemeSwitcher.module.css';

const OPTIONS: { value: ThemePreference; label: string; Icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', Icon: Sun },
  { value: 'dark', label: 'Dark', Icon: Moon },
  { value: 'system', label: 'System', Icon: Monitor },
];

/** Compact light / dark / system choice for the signed-out screens; signed-in users pick a theme in account settings. */
export function ThemeSwitcher() {
  const { preference, setPreference } = useTheme();
  return (
    <fieldset className={styles.switcher}>
      <legend className="visually-hidden">Theme</legend>
      {OPTIONS.map(({ value, label, Icon }) => (
        <label key={value} className={styles.option} title={`${label} theme`}>
          <input
            type="radio"
            name="jtrac-theme"
            value={value}
            checked={preference === value}
            onChange={() => {
              setPreference(value);
            }}
            className={styles.input}
          />
          <Icon size={14} aria-hidden="true" />
          <span className="visually-hidden">{label}</span>
        </label>
      ))}
    </fieldset>
  );
}

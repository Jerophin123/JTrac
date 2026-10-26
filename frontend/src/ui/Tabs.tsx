import { Link, type LinkProps } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import styles from './Tabs.module.css';

export interface TabLink {
  label: ReactNode;
  link: LinkProps;
  exact?: boolean;
}

/** Route-backed tabs: each tab is a real link (history, middle-click, deep links). */
export function TabNav({ label, tabs }: { label: string; tabs: TabLink[] }) {
  return (
    <nav aria-label={label} className={styles.nav}>
      <ul className={styles.list}>
        {tabs.map((tab, index) => (
          <li key={index}>
            <Link {...tab.link} className={styles.tab} activeOptions={{ exact: tab.exact ?? false, includeSearch: false }}>
              {tab.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}

interface SegmentedProps<T extends string> {
  label: string;
  value: T;
  options: { value: T; label: ReactNode }[];
  onChange: (value: T) => void;
}

/** A small radio group styled as a segmented control. */
export function Segmented<T extends string>({ label, value, options, onChange }: SegmentedProps<T>) {
  return (
    <div role="radiogroup" aria-label={label} className={styles.segmented}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={option.value === value}
          className={styles.segment}
          onClick={() => {
            onChange(option.value);
          }}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

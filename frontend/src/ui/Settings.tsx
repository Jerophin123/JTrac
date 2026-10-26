import { Link, type LinkProps } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import { Button } from './Button';
import styles from './Settings.module.css';

export interface SettingsNavItem {
  label: string;
  icon: ReactNode;
  link: LinkProps;
  exact?: boolean;
}

export interface SettingsNavGroup {
  label: string;
  items: SettingsNavItem[];
}

interface SettingsLayoutProps {
  /** Page title; omit when the layout is embedded under another page header. */
  title?: string;
  /** Who or what is being configured (for example the organization mark and name). */
  context?: ReactNode;
  navLabel: string;
  groups: SettingsNavGroup[];
  children: ReactNode;
}

/** Settings frame: grouped navigation on the start side, sections on the end side. */
export function SettingsLayout({ title, context, navLabel, groups, children }: SettingsLayoutProps) {
  return (
    <div className={styles.shell}>
      {(title ?? context) && (
        <header className={styles.top}>
          {title && <h1 className={styles.title}>{title}</h1>}
          {context && <div className={styles.context}>{context}</div>}
        </header>
      )}
      <div className={styles.layout}>
        <nav aria-label={navLabel} className={styles.nav}>
          {groups
            .filter((group) => group.items.length > 0)
            .map((group) => (
              <div key={group.label} className={styles.navGroup}>
                <p className={styles.navGroupLabel}>{group.label}</p>
                <ul className={styles.navList}>
                  {group.items.map((item) => (
                    <li key={item.label}>
                      <Link {...item.link} className={styles.navItem} activeOptions={{ exact: item.exact ?? false, includeSearch: false }}>
                        <span className={styles.navIcon} aria-hidden="true">
                          {item.icon}
                        </span>
                        {item.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
        </nav>
        <div className={styles.content}>{children}</div>
      </div>
    </div>
  );
}

interface SettingsSectionProps {
  title: string;
  description?: ReactNode;
  /** Header actions for stacked sections (for example "Add webhook"). */
  actions?: ReactNode;
  /** `split` puts the heading beside the card; `stacked` puts it above (for wide content). */
  layout?: 'split' | 'stacked';
  tone?: 'default' | 'danger';
  children: ReactNode;
}

/** One topic of a settings page: a heading with an explanation, and its controls in a card. */
export function SettingsSection({ title, description, actions, layout = 'split', tone = 'default', children }: SettingsSectionProps) {
  return (
    <section className={styles.section} data-layout={layout} data-tone={tone} aria-label={title}>
      <div className={styles.sectionHead}>
        <div className={styles.sectionText}>
          <h2 className={styles.sectionTitle}>{title}</h2>
          {description && <div className={styles.sectionDescription}>{description}</div>}
        </div>
        {actions && <div className={styles.sectionActions}>{actions}</div>}
      </div>
      <div className={styles.sectionBody}>{children}</div>
    </section>
  );
}

interface SettingsCardProps {
  title?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
  footer?: ReactNode;
  /** Remove the inner padding (for lists and tables that run edge to edge). */
  flush?: boolean;
  children?: ReactNode;
}

export function SettingsCard({ title, meta, actions, footer, flush = false, children }: SettingsCardProps) {
  return (
    <div className={styles.card}>
      {(title ?? actions) && (
        <div className={styles.cardHead}>
          <div className={styles.cardHeadText}>
            {title && <h3 className={styles.cardTitle}>{title}</h3>}
            {meta && <p className={styles.cardMeta}>{meta}</p>}
          </div>
          {actions && <div className={styles.cardActions}>{actions}</div>}
        </div>
      )}
      {children !== undefined && <div className={flush ? styles.cardFlush : styles.cardBody}>{children}</div>}
      {footer && <div className={styles.cardFooter}>{footer}</div>}
    </div>
  );
}

/** A labelled setting with its control on the end side; rows stack inside a card. */
export function SettingRow({ label, description, htmlFor, children }: { label: ReactNode; description?: ReactNode; htmlFor?: string; children: ReactNode }) {
  return (
    <div className={styles.row}>
      <div className={styles.rowText}>
        {htmlFor ? (
          <label className={styles.rowLabel} htmlFor={htmlFor}>
            {label}
          </label>
        ) : (
          <span className={styles.rowLabel}>{label}</span>
        )}
        {description && <span className={styles.rowDescription}>{description}</span>}
      </div>
      <div className={styles.rowControl}>{children}</div>
    </div>
  );
}

export function SettingsList({ label, children }: { label: string; children: ReactNode }) {
  return (
    <ul className={styles.list} aria-label={label}>
      {children}
    </ul>
  );
}

interface SettingsListItemProps {
  leading?: ReactNode;
  title: ReactNode;
  meta?: ReactNode;
  children?: ReactNode;
}

/** A person, invitation, token or endpoint: identity on the start side, controls on the end side. */
export function SettingsListItem({ leading, title, meta, children }: SettingsListItemProps) {
  return (
    <li className={styles.listItem}>
      {leading && <span className={styles.listLeading}>{leading}</span>}
      <span className={styles.listText}>
        <span className={styles.listTitle}>{title}</span>
        {meta && <span className={styles.listMeta}>{meta}</span>}
      </span>
      {children && <span className={styles.listActions}>{children}</span>}
    </li>
  );
}

export function SettingsEmpty({ children }: { children: ReactNode }) {
  return <p className={styles.empty}>{children}</p>;
}

interface SaveBarProps {
  dirty: boolean;
  saving: boolean;
  onDiscard: () => void;
  saveLabel?: string;
}

/** Sticky bar shown while a form has unsaved changes; its Save button submits the enclosing form. */
export function SaveBar({ dirty, saving, onDiscard, saveLabel = 'Save changes' }: SaveBarProps) {
  if (!dirty && !saving) {
    return null;
  }
  return (
    <div className={styles.saveBar} role="region" aria-label="Unsaved changes">
      <span className={styles.saveText}>
        <span className={styles.saveDot} aria-hidden="true" />
        {saving ? 'Saving…' : 'You have unsaved changes'}
      </span>
      <span className={styles.saveActions}>
        <Button variant="ghost" size="sm" disabled={!dirty || saving} onClick={onDiscard}>
          Discard
        </Button>
        <Button type="submit" variant="primary" size="sm" disabled={!dirty} loading={saving}>
          {saveLabel}
        </Button>
      </span>
    </div>
  );
}

import type { ReactNode } from 'react';
import styles from './LegalPages.module.css';

/**
 * Everything JTRAC stores in the browser. The Privacy Notice and the Cookie Policy both
 * render this list, so they can't drift apart. Keep it in step with the code: the session
 * cookie (SessionCookieConfiguration), the CSRF cookie (SecurityConfiguration) and the
 * localStorage keys in src/lib (theme, lastOrg, sidebar, shortcuts).
 */
export interface StoredItem {
  names: string[];
  kind: 'Cookie' | 'Browser storage';
  category: 'Strictly necessary' | 'Preference';
  purpose: ReactNode;
  details: ReactNode;
  lifetime: ReactNode;
}

export const STORED_ITEMS: StoredItem[] = [
  {
    names: ['JTRAC_SESSION'],
    kind: 'Cookie',
    category: 'Strictly necessary',
    purpose: 'Keeps you signed in. It holds a random session identifier, not your name or password.',
    details: 'HttpOnly (page scripts can’t read it), SameSite=Lax, Secure when JTRAC is served over HTTPS.',
    lifetime: 'Until you sign out or close the browser, or after the server’s session length without activity (8 hours by default).',
  },
  {
    names: ['XSRF-TOKEN'],
    kind: 'Cookie',
    category: 'Strictly necessary',
    purpose: 'A security token JTRAC sends back with every change, so other websites can’t act on your behalf (cross-site request forgery protection).',
    details: 'Readable by JTRAC’s own scripts, which is how the protection works; replaced when you sign in or out.',
    lifetime: 'Until you close the browser.',
  },
  {
    names: ['jtrac.theme'],
    kind: 'Browser storage',
    category: 'Preference',
    purpose: 'Your light, dark or system theme.',
    details: 'Stored on this device only; never sent to the server.',
    lifetime: 'Until you change it or clear this site’s data.',
  },
  {
    names: ['jtrac.lastOrg'],
    kind: 'Browser storage',
    category: 'Preference',
    purpose: 'The organization you opened last, so JTRAC returns there.',
    details: 'Stored on this device only; never sent to the server.',
    lifetime: 'Until you clear this site’s data.',
  },
  {
    names: ['jtrac.sidebar'],
    kind: 'Browser storage',
    category: 'Preference',
    purpose: 'Whether the sidebar is collapsed.',
    details: 'Stored on this device only; never sent to the server.',
    lifetime: 'Until you change it or clear this site’s data.',
  },
  {
    names: ['jtrac.shortcuts'],
    kind: 'Browser storage',
    category: 'Preference',
    purpose: 'Your custom keyboard shortcuts and whether single-key shortcuts are on.',
    details: 'Stored on this device only; never sent to the server.',
    lifetime: 'Until you reset them or clear this site’s data.',
  },
];

/** Older JTRAC versions used these names; JTRAC still reads them but no longer writes them. */
export const LEGACY_NAMES = ['jtac.theme', 'jtac.lastOrg'];

/** The inventory as a table (name, type, purpose, lifetime; optionally the technical details). */
export function StoredItemsTable({ withDetails = false }: { withDetails?: boolean }) {
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <caption className="visually-hidden">Cookies and browser storage used by JTRAC</caption>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Type</th>
            <th scope="col">Purpose</th>
            {withDetails && <th scope="col">Details</th>}
            <th scope="col">Lifetime</th>
          </tr>
        </thead>
        <tbody>
          {STORED_ITEMS.map((item) => (
            <tr key={item.names.join()}>
              <td>
                {item.names.map((name) => (
                  <code key={name}>{name}</code>
                ))}
              </td>
              <td>
                {item.kind}, {item.category.toLowerCase()}
              </td>
              <td>{item.purpose}</td>
              {withDetails && <td>{item.details}</td>}
              <td>{item.lifetime}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

import { describe, expect, it } from 'vitest';
import { LEGACY_NAMES, STORED_ITEMS } from './storage';

/** The app's own source files as text (Vite inlines them; tests and this inventory excluded). */
const SOURCES = import.meta.glob<string>(['../../**/*.{ts,tsx}', '!../../**/*.test.{ts,tsx}', '!./storage.tsx'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** Every `jtrac.*` / `jtac.*` storage key written or read anywhere in the app's source. */
function storageKeysInSource(): string[] {
  const keys = new Set<string>();
  for (const text of Object.values(SOURCES)) {
    for (const match of text.matchAll(/['"`](jtr?ac\.[a-zA-Z]+)['"`]/g)) {
      if (match[1]) {
        keys.add(match[1]);
      }
    }
  }
  return [...keys];
}

describe('the cookie and storage inventory', () => {
  it('lists every browser storage key the app uses', () => {
    const documented = new Set([...STORED_ITEMS.flatMap((item) => item.names), ...LEGACY_NAMES]);
    const keys = storageKeysInSource();
    expect(keys.length).toBeGreaterThan(0);
    // A new preference in localStorage must be added to storage.tsx (Privacy Notice + Cookie Policy).
    expect(keys.filter((key) => !documented.has(key))).toEqual([]);
  });

  it('lists the session and CSRF cookies the server sets', () => {
    expect(STORED_ITEMS.filter((item) => item.kind === 'Cookie').flatMap((item) => item.names)).toEqual(['JTRAC_SESSION', 'XSRF-TOKEN']);
  });
});

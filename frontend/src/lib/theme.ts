import { useSyncExternalStore } from 'react';

export type ThemePreference = 'light' | 'dark' | 'system';
export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'jtrac.theme';
const LEGACY_KEY = 'jtac.theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

/** Theme preference is a per-device UI convenience; it is safe if storage is unavailable. */
export function readThemePreference(): ThemePreference {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY) ?? window.localStorage.getItem(LEGACY_KEY);
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
  } catch {
    return 'system';
  }
}

function saveThemePreference(preference: ThemePreference): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, preference);
  } catch {
    // ignore: the choice simply won't persist
  }
}

export function resolveTheme(preference: ThemePreference, systemPrefersDark: boolean): Theme {
  if (preference === 'system') {
    return systemPrefersDark ? 'dark' : 'light';
  }
  return preference;
}

// One preference for the whole app, so every control (account settings, the signed-out
// corner) stays in step and the system setting is followed even when no control is mounted.
let current: ThemePreference | null = null;
const listeners = new Set<() => void>();

function preferenceSnapshot(): ThemePreference {
  current ??= readThemePreference();
  return current;
}

function applyTheme(): void {
  document.documentElement.dataset.theme = resolveTheme(preferenceSnapshot(), window.matchMedia(DARK_QUERY).matches);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function setThemePreference(next: ThemePreference): void {
  saveThemePreference(next);
  current = next;
  applyTheme();
  listeners.forEach((listener) => {
    listener();
  });
}

/** Applies the stored preference and keeps following the system setting. Call once at startup. */
export function startThemeSync(): () => void {
  const media = window.matchMedia(DARK_QUERY);
  applyTheme();
  media.addEventListener('change', applyTheme);
  return () => {
    media.removeEventListener('change', applyTheme);
  };
}

export function useTheme() {
  const preference = useSyncExternalStore(subscribe, preferenceSnapshot);
  return { preference, setPreference: setThemePreference };
}

const KEY = 'jtrac.lastOrg';
const LEGACY_KEY = 'jtac.lastOrg';

/** The last organization visited on this device (a navigation convenience, not data). */
export function readLastOrg(): string | undefined {
  try {
    return window.localStorage.getItem(KEY) ?? window.localStorage.getItem(LEGACY_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

export function rememberOrg(slug: string): void {
  try {
    window.localStorage.setItem(KEY, slug);
  } catch {
    // storage unavailable: nothing to remember
  }
}

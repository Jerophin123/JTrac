const KEY = 'jtrac.sidebar';

/** Whether the sidebar is collapsed to its icon rail on this device (a layout convenience, not data). */
export function readSidebarCollapsed(): boolean {
  try {
    return window.localStorage.getItem(KEY) === 'collapsed';
  } catch {
    return false;
  }
}

export function saveSidebarCollapsed(collapsed: boolean): void {
  try {
    window.localStorage.setItem(KEY, collapsed ? 'collapsed' : 'expanded');
  } catch {
    // storage unavailable: the choice simply won't persist
  }
}

/** Leaves the app for an agent's OAuth callback (a separate module so tests can observe it). */
export function leaveTo(url: string): void {
  window.location.assign(url);
}

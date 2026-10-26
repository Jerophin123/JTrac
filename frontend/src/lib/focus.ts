/** Ref callback that focuses an element once, when it mounts (stable identity). */
export function focusOnMount(element: HTMLElement | null): void {
  element?.focus();
}

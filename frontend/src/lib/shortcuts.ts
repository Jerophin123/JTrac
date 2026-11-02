import { useEffect, useRef, useSyncExternalStore, type KeyboardEvent as ReactKeyboardEvent } from 'react';

/*
 * Keyboard shortcuts: one registry, one listener, per-device customization.
 *
 * A binding is a chord ("mod+k", "shift+c", "?") or a two-chord sequence ("g i"). "mod" is
 * Ctrl on Windows and Linux and ⌘ on macOS, so a stored binding works on both. Keys use
 * KeyboardEvent.key (what the user sees on their layout); with Alt or mod held, letters and
 * digits come from KeyboardEvent.code, because macOS turns Alt+C into "ç".
 */

export type ShortcutGroup = 'General' | 'Issues' | 'Navigation';

export interface ShortcutDefinition {
  id: string;
  label: string;
  group: ShortcutGroup;
  defaultBinding: string;
  /** "org" shortcuts only work inside an organization. */
  scope: 'global' | 'org';
}

export const SHORTCUTS = [
  { id: 'commandPalette', label: 'Open the command palette', group: 'General', defaultBinding: 'mod+k', scope: 'org' },
  { id: 'focusSearch', label: 'Search from the sidebar', group: 'General', defaultBinding: '/', scope: 'org' },
  { id: 'showShortcuts', label: 'Show keyboard shortcuts', group: 'General', defaultBinding: '?', scope: 'global' },
  { id: 'toggleSidebar', label: 'Collapse or expand the sidebar', group: 'General', defaultBinding: '[', scope: 'global' },
  { id: 'createIssue', label: 'Create an issue', group: 'Issues', defaultBinding: 'c', scope: 'org' },
  { id: 'goMyWork', label: 'Go to My work', group: 'Navigation', defaultBinding: 'g h', scope: 'org' },
  { id: 'goInbox', label: 'Go to Inbox', group: 'Navigation', defaultBinding: 'g n', scope: 'org' },
  { id: 'goIssues', label: 'Go to Issues', group: 'Navigation', defaultBinding: 'g i', scope: 'org' },
  { id: 'goDashboards', label: 'Go to Dashboards', group: 'Navigation', defaultBinding: 'g d', scope: 'org' },
  { id: 'goProjects', label: 'Go to Projects', group: 'Navigation', defaultBinding: 'g p', scope: 'org' },
  { id: 'goSettings', label: 'Go to organization settings', group: 'Navigation', defaultBinding: 'g s', scope: 'org' },
  { id: 'goAccount', label: 'Go to your account', group: 'Navigation', defaultBinding: 'g a', scope: 'global' },
] as const satisfies readonly ShortcutDefinition[];

export type ShortcutId = (typeof SHORTCUTS)[number]['id'];

export const SHORTCUT_GROUPS: readonly ShortcutGroup[] = ['General', 'Issues', 'Navigation'];

const DEFINITIONS = new Map<string, ShortcutDefinition>(SHORTCUTS.map((s) => [s.id, s]));

/** How long the second key of a sequence ("g" then "i") may take. */
export const SEQUENCE_TIMEOUT_MS = 1000;

// --- Platform ---------------------------------------------------------------------------

export function isMacPlatform(): boolean {
  if (typeof navigator === 'undefined') {
    return false;
  }
  // userAgentData where available; the user agent string otherwise (navigator.platform is deprecated).
  const platform = (navigator as Navigator & { userAgentData?: { platform?: string } }).userAgentData?.platform ?? navigator.userAgent;
  return /mac|iphone|ipad|ipod/i.test(platform);
}

// --- Chords -----------------------------------------------------------------------------

interface Chord {
  mod: boolean;
  alt: boolean;
  shift: boolean;
  key: string;
}

const NAMED_KEYS = new Set(['arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'enter', 'space', 'backspace', 'delete', 'home', 'end', 'pageup', 'pagedown']);
const FUNCTION_KEY = /^f([1-9]|1[0-2])$/;

function parseChord(text: string): Chord | null {
  // "+" itself may be the key ("shift++" is not used; a lone "+" is the plus key).
  const parts = text === '+' ? ['+'] : text.split('+');
  const key = parts.pop();
  if (!key) {
    return null;
  }
  const mods = new Set(parts);
  if ([...mods].some((m) => m !== 'mod' && m !== 'alt' && m !== 'shift') || mods.size !== parts.length) {
    return null;
  }
  const valid = key.length === 1 || NAMED_KEYS.has(key) || FUNCTION_KEY.test(key);
  return valid ? { mod: mods.has('mod'), alt: mods.has('alt'), shift: mods.has('shift'), key } : null;
}

function chordToString(chord: Chord): string {
  return [chord.mod && 'mod', chord.alt && 'alt', chord.shift && 'shift', chord.key].filter(Boolean).join('+');
}

/** Splits a binding into its chords (one, or two for a sequence); null if malformed. */
export function parseBinding(binding: string): string[] | null {
  const chords = binding.trim().split(/\s+/);
  if (chords.length === 0 || chords.length > 2) {
    return null;
  }
  const parsed = chords.map(parseChord);
  if (parsed.some((c) => c === null)) {
    return null;
  }
  return (parsed as Chord[]).map(chordToString);
}

/** The chord a key press makes, or null for presses that can't be shortcuts (lone modifiers, IME). */
export function chordFromEvent(event: KeyboardEvent | ReactKeyboardEvent, mac = isMacPlatform()): string | null {
  if ('nativeEvent' in event ? event.nativeEvent.isComposing : event.isComposing) {
    return null;
  }
  const raw = event.key;
  if (!raw || ['Shift', 'Control', 'Alt', 'Meta', 'AltGraph', 'CapsLock', 'Dead', 'Unidentified', 'Process'].includes(raw)) {
    return null;
  }
  // The other platform's command key isn't part of "mod": Win+K or Ctrl+K on a Mac are not shortcuts.
  if (mac ? event.ctrlKey : event.metaKey) {
    return null;
  }
  const mod = mac ? event.metaKey : event.ctrlKey;
  const alt = event.altKey;
  let key = raw === ' ' ? 'space' : raw.toLowerCase();
  if (mod || alt) {
    const fromCode = /^Key([A-Z])$/.exec(event.code) ?? /^Digit([0-9])$/.exec(event.code);
    if (fromCode?.[1]) {
      key = fromCode[1].toLowerCase();
    }
  }
  if (key.length > 1 && !NAMED_KEYS.has(key) && !FUNCTION_KEY.test(key)) {
    return null;
  }
  // Shift is part of punctuation like "?" already; only letters (and named keys) keep it.
  const shift = event.shiftKey && (/^[a-z]$/.test(key) || key.length > 1);
  return chordToString({ mod, alt, shift, key });
}

function isPlainChord(chord: string): boolean {
  const parsed = parseChord(chord);
  return parsed !== null && !parsed.mod && !parsed.alt;
}

/** Whether a binding is made only of keys without Ctrl/⌘/Alt (governed by the single-key setting). */
export function isSingleKeyBinding(binding: string): boolean {
  return (parseBinding(binding) ?? []).every(isPlainChord);
}

// --- Validation and conflicts -------------------------------------------------------------

/** Combinations the browser or text editing relies on; taking them over would break expectations. */
const RESERVED = new Set(
  ['a', 'c', 'v', 'x', 'z', 'y', 'w', 't', 'n', 'l', 'r', 'q', 'f', 's', 'p', 'tab'].map((k) => `mod+${k}`).concat(
    ['mod+shift+z', 'mod+shift+t', 'mod+shift+n', 'mod+shift+w', 'f5', 'f11', 'f12'],
  ),
);
const NEVER_ALONE = new Set(['enter', 'space', 'backspace', 'delete', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'home', 'end', 'pageup', 'pagedown']);

/** Why a binding can't be used, or null if it can. */
export function bindingProblem(binding: string): string | null {
  const chords = parseBinding(binding);
  if (!chords) {
    return 'Those keys can’t be used as a shortcut.';
  }
  for (const chord of chords) {
    if (RESERVED.has(chord)) {
      return `${formatBindingText(chord)} belongs to the browser or text editing. Choose different keys.`;
    }
    const parsed = parseChord(chord);
    if (parsed && !parsed.mod && !parsed.alt && NEVER_ALONE.has(parsed.key)) {
      return `${formatBindingText(chord)} is used to move around and type, so it needs Ctrl, ⌘ or Alt.`;
    }
  }
  if (chords.length === 2 && !chords.every(isPlainChord)) {
    return 'A two-key sequence uses keys without Ctrl, ⌘ or Alt (for example G then I).';
  }
  return null;
}

/**
 * Two bindings clash when they are the same, or when one is the start of the other: with
 * "g" taken, "g i" could never be typed.
 */
export function bindingsConflict(a: string, b: string): boolean {
  const x = parseBinding(a);
  const y = parseBinding(b);
  if (!x || !y) {
    return false;
  }
  const shorter = x.length <= y.length ? x : y;
  const longer = shorter === x ? y : x;
  return shorter.every((chord, i) => chord === longer[i]);
}

// --- Display -----------------------------------------------------------------------------

const KEY_NAMES: Record<string, string> = {
  arrowup: '↑',
  arrowdown: '↓',
  arrowleft: '←',
  arrowright: '→',
  enter: 'Enter',
  space: 'Space',
  backspace: 'Backspace',
  delete: 'Delete',
  home: 'Home',
  end: 'End',
  pageup: 'Page Up',
  pagedown: 'Page Down',
};

/** The keys to show for each chord of a binding, e.g. [["Ctrl", "K"]] or [["G"], ["I"]]. */
export function formatBinding(binding: string, mac = isMacPlatform()): string[][] {
  return (parseBinding(binding) ?? []).flatMap((text) => {
    const chord = parseChord(text);
    if (!chord) {
      return [];
    }
    const keys: string[] = [];
    if (chord.mod) keys.push(mac ? '⌘' : 'Ctrl');
    if (chord.alt) keys.push(mac ? '⌥' : 'Alt');
    if (chord.shift) keys.push(mac ? '⇧' : 'Shift');
    keys.push(KEY_NAMES[chord.key] ?? chord.key.toUpperCase());
    return [keys];
  });
}

/** Plain-text form, e.g. "Ctrl K" or "G then I", for titles and messages. */
export function formatBindingText(binding: string, mac = isMacPlatform()): string {
  return formatBinding(binding, mac)
    .map((keys) => keys.join(mac ? '' : ' '))
    .join(' then ');
}

/** The aria-keyshortcuts value for a single chord (sequences can't be expressed there). */
export function ariaKeyShortcuts(binding: string | null, mac = isMacPlatform()): string | undefined {
  const only = binding ? parseBinding(binding) : null;
  const text = only?.length === 1 ? only[0] : undefined;
  const chord = text ? parseChord(text) : null;
  if (!chord) {
    return undefined;
  }
  const parts = [chord.mod && (mac ? 'Meta' : 'Control'), chord.alt && 'Alt', chord.shift && 'Shift'].filter(Boolean) as string[];
  const key = chord.key === 'space' ? 'Space' : chord.key.length === 1 ? chord.key.toUpperCase() : chord.key.replace(/^./, (c) => c.toUpperCase());
  return [...parts, key].join('+');
}

// --- Settings store ------------------------------------------------------------------------

export interface ShortcutSettings {
  /** Custom bindings by shortcut id; null turns a shortcut off. Missing ids use the default. */
  bindings: Readonly<Record<string, string | null>>;
  /** Single-key shortcuts (C, ?, G then I, ...) can be turned off (WCAG 2.1.4). */
  singleKeyEnabled: boolean;
}

const STORAGE_KEY = 'jtrac.shortcuts';
const DEFAULT_SETTINGS: ShortcutSettings = { bindings: {}, singleKeyEnabled: true };

/** Reads the stored settings, dropping anything unknown or invalid (falls back to defaults). */
export function readShortcutSettings(): ShortcutSettings {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return DEFAULT_SETTINGS;
    }
    const value = JSON.parse(raw) as { bindings?: unknown; singleKeyEnabled?: unknown };
    const bindings: Record<string, string | null> = {};
    if (value.bindings && typeof value.bindings === 'object') {
      for (const [id, binding] of Object.entries(value.bindings as Record<string, unknown>)) {
        if (!DEFINITIONS.has(id)) {
          continue;
        }
        if (binding === null) {
          bindings[id] = null;
        } else if (typeof binding === 'string') {
          const chords = parseBinding(binding);
          if (chords && !bindingProblem(binding)) {
            bindings[id] = chords.join(' ');
          }
        }
      }
    }
    return { bindings, singleKeyEnabled: value.singleKeyEnabled !== false };
  } catch {
    return DEFAULT_SETTINGS;
  }
}

/** A copy of `bindings` without the given ids. */
function without(bindings: Readonly<Record<string, string | null>>, ...ids: string[]): Record<string, string | null> {
  return Object.fromEntries(Object.entries(bindings).filter(([id]) => !ids.includes(id)));
}

let current: ShortcutSettings | null = null;
const listeners = new Set<() => void>();

function snapshot(): ShortcutSettings {
  current ??= readShortcutSettings();
  return current;
}

function update(next: ShortcutSettings): void {
  current = next;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  } catch {
    // storage unavailable: the change lasts for this visit only
  }
  listeners.forEach((listener) => {
    listener();
  });
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  // Another tab changed the shortcuts: pick them up here too.
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) {
      current = readShortcutSettings();
      listener();
    }
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

/** The binding in effect for a shortcut, or null when it is off. */
export function effectiveBinding(settings: ShortcutSettings, id: ShortcutId): string | null {
  return id in settings.bindings ? (settings.bindings[id] ?? null) : (DEFINITIONS.get(id)?.defaultBinding ?? null);
}

/** Whether a shortcut can fire under the current settings (it has keys and they aren't switched off). */
export function isActive(settings: ShortcutSettings, id: ShortcutId): boolean {
  const binding = effectiveBinding(settings, id);
  return binding !== null && (settings.singleKeyEnabled || !isSingleKeyBinding(binding));
}

export function useShortcutSettings() {
  const settings = useSyncExternalStore(subscribe, snapshot, () => DEFAULT_SETTINGS);
  return {
    settings,
    bindingFor: (id: ShortcutId) => effectiveBinding(settings, id),
    /** The binding to display, or null when the shortcut is off or switched off by the single-key setting. */
    activeBindingFor: (id: ShortcutId) => (isActive(settings, id) ? effectiveBinding(settings, id) : null),
    isCustomized: (id: ShortcutId) => id in settings.bindings,
    /** Sets a binding; any shortcut whose keys clash with it is turned off (the caller confirmed). */
    setBinding: (id: ShortcutId, binding: string | null) => {
      const normalized = binding === null ? null : (parseBinding(binding) ?? []).join(' ');
      const turnedOff = normalized
        ? SHORTCUTS.filter((other) => {
            const otherBinding = effectiveBinding(snapshot(), other.id);
            return other.id !== id && otherBinding !== null && bindingsConflict(otherBinding, normalized);
          }).map((other) => other.id)
        : [];
      const rest = without(snapshot().bindings, id);
      const bindings: Record<string, string | null> = {
        ...rest,
        ...Object.fromEntries(turnedOff.map((other) => [other, null])),
        // Choosing the default keys again simply drops the customization.
        ...(normalized === DEFINITIONS.get(id)?.defaultBinding ? {} : { [id]: normalized }),
      };
      update({ ...snapshot(), bindings });
    },
    resetBinding: (id: ShortcutId) => {
      // Restoring the default may clash with keys given to another shortcut meanwhile: that one goes back to its own default.
      const restored = DEFINITIONS.get(id)?.defaultBinding;
      const current = snapshot().bindings;
      const clashing = SHORTCUTS.filter((other) => {
        const otherBinding = current[other.id];
        return other.id !== id && restored !== undefined && typeof otherBinding === 'string' && bindingsConflict(otherBinding, restored);
      }).map((other) => other.id);
      update({ ...snapshot(), bindings: without(current, id, ...clashing) });
    },
    resetAll: () => {
      update({ ...DEFAULT_SETTINGS, singleKeyEnabled: snapshot().singleKeyEnabled });
    },
    setSingleKeyEnabled: (enabled: boolean) => {
      update({ ...snapshot(), singleKeyEnabled: enabled });
    },
  };
}

/** The shortcut (other than `exceptId`) whose keys clash with `binding`, if any. */
export function conflictingShortcut(settings: ShortcutSettings, binding: string, exceptId: ShortcutId): ShortcutDefinition | undefined {
  return SHORTCUTS.find((s) => {
    const other = effectiveBinding(settings, s.id);
    return s.id !== exceptId && other !== null && bindingsConflict(other, binding);
  });
}

/** Test hook: forget the cached settings so the next read comes from storage. */
export function resetShortcutStoreForTests(): void {
  current = null;
}

// --- Dispatch -------------------------------------------------------------------------------

/**
 * Where keys belong to the page, not to shortcuts: text entry, open dialogs and menus (their
 * own keys), and the shortcut recorder. Shortcuts with Ctrl/⌘/Alt still work while typing.
 */
function blockedFor(target: EventTarget | null, plain: boolean): boolean {
  if (!(target instanceof HTMLElement)) {
    return false;
  }
  if (target.closest('[data-shortcut-recorder], dialog[open]')) {
    return true;
  }
  if (!plain) {
    return false;
  }
  return (
    target.isContentEditable ||
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    target.closest('[role="menu"], [role="listbox"], [role="combobox"]') !== null
  );
}

export type ShortcutHandlers = Partial<Record<ShortcutId, () => void>>;

/**
 * Runs the handlers for the shortcuts the user presses. Only shortcuts with a handler take
 * part, so "org" shortcuts are inert outside an organization.
 */
export function useShortcuts(handlers: ShortcutHandlers): void {
  const { settings } = useShortcutSettings();
  const latest = useRef(handlers);
  useEffect(() => {
    latest.current = handlers;
  });

  useEffect(() => {
    const mac = isMacPlatform();
    let pending: string | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const clearPending = () => {
      pending = null;
      clearTimeout(timer);
    };

    const active = () =>
      SHORTCUTS.filter((s) => latest.current[s.id] && isActive(settings, s.id)).map((s) => ({
        id: s.id,
        chords: parseBinding(effectiveBinding(settings, s.id) ?? '') ?? [],
      }));

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.repeat) {
        return;
      }
      const chord = chordFromEvent(event, mac);
      if (!chord) {
        return;
      }
      if (blockedFor(event.target, isPlainChord(chord))) {
        clearPending();
        return;
      }
      const shortcuts = active();
      if (pending) {
        const first = pending;
        clearPending();
        const match = shortcuts.find((s) => s.chords.length === 2 && s.chords[0] === first && s.chords[1] === chord);
        if (match) {
          event.preventDefault();
          latest.current[match.id]?.();
          return;
        }
      }
      const single = shortcuts.find((s) => s.chords.length === 1 && s.chords[0] === chord);
      if (single) {
        event.preventDefault();
        latest.current[single.id]?.();
        return;
      }
      if (shortcuts.some((s) => s.chords.length === 2 && s.chords[0] === chord)) {
        pending = chord;
        timer = setTimeout(clearPending, SEQUENCE_TIMEOUT_MS);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      clearPending();
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [settings]);
}

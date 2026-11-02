import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ariaKeyShortcuts,
  bindingProblem,
  bindingsConflict,
  chordFromEvent,
  conflictingShortcut,
  formatBindingText,
  parseBinding,
  readShortcutSettings,
  resetShortcutStoreForTests,
  SEQUENCE_TIMEOUT_MS,
  useShortcuts,
  useShortcutSettings,
} from './shortcuts';

function key(init: KeyboardEventInit): KeyboardEvent {
  return new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
}

beforeEach(() => {
  window.localStorage.clear();
  resetShortcutStoreForTests();
});

describe('bindings', () => {
  it('parses chords and two-key sequences into a canonical form', () => {
    expect(parseBinding('mod+k')).toEqual(['mod+k']);
    expect(parseBinding('  g   i ')).toEqual(['g', 'i']);
    expect(parseBinding('shift+alt+mod+p')).toEqual(['mod+alt+shift+p']);
    expect(parseBinding('ctrl+k')).toBeNull();
    expect(parseBinding('g i x')).toBeNull();
    expect(parseBinding('mod+mod+k')).toBeNull();
  });

  it('turns key presses into chords the same way on every layout and platform', () => {
    expect(chordFromEvent(key({ key: 'K', ctrlKey: true, code: 'KeyK' }), false)).toBe('mod+k');
    expect(chordFromEvent(key({ key: 'k', metaKey: true, code: 'KeyK' }), true)).toBe('mod+k');
    // The other platform's command key is never "mod".
    expect(chordFromEvent(key({ key: 'k', metaKey: true }), false)).toBeNull();
    expect(chordFromEvent(key({ key: 'k', ctrlKey: true }), true)).toBeNull();
    // "?" already implies Shift; letters keep it.
    expect(chordFromEvent(key({ key: '?', shiftKey: true }), false)).toBe('?');
    expect(chordFromEvent(key({ key: 'C', shiftKey: true }), false)).toBe('shift+c');
    // macOS Alt+C types "ç": the physical key is used instead.
    expect(chordFromEvent(key({ key: 'ç', altKey: true, code: 'KeyC' }), true)).toBe('alt+c');
    expect(chordFromEvent(key({ key: 'Shift', shiftKey: true }), false)).toBeNull();
    expect(chordFromEvent(key({ key: 'a', isComposing: true }), false)).toBeNull();
  });

  it('refuses keys that belong to the browser, editing or moving around', () => {
    expect(bindingProblem('mod+c')).toMatch(/browser or text editing/);
    expect(bindingProblem('mod+w')).toMatch(/browser/);
    expect(bindingProblem('enter')).toMatch(/needs Ctrl/);
    expect(bindingProblem('g mod+i')).toMatch(/two-key sequence/);
    expect(bindingProblem('mod+shift+e')).toBeNull();
    expect(bindingProblem('g x')).toBeNull();
  });

  it('treats a binding that starts another as a clash', () => {
    expect(bindingsConflict('g', 'g i')).toBe(true);
    expect(bindingsConflict('g i', 'g i')).toBe(true);
    expect(bindingsConflict('g i', 'g n')).toBe(false);
    expect(bindingsConflict('mod+k', 'k')).toBe(false);
  });

  it('describes keys for people and assistive technology', () => {
    expect(formatBindingText('mod+shift+k', false)).toBe('Ctrl Shift K');
    expect(formatBindingText('mod+k', true)).toBe('⌘K');
    expect(formatBindingText('g i', false)).toBe('G then I');
    expect(ariaKeyShortcuts('mod+k', false)).toBe('Control+K');
    expect(ariaKeyShortcuts('mod+k', true)).toBe('Meta+K');
    expect(ariaKeyShortcuts('g i', false)).toBeUndefined();
  });
});

describe('settings', () => {
  it('gives keys to a new shortcut and turns off the one that had them', () => {
    const { result } = renderHook(() => useShortcutSettings());
    act(() => {
      result.current.setBinding('goInbox', 'c');
    });
    expect(result.current.bindingFor('goInbox')).toBe('c');
    expect(result.current.bindingFor('createIssue')).toBeNull();
    expect(readShortcutSettings().bindings).toEqual({ goInbox: 'c', createIssue: null });
  });

  it('drops the customization when the default keys are chosen again', () => {
    const { result } = renderHook(() => useShortcutSettings());
    act(() => {
      result.current.setBinding('createIssue', 'shift+n');
    });
    act(() => {
      result.current.setBinding('createIssue', 'c');
    });
    expect(result.current.isCustomized('createIssue')).toBe(false);
  });

  it('resets a shortcut, sending a clashing one back to its own default', () => {
    const { result } = renderHook(() => useShortcutSettings());
    act(() => {
      result.current.setBinding('goInbox', 'c');
    });
    act(() => {
      result.current.resetBinding('createIssue');
    });
    expect(result.current.bindingFor('createIssue')).toBe('c');
    expect(result.current.bindingFor('goInbox')).toBe('g n');
  });

  it('names the shortcut that already uses some keys', () => {
    const { result } = renderHook(() => useShortcutSettings());
    expect(conflictingShortcut(result.current.settings, 'g', 'createIssue')?.id).toBe('goMyWork');
    expect(conflictingShortcut(result.current.settings, 'g h', 'goMyWork')).toBeUndefined();
  });

  it('ignores unknown, invalid or reserved stored values', () => {
    window.localStorage.setItem(
      'jtrac.shortcuts',
      JSON.stringify({ bindings: { createIssue: 'mod+c', goInbox: 'g  x', nope: 'x', goIssues: 42, goProjects: null }, singleKeyEnabled: false }),
    );
    expect(readShortcutSettings()).toEqual({ bindings: { goInbox: 'g x', goProjects: null }, singleKeyEnabled: false });
    window.localStorage.setItem('jtrac.shortcuts', '{not json');
    expect(readShortcutSettings()).toEqual({ bindings: {}, singleKeyEnabled: true });
  });
});

describe('useShortcuts', () => {
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  function press(init: KeyboardEventInit, target: EventTarget = document.body): KeyboardEvent {
    const event = key(init);
    act(() => {
      target.dispatchEvent(event);
    });
    return event;
  }

  it('runs chords and sequences, and lets a sequence time out', () => {
    vi.useFakeTimers();
    const handlers = { createIssue: vi.fn(), goInbox: vi.fn(), commandPalette: vi.fn() };
    renderHook(() => {
      useShortcuts(handlers);
    });

    expect(press({ key: 'c' }).defaultPrevented).toBe(true);
    expect(handlers.createIssue).toHaveBeenCalledTimes(1);

    press({ key: 'g' });
    press({ key: 'n' });
    expect(handlers.goInbox).toHaveBeenCalledTimes(1);

    press({ key: 'g' });
    act(() => {
      vi.advanceTimersByTime(SEQUENCE_TIMEOUT_MS + 1);
    });
    press({ key: 'n' });
    expect(handlers.goInbox).toHaveBeenCalledTimes(1);
  });

  it('leaves typing alone but still answers Ctrl/⌘ shortcuts in a text field', () => {
    const handlers = { createIssue: vi.fn(), commandPalette: vi.fn() };
    renderHook(() => {
      useShortcuts(handlers);
    });
    const input = document.body.appendChild(document.createElement('input'));

    expect(press({ key: 'c' }, input).defaultPrevented).toBe(false);
    expect(handlers.createIssue).not.toHaveBeenCalled();

    press({ key: 'k', ctrlKey: true, code: 'KeyK' }, input);
    expect(handlers.commandPalette).toHaveBeenCalledTimes(1);
  });

  it('stays out of open dialogs', () => {
    const handlers = { commandPalette: vi.fn() };
    renderHook(() => {
      useShortcuts(handlers);
    });
    const dialog = document.body.appendChild(document.createElement('dialog'));
    dialog.setAttribute('open', '');
    const button = dialog.appendChild(document.createElement('button'));
    press({ key: 'k', ctrlKey: true, code: 'KeyK' }, button);
    expect(handlers.commandPalette).not.toHaveBeenCalled();
  });

  it('switches single-key shortcuts off while keeping modifier shortcuts', () => {
    const settings = renderHook(() => useShortcutSettings());
    act(() => {
      settings.result.current.setSingleKeyEnabled(false);
    });
    const handlers = { createIssue: vi.fn(), commandPalette: vi.fn() };
    renderHook(() => {
      useShortcuts(handlers);
    });
    press({ key: 'c' });
    press({ key: 'k', ctrlKey: true, code: 'KeyK' });
    expect(handlers.createIssue).not.toHaveBeenCalled();
    expect(handlers.commandPalette).toHaveBeenCalledTimes(1);
  });

  it('follows a changed binding without remounting', () => {
    const settings = renderHook(() => useShortcutSettings());
    const handlers = { createIssue: vi.fn() };
    renderHook(() => {
      useShortcuts(handlers);
    });
    act(() => {
      settings.result.current.setBinding('createIssue', 'shift+n');
    });
    press({ key: 'c' });
    press({ key: 'N', shiftKey: true });
    expect(handlers.createIssue).toHaveBeenCalledTimes(1);
  });
});

import { describe, expect, it } from 'vitest';
import { greetingFor } from '../features/home/HomePage';
import { initialsOf } from '../ui/Avatar';
import { resolveTheme } from './theme';

describe('resolveTheme', () => {
  it('follows the system preference when set to system', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });

  it('honours an explicit choice regardless of the system', () => {
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
});

describe('initialsOf', () => {
  it.each([
    ['Ada Lovelace', 'AL'],
    ['grace', 'G'],
    ['  Jean  Paul  Sartre ', 'JS'],
    ['', '?'],
  ])('%s → %s', (name, expected) => {
    expect(initialsOf(name)).toBe(expected);
  });
});

describe('greetingFor', () => {
  it.each([
    [8, 'Good morning'],
    [13, 'Good afternoon'],
    [20, 'Good evening'],
  ])('at %i:00 says %s', (hour, expected) => {
    expect(greetingFor(new Date(2026, 9, 24, hour))).toBe(expected);
  });
});

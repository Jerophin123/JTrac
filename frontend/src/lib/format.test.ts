import { describe, expect, it } from 'vitest';
import { formatBytes, isOverdue, pluralize, timeAgo, todayIso } from './format';

describe('format', () => {
  const now = new Date('2026-10-24T12:00:00Z').getTime();

  it('describes recent times relatively', () => {
    expect(timeAgo('2026-10-24T11:59:30Z', now)).toBe('just now');
    expect(timeAgo('2026-10-24T11:55:00Z', now)).toMatch(/5 minutes ago/);
    expect(timeAgo('2026-10-21T12:00:00Z', now)).toMatch(/3 days ago/);
    expect(timeAgo(null, now)).toBe('');
  });

  it('formats sizes', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(25 * 1024 * 1024)).toBe('25 MB');
  });

  it('detects overdue dates only for unresolved issues', () => {
    const today = new Date('2026-10-24T09:00:00');
    expect(todayIso(today)).toBe('2026-10-24');
    expect(isOverdue('2026-10-23', false, today)).toBe(true);
    expect(isOverdue('2026-10-24', false, today)).toBe(false);
    expect(isOverdue('2026-10-23', true, today)).toBe(false);
    expect(isOverdue(null, false, today)).toBe(false);
  });

  it('pluralizes', () => {
    expect(pluralize(1, 'issue')).toBe('1 issue');
    expect(pluralize(3, 'issue')).toBe('3 issues');
  });
});

import { describe, expect, it } from 'vitest';
import { contrastRatio, DARK_INK, LIGHT_INK, readableTextOn, relativeLuminance } from './contrast';

describe('contrast', () => {
  it('computes WCAG luminance and ratios', () => {
    expect(relativeLuminance('#ffffff')).toBeCloseTo(1, 5);
    expect(relativeLuminance('#000')).toBeCloseTo(0, 5);
    expect(contrastRatio('#ffffff', '#000000')).toBeCloseTo(21, 5);
    expect(relativeLuminance('var(--color-accent)')).toBeNull();
  });

  it('picks the ink with more contrast', () => {
    expect(readableTextOn('#b4532a')).toBe(LIGHT_INK);
    expect(readableTextOn('#334155')).toBe(LIGHT_INK);
    expect(readableTextOn('#facc15')).toBe(DARK_INK);
    expect(readableTextOn('#e2e8f0')).toBe(DARK_INK);
    expect(readableTextOn('not a color')).toBeUndefined();
    expect(readableTextOn(null)).toBeUndefined();
  });

  it('always reaches at least 4.5:1 for opaque colors', () => {
    for (let v = 0; v <= 0xffffff; v += 0x0f0f0f) {
      const color = `#${v.toString(16).padStart(6, '0')}`;
      const ink = readableTextOn(color);
      expect(ink).toBeDefined();
      expect(contrastRatio(color, ink ?? '')).toBeGreaterThanOrEqual(4.5);
    }
  });
});

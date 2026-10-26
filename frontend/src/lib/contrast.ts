/** WCAG 2 relative luminance of a `#rgb` or `#rrggbb` color, or null if it cannot be parsed. */
export function relativeLuminance(color: string): number | null {
  const hex = color.trim().replace(/^#/, '');
  const full = hex.length === 3 ? hex.replace(/./g, (c) => c + c) : hex;
  if (!/^[0-9a-f]{6}$/i.test(full)) {
    return null;
  }
  const [r, g, b] = [0, 2, 4].map((i) => {
    const channel = parseInt(full.slice(i, i + 2), 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrastRatio(a: string, b: string): number | null {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  if (la === null || lb === null) {
    return null;
  }
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

export const LIGHT_INK = '#ffffff';
export const DARK_INK = '#000000';

/**
 * The text color (white or near-black) with the higher contrast on `background`.
 * Returns undefined for colors it cannot parse, so callers fall back to the theme token.
 */
export function readableTextOn(background: string | null | undefined): string | undefined {
  if (!background) {
    return undefined;
  }
  const light = contrastRatio(background, LIGHT_INK);
  const dark = contrastRatio(background, DARK_INK);
  if (light === null || dark === null) {
    return undefined;
  }
  return light >= dark ? LIGHT_INK : DARK_INK;
}

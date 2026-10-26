/**
 * Helper to convert 3-digit or 6-digit hex color to RGB tuple.
 */
function hexToRgb(hex: string): [number, number, number] | null {
  const clean = hex.trim().replace(/^#/, '');
  if (clean.length === 3) {
    const c0 = clean[0] ?? '0';
    const c1 = clean[1] ?? '0';
    const c2 = clean[2] ?? '0';
    const r = parseInt(c0 + c0, 16);
    const g = parseInt(c1 + c1, 16);
    const b = parseInt(c2 + c2, 16);
    return isNaN(r) || isNaN(g) || isNaN(b) ? null : [r, g, b];
  }
  if (clean.length === 6) {
    const r = parseInt(clean.slice(0, 2), 16);
    const g = parseInt(clean.slice(2, 4), 16);
    const b = parseInt(clean.slice(4, 6), 16);
    return isNaN(r) || isNaN(g) || isNaN(b) ? null : [r, g, b];
  }
  return null;
}

/**
 * Calculates saturation (0 to 1) and lightness (0 to 255) for an RGB color.
 */
function getSaturationAndLightness(r: number, g: number, b: number): { sat: number; lightness: number } {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const delta = max - min;
  const lightness = (max + min) / 2;
  const sat = delta === 0 ? 0 : delta / (lightness > 127 ? 510 - max - min : max + min);
  return { sat, lightness };
}

/**
 * Extracts color candidates directly from an SVG data URI or SVG string.
 */
function extractFromSvgString(svg: string): string | null {
  const matches = svg.match(/#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})\b/g);
  if (!matches || matches.length === 0) return null;

  const scored = matches
    .map((hex) => {
      const rgb = hexToRgb(hex);
      if (!rgb) return { hex, score: -1 };
      const { sat, lightness } = getSaturationAndLightness(rgb[0], rgb[1], rgb[2]);
      const isMonochrome = lightness < 30 || lightness > 245;
      const score = isMonochrome ? sat * 0.1 : sat * (lightness >= 40 && lightness <= 210 ? 2 : 1);
      return { hex: hex.toLowerCase(), score };
    })
    .sort((a, b) => b.score - a.score);

  return scored[0] && scored[0].score > 0.05 ? scored[0].hex : null;
}

/**
 * Extracts the most prominent chromatic brand color from an image URL or data URI.
 * Works seamlessly with PNG, JPEG, SVG data URIs, WebP, and external image URLs.
 */
export async function extractColorFromImage(source: string): Promise<string | null> {
  if (!source) return null;

  // Fast-path for data:image/svg+xml: analyze SVG fills/strokes directly
  if (source.startsWith('data:image/svg+xml')) {
    try {
      let svgText = '';
      if (source.includes(';base64,')) {
        const b64 = source.split(';base64,')[1];
        if (b64) {
          svgText = atob(b64);
        }
      } else {
        const commaIdx = source.indexOf(',');
        if (commaIdx !== -1) {
          svgText = decodeURIComponent(source.slice(commaIdx + 1));
        }
      }
      if (svgText) {
        const svgColor = extractFromSvgString(svgText);
        if (svgColor) {
          return svgColor;
        }
      }
    } catch {
      // Fallback to canvas rasterization below
    }
  }

  // Universal canvas-based extraction for raster images and rendered SVGs
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';

    img.onload = () => {
      try {
        const canvas = document.createElement('canvas');
        const size = 64;
        canvas.width = size;
        canvas.height = size;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          resolve(null);
          return;
        }

        ctx.drawImage(img, 0, 0, size, size);
        const { data } = ctx.getImageData(0, 0, size, size);

        // Quantize colors into 16x16x16 RGB buckets
        const buckets: Record<number, { r: number; g: number; b: number; count: number; score: number }> = {};

        for (let i = 0; i < data.length; i += 4) {
          const a = data[i + 3];
          if (a === undefined || a < 128) continue;

          const r = data[i] ?? 0;
          const g = data[i + 1] ?? 0;
          const b = data[i + 2] ?? 0;

          const { sat, lightness } = getSaturationAndLightness(r, g, b);

          // Disregard near-black and near-white pixels
          if (lightness < 30 || lightness > 245) continue;

          // Strongly favor saturated brand hues over dull neutrals
          const weight = 1 + sat * sat * 12;

          const qr = Math.floor(r / 16);
          const qg = Math.floor(g / 16);
          const qb = Math.floor(b / 16);
          const key = (qr << 8) | (qg << 4) | qb;

          const bucket = buckets[key] ?? { r: 0, g: 0, b: 0, count: 0, score: 0 };
          bucket.r += r;
          bucket.g += g;
          bucket.b += b;
          bucket.count += 1;
          bucket.score += weight;
          buckets[key] = bucket;
        }

        let bestBucket: { r: number; g: number; b: number; count: number } | null = null;
        let maxScore = -1;

        for (const bucket of Object.values(buckets)) {
          if (bucket.score > maxScore) {
            maxScore = bucket.score;
            bestBucket = bucket;
          }
        }

        if (!bestBucket || bestBucket.count === 0) {
          resolve(null);
          return;
        }

        const avgR = Math.round(bestBucket.r / bestBucket.count);
        const avgG = Math.round(bestBucket.g / bestBucket.count);
        const avgB = Math.round(bestBucket.b / bestBucket.count);

        const hex = `#${[avgR, avgG, avgB].map((x) => x.toString(16).padStart(2, '0')).join('')}`;
        resolve(hex);
      } catch {
        resolve(null);
      }
    };

    img.onerror = () => {
      resolve(null);
    };

    img.src = source;
  });
}

/**
 * Generates custom CSS rules dynamically deriving the entire base background,
 * surfaces, borders, and accent tokens from the chosen brand color.
 */
export function generateOrgThemeCss(color: string): string {
  const validHex = color.trim().toLowerCase();
  return `
:root {
  --color-accent: ${validHex};
  --color-accent-hover: color-mix(in srgb, ${validHex} 85%, black);
  --color-accent-soft: color-mix(in srgb, ${validHex} 15%, #ffffff);
  --color-accent-text: color-mix(in srgb, ${validHex} 85%, black);
  --color-on-accent: #ffffff;
  --color-focus: ${validHex};
  --color-canvas: color-mix(in srgb, ${validHex} 4%, #f8fafc);
  --color-surface-muted: color-mix(in srgb, ${validHex} 5%, #f1f5f9);
  --color-surface-sunken: color-mix(in srgb, ${validHex} 8%, #e2e8f0);
  --color-surface-hover: color-mix(in srgb, ${validHex} 9%, #edf2f7);
  --color-border: color-mix(in srgb, ${validHex} 14%, #cbd5e1);
  --color-border-strong: color-mix(in srgb, ${validHex} 22%, #94a3b8);
}

:root[data-theme='dark'] {
  --color-accent: ${validHex};
  --color-accent-hover: color-mix(in srgb, ${validHex} 85%, white);
  --color-accent-soft: color-mix(in srgb, ${validHex} 22%, #000000);
  --color-accent-text: color-mix(in srgb, ${validHex} 75%, white);
  --color-on-accent: #ffffff;
  --color-focus: ${validHex};
  --color-canvas: color-mix(in srgb, ${validHex} 6%, #0b0d12);
  --color-surface: color-mix(in srgb, ${validHex} 8%, #11141c);
  --color-surface-muted: color-mix(in srgb, ${validHex} 12%, #161b25);
  --color-surface-sunken: color-mix(in srgb, ${validHex} 4%, #080a0d);
  --color-surface-hover: color-mix(in srgb, ${validHex} 15%, #1b212e);
  --color-border: color-mix(in srgb, ${validHex} 18%, #222938);
  --color-border-strong: color-mix(in srgb, ${validHex} 26%, #303b50);
}
`;
}


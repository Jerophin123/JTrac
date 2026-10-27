import { ImageResponse } from 'next/og';
import { mark } from './logo-paths';

/**
 * Square icon: the official mark on the product's dark canvas, edge to edge so it fills the
 * round crop search results and home screens apply. Rendered once at build time.
 */
export function markIcon(size: number) {
  const width = Math.round(size * 0.73);
  return new ImageResponse(
    (
      <div style={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#110f0d' }}>
        <svg width={width} height={Math.round((width * 8500) / 12540)} viewBox={mark.viewBox}>
          <path d={mark.base} fill="#ffffff" />
          <path d={mark.accent} fill="#fc6f08" />
        </svg>
      </div>
    ),
    { width: size, height: size },
  );
}

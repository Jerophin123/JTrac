import { ImageResponse } from 'next/og';
import { mark } from '@/lib/logo-paths';

export const dynamic = 'force-static';

/** 180x180 home-screen icon: the official mark on the product's dark canvas. */
export function GET() {
  return new ImageResponse(
    (
      <div style={{ width: '100%', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#110f0d' }}>
        <svg width={132} height={90} viewBox={mark.viewBox}>
          <path d={mark.base} fill="#ffffff" />
          <path d={mark.accent} fill="#fc6f08" />
        </svg>
      </div>
    ),
    { width: 180, height: 180 },
  );
}

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { ImageResponse } from 'next/og';
import { latestRelease } from '@/lib/site';
import { seo } from '@/lib/seo';
import { wordmark } from '@/lib/logo-paths';

export const dynamic = 'force-static';

/** 1200x630 share image (Open Graph / X), rendered once at build time. */
export async function GET() {
  // Satori reads TTF/OTF/WOFF only, so the static IBM Plex files are used (the page uses the variable WOFF2).
  const font = (pkg: string, file: string) => readFile(join(process.cwd(), 'node_modules/@fontsource', pkg, 'files', file));
  const [sans, mono] = await Promise.all([
    font('ibm-plex-sans', 'ibm-plex-sans-latin-700-normal.woff'),
    font('ibm-plex-mono', 'ibm-plex-mono-latin-500-normal.woff'),
  ]);
  const accent = '#fc6f08';
  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          padding: '72px 80px',
          background: 'radial-gradient(90% 80% at 50% 0%, #2a1a0f 0%, #110f0d 60%)',
          color: '#eee8e1',
          fontFamily: 'Plex Sans',
        }}
      >
        <svg width={300} height={70} viewBox={wordmark.viewBox}>
          {wordmark.base.map((d) => (
            <path key={d.slice(0, 24)} d={d} fill="#eee8e1" />
          ))}
          {wordmark.accent.map((d) => (
            <path key={d.slice(0, 24)} d={d} fill={accent} />
          ))}
        </svg>
        <div style={{ display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', gap: 10, marginBottom: 28 }}>
            <div style={{ display: 'flex', width: 64, height: 7, background: accent, transform: 'skewX(-12deg)' }} />
            <div style={{ display: 'flex', width: 64, height: 7, background: 'rgba(252,111,8,0.4)', transform: 'skewX(-12deg)' }} />
          </div>
          <div style={{ display: 'flex', fontSize: 84, fontWeight: 700, letterSpacing: '-0.04em', lineHeight: 1.04 }}>Project work.</div>
          <div style={{ display: 'flex', fontSize: 84, fontWeight: 700, letterSpacing: '-0.04em', lineHeight: 1.04 }}>
            Finally in&nbsp;<span style={{ color: accent }}>flow</span>.
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', fontFamily: 'Plex Mono', fontSize: 26, color: '#aaa196' }}>
          <span>Open-source, self-hosted issue tracking</span>
          <span style={{ color: accent }}>v{latestRelease.version} · Apache-2.0</span>
        </div>
      </div>
    ),
    {
      width: seo.ogImage.width,
      height: seo.ogImage.height,
      fonts: [
        { name: 'Plex Sans', data: sans, weight: 700, style: 'normal' },
        { name: 'Plex Mono', data: mono, weight: 500, style: 'normal' },
      ],
    },
  );
}

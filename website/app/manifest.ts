import type { MetadataRoute } from 'next';
import { faviconSizes, seo } from '@/lib/seo';

export const dynamic = 'force-static';

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: seo.title,
    short_name: seo.name,
    description: seo.description,
    start_url: '.',
    display: 'browser',
    background_color: '#110f0d',
    theme_color: '#110f0d',
    icons: [
      { src: 'icon.svg', type: 'image/svg+xml', sizes: 'any' },
      ...faviconSizes.map((s) => ({ src: `icons/icon-${String(s)}.png`, type: 'image/png', sizes: `${String(s)}x${String(s)}` })),
      { src: 'apple-icon.png', type: 'image/png', sizes: '180x180' },
    ],
  };
}

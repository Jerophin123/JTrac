import type { MetadataRoute } from 'next';
import { absolute } from '@/lib/seo';

export const dynamic = 'force-static';

/** Allow every crawler; point to the sitemap once the site address is set. */
export default function robots(): MetadataRoute.Robots {
  const sitemap = absolute('sitemap.xml');
  return {
    rules: [{ userAgent: '*', allow: '/' }],
    ...(sitemap ? { sitemap } : {}),
  };
}

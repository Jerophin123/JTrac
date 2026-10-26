import type { MetadataRoute } from 'next';
import { absolute } from '@/lib/seo';

export const dynamic = 'force-static';

/** One page. Empty until the site address is set: sitemaps require absolute URLs. */
export default function sitemap(): MetadataRoute.Sitemap {
  const home = absolute();
  return home ? [{ url: home, changeFrequency: 'monthly', priority: 1 }] : [];
}

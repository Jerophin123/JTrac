import type { MetadataRoute } from 'next';
import { absolute } from '@/lib/seo';

export const dynamic = 'force-static';

/** One page. Empty until the site address is set: sitemaps require absolute URLs. */
export default function sitemap(): MetadataRoute.Sitemap {
  const home = absolute();
  // lastmod is the build date: every deploy rebuilds the one page it lists.
  return home ? [{ url: home, lastModified: new Date(), changeFrequency: 'monthly', priority: 1 }] : [];
}

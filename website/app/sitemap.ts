import type { MetadataRoute } from 'next';
import { pages } from '@/lib/pages';
import { absolute, seo } from '@/lib/seo';

export const dynamic = 'force-static';

/** The home page and every page in lib/pages.ts. Empty until the site address is set: sitemaps require absolute URLs. */
export default function sitemap(): MetadataRoute.Sitemap {
  // lastmod is the build date: every deploy rebuilds every page it lists.
  const lastModified = new Date();
  // Image sitemap entry: the share image, so it can be found in image search.
  const image = absolute(seo.ogImage.path);
  const entries = [
    { path: '', priority: 1 },
    ...pages.map((p) => ({ path: p.path, priority: p.path === 'download/' ? 0.9 : 0.8 })),
  ];
  return entries.flatMap(({ path, priority }) => {
    const url = absolute(path);
    return url ? [{ url, lastModified, changeFrequency: 'monthly' as const, priority, ...(image ? { images: [image] } : {}) }] : [];
  });
}

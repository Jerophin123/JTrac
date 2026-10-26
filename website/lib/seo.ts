import type { Metadata } from 'next';
import { downloads, latestRelease, links, siteUrl } from './site';

/*
 * Search and sharing metadata. Wording reuses what the page already says, so search
 * snippets match the visible content (search engines penalize mismatches).
 */

export const seo = {
  name: 'JTRAC',
  /** 50-60 characters so it is not truncated in results. */
  title: 'JTRAC: Free, Open-Source Self-Hosted Issue Tracker',
  /** 120-160 characters. */
  description:
    'JTRAC is a free, open-source issue tracker you host yourself: configurable workflows, custom fields, boards, timelines and a REST API. Apache License 2.0.',
  author: 'Jerophin D R',
  keywords: [
    'issue tracker',
    'open source issue tracker',
    'self-hosted issue tracker',
    'project management',
    'bug tracker',
    'workflow',
    'custom fields',
    'kanban board',
    'JTrac',
  ],
  ogImage: { path: 'og-image.png', width: 1200, height: 630, alt: 'JTRAC: open-source, self-hosted issue tracking' },
} as const;

/** Absolute URL on this site, or null while the site address is not decided. */
export function absolute(path = ''): string | null {
  return siteUrl ? new URL(path, siteUrl).toString() : null;
}

export function buildMetadata(): Metadata {
  const ogImage = absolute(seo.ogImage.path);
  return {
    ...(siteUrl ? { metadataBase: new URL(siteUrl), alternates: { canonical: '/' } } : {}),
    title: seo.title,
    description: seo.description,
    applicationName: seo.name,
    authors: [{ name: seo.author, url: links.repo }],
    creator: seo.author,
    publisher: seo.author,
    keywords: [...seo.keywords],
    category: 'technology',
    // Declaring icons replaces Next's automatic app/icon.svg link, so both are listed here.
    icons: {
      icon: [{ url: '/icon.svg', type: 'image/svg+xml' }],
      apple: [{ url: '/apple-icon.png', sizes: '180x180', type: 'image/png' }],
    },
    robots: {
      index: true,
      follow: true,
      googleBot: { index: true, follow: true, 'max-image-preview': 'large', 'max-snippet': -1, 'max-video-preview': -1 },
    },
    formatDetection: { telephone: false, email: false, address: false },
    openGraph: {
      type: 'website',
      siteName: seo.name,
      title: seo.title,
      description: seo.description,
      locale: 'en_US',
      ...(siteUrl ? { url: siteUrl } : {}),
      ...(ogImage ? { images: [{ url: ogImage, width: seo.ogImage.width, height: seo.ogImage.height, alt: seo.ogImage.alt }] } : {}),
    },
    twitter: {
      card: 'summary_large_image',
      title: seo.title,
      description: seo.description,
      ...(ogImage ? { images: [{ url: ogImage, alt: seo.ogImage.alt }] } : {}),
    },
  };
}

/** schema.org structured data: the product, its source code and the visible FAQ. */
export function structuredData(faq: { q: string; a: string }[]) {
  const url = absolute();
  const app = {
    '@type': 'SoftwareApplication',
    '@id': url ? `${url}#software` : undefined,
    name: seo.name,
    description: seo.description,
    applicationCategory: 'BusinessApplication',
    applicationSubCategory: 'Issue tracker',
    operatingSystem: 'Windows, Linux, macOS',
    softwareVersion: latestRelease.version,
    releaseNotes: latestRelease.url,
    downloadUrl: downloads.map((d) => d.url),
    license: 'https://www.apache.org/licenses/LICENSE-2.0',
    isAccessibleForFree: true,
    // Free software; stating the price lets search engines show it as free.
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
    author: { '@type': 'Person', name: seo.author, url: 'https://github.com/Jerophin123' },
    url: url ?? undefined,
    sameAs: [links.repo],
  };
  const code = {
    '@type': 'SoftwareSourceCode',
    name: `${seo.name} source code`,
    codeRepository: links.repo,
    programmingLanguage: ['Java', 'TypeScript'],
    runtimePlatform: 'Java 25',
    license: 'https://www.apache.org/licenses/LICENSE-2.0',
    targetProduct: url ? { '@id': `${url}#software` } : { '@type': 'SoftwareApplication', name: seo.name },
  };
  const faqPage = {
    '@type': 'FAQPage',
    mainEntity: faq.map(({ q, a }) => ({ '@type': 'Question', name: q, acceptedAnswer: { '@type': 'Answer', text: a } })),
  };
  return { '@context': 'https://schema.org', '@graph': [app, code, faqPage] };
}

/** JSON for a <script type="application/ld+json">, with "<" escaped so it cannot close the tag. */
export function jsonLd(data: unknown): string {
  return JSON.stringify(data).replace(/</g, '\\u003c');
}

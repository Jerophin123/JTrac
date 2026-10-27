import type { Metadata } from 'next';
import type { Page } from './pages';
import { downloads, latestRelease, links, siteUrl } from './site';

/*
 * Search and sharing metadata. Wording reuses what the page already says, so search
 * snippets match the visible content (search engines penalize mismatches).
 */

export const seo = {
  name: 'Jtrac',
  /** 50-60 characters so it is not truncated in results. */
  title: 'Jtrac: Free, Open-Source Self-Hosted Issue Tracker',
  /** 120-160 characters. */
  description:
    'Jtrac is a free, open-source issue tracker you host yourself: configurable workflows, custom fields, boards, timelines and a REST API. Apache License 2.0.',
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
  ogImage: { path: 'og-image.png', width: 1200, height: 630, alt: 'Jtrac: open-source, self-hosted issue tracking' },
} as const;

/** PNG favicon sizes (app/icons/[file]); multiples of 48 for Google Search. */
export const faviconSizes = [48, 96, 192, 512] as const;

/** Absolute URL on this site, or null while the site address is not decided. */
export function absolute(path = ''): string | null {
  return siteUrl ? new URL(path, siteUrl).toString() : null;
}

/** Metadata for the home page, or for one of the subpages in lib/pages.ts. */
export function buildMetadata(page?: Page): Metadata {
  const ogImage = absolute(seo.ogImage.path);
  const path = `/${page?.path ?? ''}`;
  const url = absolute(page?.path);
  const title = page?.title ?? seo.title;
  const description = page?.description ?? seo.description;
  return {
    ...(siteUrl ? { metadataBase: new URL(siteUrl), alternates: { canonical: path } } : {}),
    title,
    description,
    applicationName: seo.name,
    authors: [{ name: seo.author, url: links.repo }],
    creator: seo.author,
    publisher: seo.author,
    keywords: [...(page?.keywords ?? []), ...seo.keywords],
    category: 'technology',
    // Declaring icons replaces Next's automatic links, so all are listed here. Google Search
    // wants a square icon in a multiple of 48px and also probes /favicon.ico, so raster
    // versions come first; the SVG (light/dark aware) serves browsers that prefer it.
    icons: {
      icon: [
        { url: '/favicon.ico', sizes: '48x48', type: 'image/x-icon' },
        ...faviconSizes.map((s) => ({ url: `/icons/icon-${String(s)}.png`, sizes: `${String(s)}x${String(s)}`, type: 'image/png' })),
        { url: '/icon.svg', type: 'image/svg+xml' },
      ],
      shortcut: '/favicon.ico',
      apple: [{ url: '/apple-icon.png', sizes: '180x180', type: 'image/png' }],
    },
    robots: {
      index: true,
      follow: true,
      googleBot: { index: true, follow: true, 'max-image-preview': 'large', 'max-snippet': -1, 'max-video-preview': -1 },
    },
    appleWebApp: { title: seo.name },
    formatDetection: { telephone: false, email: false, address: false },
    openGraph: {
      type: 'website',
      siteName: seo.name,
      title,
      description,
      locale: 'en_US',
      ...(url ? { url } : {}),
      ...(ogImage ? { images: [{ url: ogImage, width: seo.ogImage.width, height: seo.ogImage.height, alt: seo.ogImage.alt }] } : {}),
    },
    twitter: {
      card: 'summary_large_image',
      title,
      description,
      ...(ogImage ? { images: [{ url: ogImage, alt: seo.ogImage.alt }] } : {}),
    },
  };
}

// Features the page documents (README "Features"), for SoftwareApplication.featureList.
const featureList = [
  'Readable issue keys such as WEB-42',
  'Visual workflow editor with server-enforced transitions',
  'Per-project custom fields',
  'Kanban boards, filtered lists and timelines',
  'Saved views and global search',
  'Comments with @mentions, attachments and notifications',
  'REST API with personal access tokens and OpenAPI 3',
  'Signed webhooks (HMAC-SHA256)',
  'Self-hosted on Windows, Linux and macOS',
];

/** Stable node ids, so pages reference one WebSite, publisher and product instead of repeating them. */
const nodeId = (fragment: string) => {
  const home = absolute();
  return home ? `${home}#${fragment}` : undefined;
};

/** Nodes shared by every page: the site (its name is what Google shows as the site name), the publisher and the product. */
function siteNodes() {
  const url = absolute() ?? undefined;
  const logo = absolute('icons/icon-512.png') ?? undefined;
  const image = absolute(seo.ogImage.path) ?? undefined;
  const website = {
    '@type': 'WebSite',
    '@id': nodeId('website'),
    url,
    name: seo.name,
    alternateName: ['Jtrac Issue Tracker', 'jtrac.site'],
    description: seo.description,
    inLanguage: 'en',
    publisher: { '@id': nodeId('organization') },
  };
  const organization = {
    '@type': 'Organization',
    '@id': nodeId('organization'),
    name: seo.name,
    url,
    logo: logo ? { '@type': 'ImageObject', url: logo, width: 512, height: 512 } : undefined,
    founder: { '@id': nodeId('author') },
    sameAs: [links.repo],
  };
  const author = { '@type': 'Person', '@id': nodeId('author'), name: seo.author, url: 'https://github.com/Jerophin123' };
  const app = {
    '@type': 'SoftwareApplication',
    '@id': nodeId('software'),
    name: seo.name,
    alternateName: 'JTrac',
    description: seo.description,
    applicationCategory: 'BusinessApplication',
    applicationSubCategory: 'Issue tracker',
    operatingSystem: 'Windows, Linux, macOS',
    softwareVersion: latestRelease.version,
    releaseNotes: latestRelease.url,
    downloadUrl: downloads.map((d) => d.url),
    installUrl: absolute('download/') ?? undefined,
    softwareRequirements: 'None: Java 25 runtime and PostgreSQL 18 are bundled',
    featureList,
    image,
    license: 'https://www.apache.org/licenses/LICENSE-2.0',
    isAccessibleForFree: true,
    // Free software; stating the price lets search engines show it as free.
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD', availability: 'https://schema.org/InStock' },
    author: { '@id': nodeId('author') },
    publisher: { '@id': nodeId('organization') },
    url,
    sameAs: [links.repo],
  };
  const code = {
    '@type': 'SoftwareSourceCode',
    name: `${seo.name} source code`,
    codeRepository: links.repo,
    programmingLanguage: ['Java', 'TypeScript'],
    runtimePlatform: 'Java 25',
    license: 'https://www.apache.org/licenses/LICENSE-2.0',
    targetProduct: url ? { '@id': nodeId('software') } : { '@type': 'SoftwareApplication', name: seo.name },
  };
  return [website, organization, author, app, code];
}

/** WebPage node for a path ('' is the home page). */
function webPage(path: string, name: string, description: string, extra: Record<string, unknown> = {}) {
  const url = absolute(path) ?? undefined;
  return {
    '@type': 'WebPage',
    '@id': url ? `${url}#webpage` : undefined,
    url,
    name,
    description,
    inLanguage: 'en',
    isPartOf: { '@id': nodeId('website') },
    about: { '@id': nodeId('software') },
    primaryImageOfPage: absolute(seo.ogImage.path) ?? undefined,
    ...extra,
  };
}

/** schema.org structured data for the home page: the site, product, source code and the visible FAQ. */
export function structuredData(faq: { q: string; a: string }[]) {
  const faqPage = {
    '@type': 'FAQPage',
    mainEntity: faq.map(({ q, a }) => ({ '@type': 'Question', name: q, acceptedAnswer: { '@type': 'Answer', text: a } })),
  };
  return { '@context': 'https://schema.org', '@graph': [...siteNodes(), webPage('', seo.title, seo.description), faqPage] };
}

/** schema.org structured data for a subpage: the page, its breadcrumb trail, and the shared site nodes. */
export function pageStructuredData(page: Page) {
  const url = absolute(page.path) ?? undefined;
  const breadcrumb = {
    '@type': 'BreadcrumbList',
    '@id': url ? `${url}#breadcrumb` : undefined,
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: absolute() ?? undefined },
      { '@type': 'ListItem', position: 2, name: page.label, item: url },
    ],
  };
  return {
    '@context': 'https://schema.org',
    '@graph': [...siteNodes(), webPage(page.path, page.title, page.description, { breadcrumb: { '@id': breadcrumb['@id'] } }), breadcrumb],
  };
}

/** JSON for a <script type="application/ld+json">, with "<" escaped so it cannot close the tag. */
export function jsonLd(data: unknown): string {
  return JSON.stringify(data).replace(/</g, '\\u003c');
}

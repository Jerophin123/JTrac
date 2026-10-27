import { latestRelease } from './site';

/**
 * The site's indexable pages. Each one is a real URL with its own title, description and
 * canonical address, so search engines can list them separately (in-page #anchors are not
 * pages to a crawler). The sitemap, footer and per-page metadata all read this list.
 */
export interface Page {
  /** Path with a trailing slash, matching `trailingSlash: true`. */
  path: string;
  /** Short label for navigation and the footer. */
  label: string;
  /** 30-60 characters so it is not truncated in results. */
  title: string;
  /** 70-160 characters. */
  description: string;
  /** Search phrases this page answers, added to the site-wide keywords. */
  keywords: string[];
  /** The page's visible h1 and introduction. */
  heading: string;
  lede: string;
}

export const pages: Page[] = [
  {
    path: 'features/',
    label: 'Features',
    title: 'Jtrac Features: Workflows, Custom Fields and Boards',
    description:
      'Readable issue keys, visual workflows enforced on the server, per-project custom fields, boards, timelines, saved views, @mentions and a REST API.',
    keywords: ['issue tracker features', 'workflow editor', 'custom fields', 'kanban board', 'issue timeline', 'REST API', 'webhooks'],
    heading: 'Everything an issue tracker should do.',
    lede: 'The classic JTrac model of projects, workflows and custom fields, rebuilt with a fast interface and rules the server actually enforces.',
  },
  {
    path: 'download/',
    label: 'Download',
    title: 'Download Jtrac for Windows, Linux and macOS',
    description:
      `Direct downloads of Jtrac ${latestRelease.version} for Windows x64, Linux x64 and ARM64, and macOS. Java and PostgreSQL are bundled; every package has a SHA-256 checksum.`,
    keywords: ['download issue tracker', 'self-hosted issue tracker download', 'JTrac download', 'issue tracker for Windows', 'issue tracker for Linux', 'issue tracker for macOS'],
    heading: 'Download Jtrac.',
    lede: 'One archive per platform with its own Java runtime and a managed PostgreSQL. Unpack it, start it and finish setup in the browser.',
  },
  {
    path: 'technology/',
    label: 'Technology',
    title: 'Jtrac Technology: Java 25, Spring Boot, PostgreSQL',
    description:
      'How Jtrac is built: Java 25 and Spring Boot 4.1 on the server, PostgreSQL 18 for data, a React and TypeScript web app, and a REST API with OpenAPI 3.',
    keywords: ['Java issue tracker', 'Spring Boot issue tracker', 'PostgreSQL issue tracker', 'React issue tracker', 'OpenAPI'],
    heading: 'How Jtrac is built.',
    lede: 'A Spring Boot modular monolith with a React web app on PostgreSQL, packaged with its own Java runtime for Windows, Linux and macOS.',
  },
  {
    path: 'docs/',
    label: 'Docs',
    title: 'Jtrac Documentation: Install, Configure and Upgrade',
    description:
      'Guides for installing Jtrac, the setup wizard, SMTP, configuration, backups, upgrades, troubleshooting, security and the REST API reference.',
    keywords: ['Jtrac documentation', 'install issue tracker', 'issue tracker setup', 'SMTP configuration', 'backup and restore', 'upgrade guide'],
    heading: 'Jtrac documentation.',
    lede: 'Installation, configuration and operations guides, maintained in the repository next to the code they describe.',
  },
  {
    path: 'faq/',
    label: 'FAQ',
    title: 'Jtrac FAQ: Licensing, Requirements and Hosting',
    description:
      'Answers about Jtrac: is it free, do you need Java or a PostgreSQL server, which databases work, telemetry, email and putting it on the internet.',
    keywords: ['Jtrac FAQ', 'free issue tracker', 'issue tracker requirements', 'self-hosting issue tracker'],
    heading: 'Frequently asked questions.',
    lede: 'Short answers about licensing, requirements and running Jtrac on your own server.',
  },
  {
    path: 'open-source/',
    label: 'Open Source',
    title: 'Jtrac Open Source: Apache 2.0 Licensed Issue Tracker',
    description:
      'Jtrac is open source under the Apache License 2.0. Read the code, report issues, contribute changes and review the security policy on GitHub.',
    keywords: ['open source issue tracker', 'Apache 2.0 issue tracker', 'free bug tracker', 'JTrac alternative', 'GitHub issue tracker project'],
    heading: 'Open source, Apache License 2.0.',
    lede: 'The code, documentation, issue tracker and release history are public on GitHub.',
  },
];

export function getPage(path: string): Page {
  const page = pages.find((p) => p.path === path);
  if (!page) throw new Error(`Unknown page: ${path}`);
  return page;
}

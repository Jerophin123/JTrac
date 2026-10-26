/**
 * Every outbound destination on the page. All were checked against github.com
 * (HTTP 200) and the Releases page lists published packages, so "Download" is accurate.
 */
const repo = 'https://github.com/Jerophin123/JTrac';
const doc = (path: string) => `${repo}/blob/main/${path}`;

/**
 * Public address of this site, with a trailing slash (e.g. 'https://jtrac.example.com/').
 * Not decided yet: while null, the build emits no canonical URL, sitemap entries or absolute
 * share-image URLs rather than wrong ones. Set it before publishing; `pnpm check:site` then
 * requires all of them.
 */
export const siteUrl: string | null = null;

/** Latest published release (checked on the Releases page). Update with each release. */
export const latestRelease = { version: '3.0.0', url: `${repo}/releases/tag/v3.0.0` } as const;

const asset = (name: string) => `${repo}/releases/download/v${latestRelease.version}/${name}`;

export type DownloadId = 'windows-x64' | 'linux-x64' | 'linux-arm64' | 'macos-arm64' | 'macos-x64';

export interface Download {
  id: DownloadId;
  os: 'Windows' | 'Linux' | 'macOS';
  arch: string;
  file: string;
  /** Rounded size in MB from the release asset listing. */
  sizeMb: number;
  url: string;
  sha256: string;
  smokeReport: string;
}

const pkg = (id: DownloadId, os: Download['os'], arch: string, ext: string, sizeMb: number): Download => {
  const file = `jtrac-${latestRelease.version}-${id}.${ext}`;
  return { id, os, arch, file, sizeMb, url: asset(file), sha256: asset(`${file}.sha256`), smokeReport: asset(`${file}.installer-smoke.md`) };
};

/** Direct links to the release assets (names and sizes from the GitHub release API). */
export const downloads: Download[] = [
  pkg('windows-x64', 'Windows', 'x64', 'zip', 153),
  pkg('linux-x64', 'Linux', 'x64', 'tar.gz', 139),
  pkg('linux-arm64', 'Linux', 'ARM64', 'tar.gz', 138),
  pkg('macos-arm64', 'macOS', 'Apple Silicon', 'tar.gz', 234),
  pkg('macos-x64', 'macOS', 'Intel', 'tar.gz', 236),
];

export const checksums = asset('SHA256SUMS');

export const links = {
  repo,
  releases: `${repo}/releases`,
  readme: `${repo}#readme`,
  issues: `${repo}/issues`,
  installation: doc('docs/installation.md'),
  setupWizard: doc('docs/setup-wizard.md'),
  platforms: doc('docs/platform-support.md'),
  upgrades: doc('docs/upgrades.md'),
  configuration: doc('docs/configuration-reference.md'),
  smtp: doc('docs/smtp-configuration.md'),
  backup: doc('docs/backup-restore.md'),
  troubleshooting: doc('docs/troubleshooting.md'),
  securityDoc: doc('docs/security.md'),
  architecture: doc('docs/architecture.md'),
  database: doc('docs/database-and-migrations.md'),
  openapi: doc('api/openapi.yaml'),
  testing: doc('docs/testing.md'),
  adr: `${repo}/tree/main/docs/adr`,
  changelog: doc('CHANGELOG.md'),
  contributing: doc('CONTRIBUTING.md'),
  security: doc('SECURITY.md'),
  license: doc('LICENSE'),
  original: 'https://sourceforge.net/projects/j-trac/',
} as const;

export const sections = [
  // Page order, so the scroll-spy moves forward as the page scrolls.
  { id: 'product', label: 'Product' },
  { id: 'features', label: 'Features' },
  { id: 'technology', label: 'Technology' },
  { id: 'docs', label: 'Docs' },
  { id: 'open-source', label: 'Open Source' },
] as const;

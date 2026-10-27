'use client';

import { Download as DownloadIcon, FileCheck, Laptop, Monitor, ShieldCheck, Terminal, type LucideIcon } from 'lucide-react';
import { checksums, downloads, latestRelease, links } from '@/lib/site';
import { useDetectedDownload } from './DownloadButton';
import landing from './landing.module.css';
import page from './product/styles/Page.module.css';
import { Reveal } from './Reveal';
import { ButtonLink, SectionHeader } from './ui';

const OS: { os: 'Windows' | 'Linux' | 'macOS'; icon: LucideIcon }[] = [
  { os: 'Windows', icon: Monitor },
  { os: 'Linux', icon: Terminal },
  { os: 'macOS', icon: Laptop },
];

function ExternalLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={landing.link}>
      {children}
      <span className="sr-only"> (opens on GitHub in a new tab)</span>
    </a>
  );
}

export function Downloads() {
  const detected = useDetectedDownload();
  return (
    <section id="download" aria-labelledby="download-title" className="relative px-4 pb-28 sm:px-6 md:pb-36">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <SectionHeader
            id="download-title"
            eyebrow="Download"
            title={`Download Jtrac ${latestRelease.version}`}
            lede="One self-contained package per platform: the server, the jtrac command, a Java 25 runtime and, for the managed database mode, PostgreSQL. Unpack it and run setup."
          />
        </Reveal>

        <div className="mt-14 grid gap-4 lg:grid-cols-3">
          {OS.map(({ os, icon: Icon }, i) => (
            <Reveal key={os} delay={i * 0.06} className="h-full">
              <section aria-labelledby={`dl-${os}`} className={`${page.panel} h-full`}>
                <header className={page.panelHeader}>
                  <h3 id={`dl-${os}`} className={`${page.panelTitle} font-semibold text-snow`}>
                    <Icon size={16} aria-hidden="true" className="text-muted" />
                    {os}
                  </h3>
                </header>
                <ul className="grid gap-px">
                  {downloads
                    .filter((d) => d.os === os)
                    .map((d) => {
                      const recommended = detected?.id === d.id;
                      return (
                        <li key={d.id} className={`grid gap-3 p-4 ${recommended ? 'bg-brand-soft/40' : ''}`}>
                          <div className="flex items-baseline justify-between gap-3">
                            <p className="font-medium text-snow">
                              {d.arch}
                              {recommended ? (
                                <span className="ms-2 rounded-full bg-brand-soft px-2 py-0.5 text-xs font-semibold text-brand-fg">Your system</span>
                              ) : null}
                            </p>
                            <p className="shrink-0 text-sm text-muted">{d.sizeMb} MB</p>
                          </div>
                          <p className="truncate font-mono text-xs text-subtle" title={d.file}>
                            {d.file}
                          </p>
                          <ButtonLink
                            href={d.url}
                            download={`${String(d.sizeMb)} MB`}
                            variant={recommended ? 'primary' : 'secondary'}
                            size="md"
                            icon={<DownloadIcon size={16} aria-hidden="true" />}
                            className="w-full"
                          >
                            Download {d.file.endsWith('.zip') ? '.zip' : '.tar.gz'}
                          </ButtonLink>
                          <p className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
                            <span className="inline-flex items-center gap-1.5">
                              <ShieldCheck size={13} aria-hidden="true" className="text-subtle" />
                              <ExternalLink href={d.sha256}>SHA-256</ExternalLink>
                            </span>
                            <span className="inline-flex items-center gap-1.5">
                              <FileCheck size={13} aria-hidden="true" className="text-subtle" />
                              <ExternalLink href={d.smokeReport}>Installer test report</ExternalLink>
                            </span>
                          </p>
                        </li>
                      );
                    })}
                </ul>
              </section>
            </Reveal>
          ))}
        </div>

        <Reveal delay={0.1}>
          <div className="mt-6 grid gap-3 rounded-(--radius-shape) border border-line bg-panel p-5 text-sm text-muted md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
            <p>
              Check the archive before running it: compare it with <span className="font-mono text-snow">SHA256SUMS</span>, then follow
              the <ExternalLink href={links.installation}>installation guide</ExternalLink>. Windows on ARM has no package yet: build
              from source with an external PostgreSQL.
            </p>
            <p className="flex flex-wrap gap-x-5 gap-y-2">
              <ExternalLink href={checksums}>SHA256SUMS</ExternalLink>
              <ExternalLink href={latestRelease.url}>Release notes</ExternalLink>
              <ExternalLink href={links.releases}>All releases</ExternalLink>
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

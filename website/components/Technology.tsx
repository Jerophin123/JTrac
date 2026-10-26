import { Package, Terminal } from 'lucide-react';
import { links } from '@/lib/site';
import landing from './landing.module.css';
import { Reveal } from './Reveal';
import { SectionHeader } from './ui';

// Verified against gradle/libs.versions.toml, frontend/package.json and the README.
const stack: { group: string; items: string[] }[] = [
  { group: 'Server', items: ['Java 25', 'Spring Boot 4.1', 'Spring Modulith', 'Spring Security', 'Flyway'] },
  { group: 'Data', items: ['PostgreSQL 18'] },
  { group: 'Web app', items: ['React 19', 'TypeScript', 'Vite', 'TanStack Router', 'TanStack Query'] },
  { group: 'Interfaces', items: ['REST /api/v1', 'OpenAPI 3', 'Server-Sent Events', 'Webhooks'] },
];

// Targets with a package on the Releases page.
const platforms = ['Windows x64', 'Linux x64', 'Linux ARM64', 'macOS Intel', 'macOS Apple Silicon'];

export function Technology() {
  return (
    <section id="technology" aria-labelledby="technology-title" className="relative border-y border-line bg-[linear-gradient(180deg,var(--color-canvas),var(--color-surface-sunken))] px-4 py-28 sm:px-6 md:py-32">
      <div className="mx-auto max-w-6xl">
        <div className="grid gap-14 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.25fr)] lg:gap-20">
          <Reveal from="left">
            <SectionHeader
              id="technology-title"
              align="start"
              eyebrow="Technology"
              title="A modern stack, packaged to run anywhere."
              lede="A Spring Boot modular monolith with a React single-page app on top of PostgreSQL. Each release package bundles its own Java runtime and, for the managed database mode, PostgreSQL binaries."
            />
            <div className="mt-8 flex items-start gap-3 rounded-(--radius-shape) border border-line bg-panel p-4">
              <Terminal size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-brand-fg" />
              <p className="text-sm leading-relaxed text-muted">
                A guided setup wizard configures the database, the first administrator and SMTP, and the{' '}
                <code className="rounded-(--radius-sm) bg-ink px-1 font-mono text-[0.8125rem] text-snow">jtrac</code> command handles start, stop, backup, restore and
                update.{' '}
                <a href={links.installation} target="_blank" rel="noopener noreferrer" className={landing.link}>
                  Read the installation guide
                  <span className="sr-only"> (opens on GitHub in a new tab)</span>
                </a>
              </p>
            </div>
          </Reveal>

          <Reveal from="right" delay={0.1}>
            <dl className="grid gap-px overflow-hidden rounded-(--radius-shape) border border-line bg-line">
              {stack.map(({ group, items }) => (
                <div key={group} className="grid gap-3 bg-panel p-5 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:items-center">
                  <dt className="eyebrow">{group}</dt>
                  <dd className="flex flex-wrap gap-2">
                    {items.map((item) => (
                      <span key={item} className={landing.chip}>
                        {item}
                      </span>
                    ))}
                  </dd>
                </div>
              ))}
            </dl>

            <div className="mt-6 rounded-(--radius-shape) border border-line bg-panel p-5">
              <p className="flex items-center gap-2 text-sm font-medium text-snow">
                <Package size={16} aria-hidden="true" className="text-brand-fg" />
                Self-contained packages
              </p>
              <ul className="mt-3 flex flex-wrap gap-2">
                {platforms.map((p) => (
                  <li key={p} className={landing.chip}>
                    <span aria-hidden="true" className={landing.chipDot} />
                    {p}
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-xs text-subtle">
                Testing status per platform is documented in{' '}
                <a href={links.platforms} target="_blank" rel="noopener noreferrer" className={landing.link}>
                  platform support
                  <span className="sr-only"> (opens on GitHub in a new tab)</span>
                </a>
                .
              </p>
            </div>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

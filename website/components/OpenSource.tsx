import { Code, FolderGit2, GitPullRequest, Package, Scale, type LucideIcon } from 'lucide-react';
import { links } from '@/lib/site';
import landing from './landing.module.css';
import { Reveal } from './Reveal';
import { DownloadButton } from './DownloadButton';
import { ButtonLink, SectionHeader } from './ui';

const highlights: { icon: LucideIcon; title: string; body: string; href: string; cta: string }[] = [
  {
    icon: Code,
    title: 'Explore the code',
    body: 'Backend, web app, API contract, architecture decision records and the release pipeline all live in one public repository.',
    href: links.repo,
    cta: 'Browse the repository',
  },
  {
    icon: Package,
    title: 'Available releases',
    body: 'Published packages for Windows, Linux and macOS, each with a SHA-256 checksum and its installer test report.',
    href: links.releases,
    cta: 'See the releases',
  },
  {
    icon: GitPullRequest,
    title: 'Contribute',
    body: 'The contributing guide covers development setup, project rules and the pull-request process. Security reports follow the security policy.',
    href: links.contributing,
    cta: 'Read the contributing guide',
  },
];

export function OpenSource() {
  return (
    <section id="open-source" aria-labelledby="open-source-title" className="relative px-4 py-28 sm:px-6 md:py-36">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <SectionHeader
            id="open-source-title"
            eyebrow="Open Source"
            title="Open source. Built for teams."
            lede="Explore the code, discover available releases, and follow the project's development through its official repository."
          />
          <p className="mt-6 text-center">
            <span className={`${landing.chip} text-muted`}>
              <Scale size={14} aria-hidden="true" className="text-brand-fg" />
              Licensed under the
              <a href={links.license} target="_blank" rel="noopener noreferrer" className={landing.link}>
                Apache License 2.0
                <span className="sr-only"> (opens on GitHub in a new tab)</span>
              </a>
            </span>
          </p>
        </Reveal>

        <ul className="mt-16 grid gap-4 md:grid-cols-3">
          {highlights.map(({ icon: Icon, title, body, href, cta }, i) => (
            <Reveal as="li" key={title} delay={i * 0.08} className="h-full">
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className={`group panel ${landing.card} flex h-full flex-col p-6 no-underline transition-[transform,border-color] duration-300 ease-out-soft hover:-translate-y-1 hover:border-line-strong`}
              >
                <span className={landing.iconTile}>
                  <Icon size={19} strokeWidth={1.85} aria-hidden="true" />
                </span>
                <h3 className="mt-5 text-lg font-semibold tracking-tight text-snow">{title}</h3>
                <p className="mt-2.5 flex-1 text-[0.9375rem] leading-relaxed text-muted">{body}</p>
                <span className="mt-6 inline-flex items-center gap-1.5 text-sm font-medium text-brand-fg">
                  {cta}
                  <span aria-hidden="true" className="transition-transform duration-200 group-hover:translate-x-1">
                    →
                  </span>
                  <span className="sr-only">(opens on GitHub in a new tab)</span>
                </span>
              </a>
            </Reveal>
          ))}
        </ul>

        <Reveal delay={0.1} className="mt-12 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <DownloadButton />
          <ButtonLink href={links.repo} variant="secondary" icon={<FolderGit2 size={17} aria-hidden="true" />}>
            View Source Code
          </ButtonLink>
        </Reveal>

        <p className="mx-auto mt-10 max-w-2xl text-center text-xs leading-relaxed text-subtle">
          This JTRAC is an unofficial, independent reimagining of the classic JTrac issue tracker. It is not affiliated with,
          endorsed by or maintained by the{' '}
          <a href={links.original} target="_blank" rel="noopener noreferrer" className={landing.link}>
            original JTrac project
            <span className="sr-only"> (opens SourceForge in a new tab)</span>
          </a>{' '}
          and contains none of its source code.
        </p>
      </div>
    </section>
  );
}

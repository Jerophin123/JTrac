import {
  ArrowUpRight,
  BookOpen,
  Boxes,
  Bug,
  DatabaseBackup,
  FileCode,
  GitPullRequest,
  History,
  LifeBuoy,
  Mail,
  Monitor,
  Network,
  Rocket,
  Settings2,
  ShieldCheck,
  ListChecks,
  type LucideIcon,
} from 'lucide-react';
import { links } from '@/lib/site';
import page from './product/styles/Page.module.css';
import { Reveal } from './Reveal';
import { SectionHeader } from './ui';

// Grouped like the documentation menu of the classic JTrac site; every page is in the repository.
const groups: { title: string; items: { icon: LucideIcon; label: string; note: string; href: string }[] }[] = [
  {
    title: 'Get started',
    items: [
      { icon: Rocket, label: 'Installation', note: 'Download, unpack, run setup', href: links.installation },
      { icon: ListChecks, label: 'Setup wizard', note: 'Every step, interactive or unattended', href: links.setupWizard },
      { icon: Monitor, label: 'Platform support', note: 'What was tested, where', href: links.platforms },
      { icon: History, label: 'Upgrades', note: 'Moving to a new release safely', href: links.upgrades },
    ],
  },
  {
    title: 'Run it',
    items: [
      { icon: Settings2, label: 'Configuration', note: 'Every setting and its default', href: links.configuration },
      { icon: Mail, label: 'Outgoing email', note: 'SMTP setup and the built-in test', href: links.smtp },
      { icon: DatabaseBackup, label: 'Backup and restore', note: 'Verified backups with manifests', href: links.backup },
      { icon: LifeBuoy, label: 'Troubleshooting', note: 'Symptoms, causes and fixes', href: links.troubleshooting },
    ],
  },
  {
    title: 'Build on it',
    items: [
      { icon: FileCode, label: 'API contract', note: 'OpenAPI 3 for /api/v1', href: links.openapi },
      { icon: Network, label: 'Architecture', note: 'Modules, data flow, decisions', href: links.architecture },
      { icon: Boxes, label: 'Decision records', note: 'Why the stack looks like this', href: links.adr },
      { icon: ShieldCheck, label: 'Security model', note: 'Sessions, CSRF, roles, secrets', href: links.securityDoc },
    ],
  },
  {
    title: 'Project',
    items: [
      { icon: BookOpen, label: 'Changelog', note: 'What changed in each release', href: links.changelog },
      { icon: GitPullRequest, label: 'Contributing', note: 'Setup, rules, pull requests', href: links.contributing },
      { icon: Bug, label: 'Issues', note: 'Report a bug or request a feature', href: links.issues },
      { icon: ShieldCheck, label: 'Security policy', note: 'Reporting vulnerabilities privately', href: links.security },
    ],
  },
];

export function Docs() {
  return (
    <section id="docs" aria-labelledby="docs-title" className="relative px-4 py-28 sm:px-6 md:py-36">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <SectionHeader
            id="docs-title"
            eyebrow="Documentation"
            title="Everything written down, next to the code."
            lede="Installation, operations and development guides live in the repository, versioned with the release they describe."
          />
        </Reveal>

        <div className="mt-14 grid gap-4 md:grid-cols-2">
          {groups.map((group, i) => (
            <Reveal key={group.title} from={i % 2 === 0 ? 'left' : 'right'} delay={Math.floor(i / 2) * 0.1} className="h-full">
              <nav aria-labelledby={`docs-${String(i)}`} className={`${page.panel} h-full`}>
                <header className={page.panelHeader}>
                  <h3 id={`docs-${String(i)}`} className={`${page.panelTitle} font-semibold text-snow`}>
                    {group.title}
                  </h3>
                </header>
                <ul className="grid p-2">
                  {group.items.map(({ icon: Icon, label, note, href }) => (
                    <li key={label}>
                      <a
                        href={href}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="group flex items-center gap-3 rounded-(--radius-sm) px-3 py-2.5 no-underline transition-colors hover:bg-panel-hover"
                      >
                        <Icon size={17} strokeWidth={1.85} aria-hidden="true" className="shrink-0 text-brand-fg" />
                        <span className="grid min-w-0 flex-1">
                          <span className="text-[0.9375rem] font-medium text-snow">{label}</span>
                          <span className="truncate text-[0.8125rem] text-muted">{note}</span>
                        </span>
                        <ArrowUpRight
                          size={15}
                          aria-hidden="true"
                          className="shrink-0 text-subtle transition-transform group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:text-snow"
                        />
                        <span className="sr-only">(opens on GitHub in a new tab)</span>
                      </a>
                    </li>
                  ))}
                </ul>
              </nav>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

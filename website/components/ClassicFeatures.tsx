import { links } from '@/lib/site';
import landing from './landing.module.css';
import type { Category } from './product/data';
import { StatusBadge } from './product/primitives';
import base from './product/styles/Base.module.css';
import table from './product/styles/IssueTable.module.css';
import { Reveal } from './Reveal';
import { SectionHeader } from './ui';

type Verdict = 'Available' | 'Changed' | 'Not yet';

// The status markers reuse the product's category shapes: filled = available,
// half = available in a different form, hollow = not available.
const VERDICT: Record<Verdict, Category> = { Available: 'DONE', Changed: 'IN_PROGRESS', 'Not yet': 'TODO' };

/**
 * The feature list of the original JTrac site, checked one by one against this codebase.
 * "Changed" and "Not yet" rows are deliberate: the page says what is not there.
 */
const rows: { classic: string; verdict: Verdict; now: string }[] = [
  { classic: 'Extremely easy to install', verdict: 'Available', now: 'One package per OS with Java and PostgreSQL bundled, plus a guided setup wizard.' },
  { classic: 'Unlimited project spaces', verdict: 'Available', now: 'Organizations, each with its own projects and project keys, like WEB-42.' },
  { classic: 'Custom fields and drop-downs per project', verdict: 'Available', now: 'Text, number, date, select, multi-select, user and more, with validation.' },
  { classic: 'Customizable workflow per project', verdict: 'Available', now: 'A visual workflow editor with versioned workflows and role-based transitions.' },
  { classic: 'Field-level permissions', verdict: 'Available', now: 'Hidden, read-only, optional or required per status and role, enforced by the server.' },
  { classic: 'Detailed history view', verdict: 'Available', now: 'Attributed, append-only history of every change.' },
  { classic: 'E-mail notifications', verdict: 'Available', now: 'In-app and e-mail, re-checked per recipient for permissions and field visibility.' },
  { classic: 'File attachments', verdict: 'Available', now: 'Streamed to disk with SHA-256 and served only after an authorization check.' },
  { classic: 'Full text search', verdict: 'Available', now: 'PostgreSQL full-text search across issues, plus global search for projects and people.' },
  { classic: 'Filter searches by custom fields', verdict: 'Available', now: 'Search conditions on custom fields, and saved views shared with the team.' },
  { classic: 'Dashboard of statistics', verdict: 'Available', now: 'Dashboards with breakdowns, counts, trends and milestone progress, computed per viewer.' },
  { classic: 'Export to Excel', verdict: 'Changed', now: 'CSV import and export of issues, which spreadsheets open directly.' },
  { classic: 'Cross-reference items (duplicate of, depends on)', verdict: 'Available', now: 'Links: relates, duplicates and blocks, with dependencies drawn on the timeline.' },
  { classic: 'Embedded database and web server', verdict: 'Available', now: 'A managed PostgreSQL 18 and the server run from the package; no separate install.' },
  { classic: 'Anonymous browsing of projects', verdict: 'Changed', now: 'Invited guest members instead of anonymous visitors; project content requires signing in.' },
  { classic: 'Various databases supported', verdict: 'Changed', now: 'PostgreSQL only, by design; Windows, Linux and macOS packages.' },
  { classic: 'Translated into multiple languages', verdict: 'Not yet', now: 'English today; more languages are on the roadmap and the layout is RTL-ready.' },
  { classic: 'LDAP, Active Directory or CAS sign-in', verdict: 'Not yet', now: 'Built-in accounts with Argon2id passwords; no directory or SSO integration yet.' },
];

export function ClassicFeatures() {
  const counts = (['Available', 'Changed', 'Not yet'] as const).map((v) => ({ v, n: rows.filter((r) => r.verdict === v).length }));
  return (
    <section id="classic" aria-labelledby="classic-title" className="relative px-4 pb-28 sm:px-6 md:pb-36">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <SectionHeader
            id="classic-title"
            eyebrow="Then and now"
            title="The classic feature list, checked line by line."
            lede="The original JTrac site listed what made it worth installing. Here is each of those features in Jtrac 3, including the ones that work differently or are not there yet."
          />
        </Reveal>

        <Reveal delay={0.08} className="mt-12">
          <div className="text-[0.875rem]">
            <p className="mb-4 flex flex-wrap items-center justify-center gap-3">
              {counts.map(({ v, n }) => (
                <span key={v} className="inline-flex items-center gap-2 text-sm text-muted">
                  <StatusBadge name={v} category={VERDICT[v]} />
                  <span>{n}</span>
                </span>
              ))}
            </p>
            <div className={table.wrap}>
              <div className={table.scroller}>
                <table className={table.table}>
                  <caption className={base.visuallyHidden}>Features of the original JTrac and their status in Jtrac 3</caption>
                  <thead>
                    <tr>
                      <th scope="col">Classic JTrac</th>
                      <th scope="col">Jtrac 3</th>
                      <th scope="col" className={table.titleCol}>
                        How it works now
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.classic}>
                        <th scope="row" className="min-w-[12rem] bg-transparent! text-sm! font-medium! text-snow! whitespace-normal!">
                          {row.classic}
                        </th>
                        <td>
                          <StatusBadge name={row.verdict} category={VERDICT[row.verdict]} />
                        </td>
                        <td className={`${table.titleCol} text-muted`}>{row.now}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
            <p className="mt-4 text-center text-xs text-subtle">
              Classic list as published by the original project. Status checked against this repository; see the{' '}
              <a href={links.readme} target="_blank" rel="noopener noreferrer" className={landing.link}>
                README feature reference
                <span className="sr-only"> (opens on GitHub in a new tab)</span>
              </a>
              .
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

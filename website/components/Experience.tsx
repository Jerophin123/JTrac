import type { ReactNode } from 'react';
import { demoIssues } from './product/data';
import { Badge } from './product/primitives';
import base from './product/styles/Base.module.css';
import { BoardView, IssueView, TimelineView } from './product/views';
import { Reveal, SpeedMark, Words } from './Reveal';

/** A product view in the replica's frame: product tokens, product CSS, demo data. */
function ProductFrame({ title, label, children }: { title: string; label: string; children: ReactNode }) {
  return (
    <figure aria-label={label} data-nosnippet className={`${base.scope} overflow-hidden rounded-(--radius-shape) border border-line-strong shadow-(--shadow-overlay)`}>
      <div className="flex items-center justify-between gap-3 border-b border-line bg-ink px-4 py-2.5">
        <span className="truncate text-xs font-medium text-muted">{title}</span>
        <Badge tone="accent">Demo data</Badge>
      </div>
      <div className="p-4">{children}</div>
    </figure>
  );
}

const reviewIssue = demoIssues.find((i) => i.key === 'JTRAC-102')!;

const rows = [
  {
    eyebrow: 'Boards, lists, timelines',
    title: 'See the work, then move it.',
    body: 'Drag issues across a board, with keyboard alternatives for every move. Switch to a filtered, sortable list or a timeline with milestones and “blocks” dependencies, and save the view for yourself or your team.',
    mock: (
      <ProductFrame title="JTRAC Platform · Board" label="JTRAC board view with demo issues">
        <BoardView issues={demoIssues} columns={['todo', 'progress', 'review']} style={{ gridAutoColumns: 'minmax(11.5rem, 1fr)', minBlockSize: 0 }} />
      </ProductFrame>
    ),
  },
  {
    eyebrow: 'Workflows and field rules',
    title: 'Your process, enforced by the server.',
    body: 'Each status offers only the transitions its workflow allows, and some are limited to roles: in the Structured template only Administrators can Approve. Field rules make a field hidden, read-only, optional or required per status, here the assignee in review, and the server checks them on every read and write.',
    mock: (
      <ProductFrame title="JTRAC-102 · Issue details" label="JTRAC issue details panel with workflow transitions and a required field">
        <IssueView issue={reviewIssue} projectName="JTRAC Platform" compact />
      </ProductFrame>
    ),
  },
  {
    eyebrow: 'Planning',
    title: 'Dates, milestones and what blocks what.',
    body: 'The timeline lays out scheduled issues against milestones and draws “blocks” dependencies, flagging work that is scheduled to start before its blocker ends. Dashboards then summarize progress, computed for each viewer so nobody sees what they cannot open.',
    mock: (
      <ProductFrame title="JTRAC Platform · Timeline" label="JTRAC timeline view with demo issues, a milestone and a dependency">
        <TimelineView issues={demoIssues} />
      </ProductFrame>
    ),
  },
];

export function Experience() {
  return (
    <section aria-labelledby="experience-title" className="relative px-4 pb-28 sm:px-6 md:pb-36">
      {/* Outline-only heading: groups the rows below without adding visible text. */}
      <h2 id="experience-title" className="sr-only">
        Product experience
      </h2>
      <div className="mx-auto grid max-w-6xl gap-24 md:gap-32">
        {rows.map((row, i) => (
          <div key={row.title} className="grid items-center gap-10 md:grid-cols-[minmax(0,0.85fr)_minmax(0,1.15fr)] md:gap-16">
            <Reveal from={i % 2 === 1 ? 'right' : 'left'} className={i % 2 === 1 ? 'md:order-2' : ''}>
              <SpeedMark delay={0.2} />
              <p className="eyebrow mt-5">
                <span className="me-2 font-mono text-brand-fg">0{i + 1}</span>
                {row.eyebrow}
              </p>
              <h3 aria-label={row.title} className="mt-3 text-2xl font-bold tracking-[-0.03em] text-balance sm:text-3xl md:text-[2.25rem] md:leading-[1.1]">
                <Words text={row.title} inView delay={0.25} stagger={0.05} wordClassName="text-sheen" />
              </h3>
              <p className="mt-5 max-w-lg text-base leading-relaxed text-muted text-pretty">{row.body}</p>
            </Reveal>
            <Reveal from={i % 2 === 1 ? 'left' : 'right'} delay={0.12} className="relative min-w-0">
              <div
                aria-hidden="true"
                className="pointer-events-none absolute inset-x-0 -inset-y-8 -z-10 bg-[radial-gradient(50%_50%_at_50%_50%,color-mix(in_srgb,var(--color-accent)_10%,transparent),transparent_70%)] blur-2xl"
              />
              {row.mock}
            </Reveal>
          </div>
        ))}
      </div>
    </section>
  );
}

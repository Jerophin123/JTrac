import { Hash, Search, Webhook, Workflow, type LucideIcon } from 'lucide-react';
import landing from './landing.module.css';
import { Reveal } from './Reveal';
import { SectionHeader } from './ui';

// Every claim below is documented in the repository README ("Features").
const features: { icon: LucideIcon; title: string; body: string; detail: string }[] = [
  {
    icon: Hash,
    title: 'Issues people can name',
    body: 'Readable keys like WEB-42, Markdown descriptions, sub-tasks, links and per-project custom fields.',
    detail: 'Optimistic locking · append-only history',
  },
  {
    icon: Workflow,
    title: 'Workflows that hold',
    body: 'Design statuses and transitions in a visual editor, decide which roles may use each one, and set field rules per status.',
    detail: 'Enforced on the server',
  },
  {
    icon: Search,
    title: 'Find it, see it',
    body: 'Boards, filtered lists and timelines, saved views and global search across issues, projects and people.',
    detail: 'Dashboards computed per viewer',
  },
  {
    icon: Webhook,
    title: 'Built to connect',
    body: 'Comments with @mentions, attachments and notifications, plus a REST API with personal tokens and signed webhooks.',
    detail: 'OpenAPI 3 · HMAC-SHA256 · CSV',
  },
];

export function Features() {
  return (
    <section id="features" aria-labelledby="features-title" className="relative px-4 py-28 sm:px-6 md:py-36">
      <div className="mx-auto max-w-6xl">
        <Reveal>
          <SectionHeader
            id="features-title"
            eyebrow="Features"
            title="The essentials of tracking, done properly."
            lede="Workflows, custom fields and KEY-42 issues from the classic tracker, rebuilt with a fast interface and rules the server actually enforces."
          />
        </Reveal>

        <ul className="mt-16 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {features.map(({ icon: Icon, title, body, detail }, i) => (
            <Reveal as="li" key={title} delay={i * 0.08} className="h-full">
              <article className={`glass ${landing.card} flex h-full flex-col p-6 transition-[transform,border-color] duration-300 ease-out-soft hover:-translate-y-1 hover:border-line-strong`}>
                <span className={landing.iconTile}>
                  <Icon size={19} strokeWidth={1.85} aria-hidden="true" />
                </span>
                <h3 className="mt-6 text-lg font-semibold tracking-tight text-snow">{title}</h3>
                <p className="mt-2.5 flex-1 text-[0.9375rem] leading-relaxed text-muted">{body}</p>
                <p className="mt-6 border-t border-line pt-4 font-mono text-[0.6875rem] tracking-wide text-subtle">{detail}</p>
              </article>
            </Reveal>
          ))}
        </ul>
      </div>
    </section>
  );
}

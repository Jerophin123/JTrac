import { links, sections } from '@/lib/site';
import { Logo } from './Logo';

const groups: { title: string; items: { label: string; href: string }[] }[] = [
  {
    title: 'Project',
    items: [
      { label: 'Repository', href: links.repo },
      { label: 'Releases', href: links.releases },
      { label: 'README', href: links.readme },
      { label: 'Installation guide', href: links.installation },
    ],
  },
  {
    title: 'Community',
    items: [
      { label: 'Contributing', href: links.contributing },
      { label: 'Security policy', href: links.security },
      { label: 'License', href: links.license },
    ],
  },
];

export function SiteFooter() {
  return (
    <footer className="border-t border-line px-4 pt-16 pb-10 sm:px-6">
      <div className="mx-auto grid max-w-6xl gap-12 md:grid-cols-[minmax(0,1.4fr)_repeat(3,minmax(0,1fr))]">
        <div>
          <Logo height={22} />
          <p className="mt-5 max-w-xs text-sm leading-relaxed text-muted">
            A self-hosted issue tracker and project-management server: an unofficial, modern-era reimagining of the classic
            JTrac.
          </p>
        </div>

        {groups.map((group) => (
          <nav key={group.title} aria-label={group.title}>
            <h2 className="eyebrow">{group.title}</h2>
            <ul className="mt-4 grid gap-2.5">
              {group.items.map((item) => (
                <li key={item.label}>
                  <a
                    href={item.href}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sm text-muted transition-colors hover:text-snow"
                  >
                    {item.label}
                    <span className="sr-only"> (opens on GitHub in a new tab)</span>
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        ))}

        <nav aria-label="On this page">
          <h2 className="eyebrow">On this page</h2>
          <ul className="mt-4 grid gap-2.5">
            {sections.map((s) => (
              <li key={s.id}>
                <a href={`#${s.id}`} className="text-sm text-muted transition-colors hover:text-snow">
                  {s.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      </div>

      <div className="mx-auto mt-14 flex max-w-6xl flex-col gap-2 border-t border-line pt-6 text-xs text-subtle sm:flex-row sm:justify-between">
        <p>© 2026 Jerophin D R. Released under the Apache License 2.0.</p>
        <p>Not affiliated with the original JTrac project.</p>
      </div>
    </footer>
  );
}

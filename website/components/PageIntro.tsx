import Link from 'next/link';
import type { Page } from '@/lib/pages';
import { SpeedMark } from './Reveal';

/** The h1 and introduction of a subpage, with a breadcrumb back to the home page. */
export function PageIntro({ page }: { page: Page }) {
  return (
    <section aria-labelledby="page-title" className="relative px-4 pt-36 pb-16 sm:px-6 md:pt-44 md:pb-20">
      <div className="mx-auto max-w-3xl text-center">
        <nav aria-label="Breadcrumb">
          <ol className="inline-flex items-center gap-2 text-[0.8125rem] text-subtle">
            <li>
              <Link href="/" className="transition-colors hover:text-snow">
                Home
              </Link>
            </li>
            <li aria-hidden="true">/</li>
            <li aria-current="page" className="text-muted">
              {page.label}
            </li>
          </ol>
        </nav>
        <SpeedMark centered inView={false} className="mt-6" />
        <h1 id="page-title" className="text-sheen mt-5 text-[clamp(2.25rem,5.4vw,3.75rem)] leading-[1.06] font-semibold tracking-[-0.04em] text-balance">
          {page.heading}
        </h1>
        <p className="mx-auto mt-6 max-w-2xl text-base leading-relaxed text-muted text-pretty sm:text-lg">{page.lede}</p>
      </div>
    </section>
  );
}

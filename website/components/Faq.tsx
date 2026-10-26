import { ChevronDown } from 'lucide-react';
import { faq } from '@/lib/faq';
import { links } from '@/lib/site';
import landing from './landing.module.css';
import { Reveal } from './Reveal';
import { SectionHeader } from './ui';

export function Faq() {
  return (
    <section aria-labelledby="faq-title" className="relative px-4 pb-28 sm:px-6 md:pb-36">
      <div className="mx-auto grid max-w-6xl gap-12 lg:grid-cols-[minmax(0,0.8fr)_minmax(0,1.2fr)] lg:gap-20">
        <Reveal from="left">
          <SectionHeader
            id="faq-title"
            align="start"
            eyebrow="FAQ"
            title="Questions worth answering once."
            lede={
              <>
                The full list, with troubleshooting, is in the{' '}
                <a href={links.readme} target="_blank" rel="noopener noreferrer" className={landing.link}>
                  README
                  <span className="sr-only"> (opens on GitHub in a new tab)</span>
                </a>
                .
              </>
            }
          />
        </Reveal>
        <Reveal from="right" delay={0.08}>
          <div className="grid gap-2">
            {faq.map(({ q, a }) => (
              <details key={q} className="group panel rounded-(--radius-shape) open:border-line-strong">
                <summary className="flex cursor-pointer list-none items-center gap-3 px-5 py-4 text-[0.9375rem] font-medium text-snow [&::-webkit-details-marker]:hidden">
                  <span aria-hidden="true" className="h-3.5 w-[3px] shrink-0 -skew-x-12 rounded-[1px] bg-line-strong transition-colors group-open:bg-brand" />
                  <span className="flex-1">{q}</span>
                  <ChevronDown size={16} aria-hidden="true" className="shrink-0 text-subtle transition-transform duration-200 group-open:rotate-180" />
                </summary>
                <p className="px-5 pb-5 ps-[2.35rem] text-[0.9375rem] leading-relaxed text-muted">{a}</p>
              </details>
            ))}
          </div>
        </Reveal>
      </div>
    </section>
  );
}

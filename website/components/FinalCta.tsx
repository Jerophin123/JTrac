import { FolderGit2 } from 'lucide-react';
import { links } from '@/lib/site';
import { LogoMark } from './Logo';
import { Reveal } from './Reveal';
import { DownloadButton } from './DownloadButton';
import { ButtonLink } from './ui';

export function FinalCta() {
  return (
    <section aria-labelledby="final-cta-title" className="px-4 pb-24 sm:px-6 md:pb-32">
      <Reveal from="scale" className="mx-auto max-w-5xl">
        <div className="glass relative isolate overflow-hidden rounded-[28px_8px] px-6 py-16 text-center sm:px-12 md:py-24">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute start-1/2 -bottom-40 -z-10 h-80 w-[44rem] max-w-[140%] -translate-x-1/2 rounded-full bg-[radial-gradient(closest-side,color-mix(in_srgb,var(--color-accent)_35%,transparent),color-mix(in_srgb,var(--color-accent)_8%,transparent)_55%,transparent)] blur-2xl rtl:translate-x-1/2"
          />
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-12 top-0 -z-10 h-px bg-[linear-gradient(90deg,transparent,rgb(255_255_255/0.25),transparent)]"
          />
          <LogoMark size={44} aria-hidden="true" role="presentation" className="mx-auto" />
          <h2 id="final-cta-title" className="text-sheen mt-8 text-3xl font-bold tracking-[-0.035em] text-balance sm:text-5xl">
            Ready to explore Jtrac?
          </h2>
          <p className="mx-auto mt-5 max-w-lg text-base leading-relaxed text-muted text-pretty sm:text-lg">
            Start with the source, explore available releases, and see how Jtrac fits your workflow.
          </p>
          <div className="mt-10 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <DownloadButton className="w-full sm:w-auto" />
            <ButtonLink href={links.repo} variant="secondary" icon={<FolderGit2 size={17} aria-hidden="true" />} className="w-full sm:w-auto">
              Explore the Repository
            </ButtonLink>
          </div>
        </div>
      </Reveal>
    </section>
  );
}

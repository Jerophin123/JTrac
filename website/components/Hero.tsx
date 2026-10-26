'use client';

import { ArrowRight, FolderGit2 } from 'lucide-react';
import { motion } from 'motion/react';
import { latestRelease, links } from '@/lib/site';
import { AppPreview } from './AppPreview';
import { DownloadButton } from './DownloadButton';
import { HeroBackdrop } from './HeroBackdrop';
import { ease, Words } from './Reveal';
import { ButtonLink } from './ui';

function enter(delay: number, from: { x?: number; y?: number } = { y: 16 }, duration = 0.8) {
  return {
    initial: { opacity: 0, ...from },
    animate: { opacity: 1, x: 0, y: 0 },
    transition: { duration, delay, ease },
  };
}

/**
 * One message, two actions, and the product itself as the focal point.
 * Choreography: the pill drops in, "Project work." rises word by word, "Finally in" slides
 * in from the start and "flow." from the end, the speed strokes draw under "flow", then the
 * copy, the two actions (from opposite sides) and the product preview follow.
 */
export function Hero() {
  return (
    <section id="top" aria-labelledby="hero-title" className="relative isolate px-4 pt-36 pb-24 sm:px-6 md:pt-44 md:pb-32">
      <HeroBackdrop />

      <div className="mx-auto max-w-3xl text-center">
        <motion.div {...enter(0.05, { y: -14 })} className="motion-ssr">
          <a
            href={latestRelease.url}
            target="_blank"
            rel="noopener noreferrer"
            className="group inline-flex items-center gap-2.5 rounded-full border border-line bg-panel/60 py-1 ps-1 pe-3 text-[0.8125rem] text-muted no-underline backdrop-blur transition-colors hover:border-line-strong hover:text-snow"
          >
            <span className="rounded-full bg-brand-soft px-2 py-0.5 text-xs font-semibold text-brand-fg">v{latestRelease.version}</span>
            Open source issue tracking
            <ArrowRight size={14} aria-hidden="true" className="text-subtle transition-transform group-hover:translate-x-0.5" />
            <span className="sr-only">(release notes, opens on GitHub in a new tab)</span>
          </a>
        </motion.div>

        <h1
          id="hero-title"
          aria-label="Project work. Finally in flow."
          className="mt-8 text-[clamp(2.5rem,6.4vw,4.75rem)] leading-[1.04] font-semibold tracking-[-0.04em]"
        >
          <Words text="Project work." className="block" wordClassName="text-sheen" delay={0.2} stagger={0.09} />
          <span aria-hidden="true" className="block">
            <motion.span {...enter(0.55, { x: -64 }, 1)} className="motion-ssr text-sheen inline-block">
              Finally in
            </motion.span>{' '}
            <motion.span {...enter(0.7, { x: 64 }, 1)} className="motion-ssr relative inline-block text-brand">
              flow
              {/* The logo's two speed strokes as an underline, sized in em so it spans the word. */}
              <motion.span
                aria-hidden="true"
                className="motion-ssr absolute inset-x-[0.04em] -bottom-[0.02em] h-[0.07em] rounded-[1px] bg-[linear-gradient(90deg,var(--color-accent)_0_64%,transparent_64%_70%,color-mix(in_srgb,var(--color-accent)_45%,transparent)_70%)]"
                style={{ originX: 0, skewX: -12 }}
                initial={{ scaleX: 0, opacity: 0 }}
                animate={{ scaleX: 1, opacity: 1 }}
                transition={{ duration: 0.8, delay: 1.25, ease }}
              />
            </motion.span>
            <motion.span {...enter(0.85, { x: 24 }, 0.8)} className="motion-ssr text-sheen inline-block">
              .
            </motion.span>
          </span>
        </h1>

        <motion.p
          {...enter(0.95, { y: 18 })}
          className="motion-ssr mx-auto mt-7 max-w-[34rem] text-base leading-relaxed text-muted text-pretty sm:text-lg"
        >
          A clearer way to track issues, organize project work and keep teams moving. Self-hosted, with the source and
          releases on GitHub.
        </motion.p>

        <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
          <motion.div {...enter(1.1, { x: -40 })} className="motion-ssr w-full sm:w-auto">
            <DownloadButton className="w-full sm:w-auto" />
          </motion.div>
          <motion.div {...enter(1.18, { x: 40 })} className="motion-ssr w-full sm:w-auto">
            <ButtonLink href={links.repo} variant="secondary" icon={<FolderGit2 size={17} aria-hidden="true" />} className="w-full sm:w-auto">
              Explore on GitHub
            </ButtonLink>
          </motion.div>
        </div>

        <motion.p {...enter(1.3, { y: 8 })} className="motion-ssr mt-6 text-[0.8125rem] text-subtle">
          Apache License 2.0 · Windows, Linux and macOS
        </motion.p>
      </div>

      <motion.div
        initial={{ opacity: 0, y: 60, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 1.1, delay: 1.35, ease }}
        id="product"
        aria-label="Product preview"
        className="motion-ssr mx-auto mt-16 max-w-6xl md:mt-20"
      >
        <p className="mb-4 text-center text-xs text-subtle">Live preview with demo data. Switch views or open an issue.</p>
        <AppPreview />
      </motion.div>
    </section>
  );
}

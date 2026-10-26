'use client';

import { motion, useReducedMotion, useScroll, useTransform } from 'motion/react';
import { useRef } from 'react';
import { JtracApp } from './product/JtracApp';

/** The product replica in a browser-style frame, settling into place as it scrolls in. */
export function AppPreview() {
  const ref = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();
  const { scrollYProgress } = useScroll({ target: ref, offset: ['start end', 'center center'] });
  const rotateX = useTransform(scrollYProgress, [0, 1], [8, 0]);
  const scale = useTransform(scrollYProgress, [0, 1], [0.95, 1]);
  const y = useTransform(scrollYProgress, [0, 1], [48, 0]);

  return (
    <div ref={ref} className="relative [perspective:1600px]">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-x-[8%] -top-16 bottom-0 -z-10 bg-[radial-gradient(60%_55%_at_50%_30%,color-mix(in_srgb,var(--color-accent)_22%,transparent),transparent_70%)] blur-2xl"
      />
      <motion.figure
        aria-label="Interactive replica of the JTRAC web app with demo data"
        data-nosnippet
        style={reduce ? undefined : { rotateX, scale, y }}
        className="relative overflow-hidden rounded-(--radius-shape) border border-line-strong bg-canvas shadow-[0_40px_120px_-40px_rgb(0_0_0/0.9),var(--shadow-overlay)] [transform-origin:50%_0%]"
      >
        <div className="flex items-center gap-3 border-b border-line bg-ink px-4 py-2" aria-hidden="true">
          <div className="flex gap-1.5">
            <span className="size-2.5 rounded-full bg-line-strong" />
            <span className="size-2.5 rounded-full bg-line-strong" />
            <span className="size-2.5 rounded-full bg-line-strong" />
          </div>
          <p className="mx-auto min-w-0 truncate rounded-(--radius-sm) bg-panel px-3 py-0.5 font-mono text-[0.6875rem] text-subtle">
            your-jtrac-server/demo/p/JTRAC
          </p>
          <span className="w-[2.6rem]" />
        </div>
        <JtracApp />
      </motion.figure>
    </div>
  );
}

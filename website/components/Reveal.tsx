'use client';

import { motion, MotionConfig, type Variants } from 'motion/react';
import type { ReactNode } from 'react';
import landing from './landing.module.css';

export const ease = [0.2, 0.8, 0.2, 1] as const;

/**
 * Honors prefers-reduced-motion for every motion component on the page: transforms are
 * applied instantly and only opacity animates.
 */
export function MotionProvider({ children }: { children: ReactNode }) {
  return <MotionConfig reducedMotion="user">{children}</MotionConfig>;
}

type From = 'up' | 'left' | 'right' | 'scale';

const offsets: Record<From, { x?: number; y?: number; scale?: number }> = {
  up: { y: 24 },
  left: { x: -56 },
  right: { x: 56 },
  scale: { scale: 0.94, y: 16 },
};

interface RevealProps {
  children: ReactNode;
  delay?: number;
  className?: string;
  as?: 'div' | 'li' | 'article';
  /** Direction the content enters from. */
  from?: From;
}

/** Brings content in once when it scrolls into view. Transform-only, so no layout shift. */
export function Reveal({ children, delay = 0, className, as = 'div', from = 'up' }: RevealProps) {
  const Component = motion[as];
  return (
    <Component
      className={`motion-ssr ${className ?? ''}`}
      initial={{ opacity: 0, ...offsets[from] }}
      whileInView={{ opacity: 1, x: 0, y: 0, scale: 1 }}
      viewport={{ once: true, margin: '0px 0px -12% 0px' }}
      transition={{ duration: from === 'up' ? 0.7 : 0.9, delay, ease }}
    >
      {children}
    </Component>
  );
}

interface WordsProps {
  text: string;
  className?: string;
  /** Classes for each word (gradient text must live on the word, not the parent). */
  wordClassName?: string;
  delay?: number;
  stagger?: number;
  /** Animate when scrolled into view instead of on load. */
  inView?: boolean;
}

const word: Variants = {
  hidden: { y: '115%' },
  show: { y: '0%', transition: { duration: 0.85, ease } },
};

/**
 * Masked word reveal: each word rises from behind its own clipping line. The words are
 * hidden from assistive technology; the caller supplies the accessible text.
 */
export function Words({ text, className, wordClassName = '', delay = 0, stagger = 0.07, inView = false }: WordsProps) {
  const parent: Variants = { hidden: {}, show: { transition: { staggerChildren: stagger, delayChildren: delay } } };
  const trigger = inView ? { whileInView: 'show', viewport: { once: true, margin: '0px 0px -10% 0px' } } : { animate: 'show' };
  const words = text.split(' ');
  return (
    <motion.span aria-hidden="true" className={className} variants={parent} initial="hidden" {...trigger}>
      {words.map((w, i) => (
        <span key={`${w}-${String(i)}`}>
          <span className="-mb-[0.14em] inline-block overflow-hidden pb-[0.14em] align-top">
            <motion.span variants={word} className={`motion-ssr inline-block ${wordClassName}`}>
              {w}
            </motion.span>
          </span>
          {i < words.length - 1 ? ' ' : null}
        </span>
      ))}
    </motion.span>
  );
}

/** The two speed strokes, drawn in from the start like a stroke of the logo. */
export function SpeedMark({ centered = false, delay = 0, inView = true, className = '' }: { centered?: boolean; delay?: number; inView?: boolean; className?: string }) {
  const trigger = inView ? { whileInView: { scaleX: 1, opacity: 1 }, viewport: { once: true } } : { animate: { scaleX: 1, opacity: 1 } };
  return (
    <motion.span
      aria-hidden="true"
      className={`motion-ssr ${landing.speed} ${centered ? landing.speedCenter : ''} ${className}`}
      // Motion owns the transform, so the stroke's slant (--slant-mark, -12deg) is set here too.
      style={{ originX: 0, skewX: -12 }}
      initial={{ scaleX: 0, opacity: 0 }}
      {...trigger}
      transition={{ duration: 0.7, delay, ease }}
    />
  );
}

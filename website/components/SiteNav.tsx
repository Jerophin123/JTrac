'use client';

import { FolderGit2, Menu, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useId, useState } from 'react';
import { links, sections } from '@/lib/site';
import landing from './landing.module.css';
import { Logo } from './Logo';
import buttonStyles from './product/styles/Button.module.css';
import { DownloadButton } from './DownloadButton';
import { ButtonLink } from './ui';

export function SiteNav() {
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const [active, setActive] = useState<string | null>(null);
  const menuId = useId();

  // Glass background once scrolled, and scroll-spy: the active item is the last section whose
  // top has passed the bottom of the nav (the same offset in-page links scroll to).
  useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      setScrolled(window.scrollY > 12);
      const probe = parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop) + 8 || 104;
      let current: string | null = null;
      for (const { id } of sections) {
        const el = document.getElementById(id);
        if (el && el.getBoundingClientRect().top <= probe) current = id;
      }
      setActive(current);
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
    };
  }, []);

  // In-page jumps (menu links, the download button) close the mobile menu.
  useEffect(() => {
    const close = () => setOpen(false);
    window.addEventListener('hashchange', close);
    return () => window.removeEventListener('hashchange', close);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <header className="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6">
      <nav
        aria-label="Primary"
        className={`mx-auto flex max-w-6xl items-center gap-3 rounded-(--radius-shape) px-3 py-2 transition-[background-color,box-shadow,border-color] duration-300 sm:px-4 ${
          scrolled || open ? 'glass' : 'border border-transparent'
        }`}
      >
        <a href="#top" className="flex items-center rounded-lg px-1.5 py-1.5" aria-label="JTRAC home">
          <Logo height={20} aria-hidden="true" role="presentation" />
        </a>

        <ul className="ms-6 hidden items-center gap-1 lg:flex">
          {sections.map((s) => (
            <li key={s.id}>
              <a
                href={`#${s.id}`}
                aria-current={active === s.id ? 'true' : undefined}
                className={landing.navItem}
              >
                {s.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="ms-auto hidden items-center gap-2 lg:flex">
          <ButtonLink href={links.repo} variant="ghost" size="sm" icon={<FolderGit2 size={16} aria-hidden="true" />}>
            View on GitHub
          </ButtonLink>
          <DownloadButton size="sm" />
        </div>

        {/* The wrapper owns visibility: the module's display rule would override lg:hidden. */}
        <div className="ms-auto lg:hidden">
          <button
            type="button"
            className={`${buttonStyles.button} ${buttonStyles.ghost} ${buttonStyles.md}`}
            aria-expanded={open}
            aria-controls={menuId}
            onClick={() => setOpen((o) => !o)}
          >
            {open ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
            <span className="sr-only">{open ? 'Close menu' : 'Open menu'}</span>
          </button>
        </div>
      </nav>

      <AnimatePresence>
        {open ? (
          <motion.div
            id={menuId}
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={{ duration: 0.2 }}
            className="panel mx-auto mt-2 max-w-6xl rounded-(--radius-shape) p-3 shadow-(--shadow-overlay) lg:hidden"
          >
            <ul className="grid gap-1">
              {sections.map((s) => (
                <li key={s.id}>
                  <a
                    href={`#${s.id}`}
                    onClick={() => setOpen(false)}
                    aria-current={active === s.id ? 'true' : undefined}
                    className={`${landing.navItem} ${landing.navItemBlock}`}
                  >
                    {s.label}
                  </a>
                </li>
              ))}
            </ul>
            <div className="mt-3 grid gap-2 border-t border-line pt-3">
              <DownloadButton />
              <ButtonLink href={links.repo} variant="secondary" icon={<FolderGit2 size={16} aria-hidden="true" />}>
                View on GitHub
              </ButtonLink>
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </header>
  );
}

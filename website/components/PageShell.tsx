import type { ReactNode } from 'react';
import { FinalCta } from './FinalCta';
import { SiteFooter } from './SiteFooter';
import { SiteNav } from './SiteNav';

/** Chrome shared by every page: skip link, navigation, main landmark and footer. */
export function PageShell({ children, cta = true }: { children: ReactNode; cta?: boolean }) {
  return (
    <>
      <a
        href="#main"
        className="fixed start-4 top-4 z-[70] -translate-y-24 rounded-full bg-brand px-4 py-2 text-sm font-medium text-panel focus:translate-y-0"
      >
        Skip to content
      </a>
      <SiteNav />
      <main id="main" tabIndex={-1} className="outline-none">
        {children}
        {cta ? <FinalCta /> : null}
      </main>
      <SiteFooter />
    </>
  );
}

import { ClassicFeatures } from '@/components/ClassicFeatures';
import { Docs } from '@/components/Docs';
import { Downloads } from '@/components/Downloads';
import { Experience } from '@/components/Experience';
import { Faq } from '@/components/Faq';
import { Features } from '@/components/Features';
import { FinalCta } from '@/components/FinalCta';
import { Hero } from '@/components/Hero';
import { OpenSource } from '@/components/OpenSource';
import { SiteFooter } from '@/components/SiteFooter';
import { SiteNav } from '@/components/SiteNav';
import { Technology } from '@/components/Technology';

export default function LandingPage() {
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
        <Hero />
        <Features />
        <ClassicFeatures />
        <Experience />
        <Technology />
        <Downloads />
        <Docs />
        <Faq />
        <OpenSource />
        <FinalCta />
      </main>
      <SiteFooter />
    </>
  );
}

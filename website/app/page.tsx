import { ClassicFeatures } from '@/components/ClassicFeatures';
import { Docs } from '@/components/Docs';
import { Downloads } from '@/components/Downloads';
import { Experience } from '@/components/Experience';
import { Faq } from '@/components/Faq';
import { Features } from '@/components/Features';
import { JsonLd } from '@/components/JsonLd';
import { Hero } from '@/components/Hero';
import { OpenSource } from '@/components/OpenSource';
import { PageShell } from '@/components/PageShell';
import { Technology } from '@/components/Technology';
import { faq } from '@/lib/faq';
import { buildMetadata, structuredData } from '@/lib/seo';

export const metadata = buildMetadata();

export default function LandingPage() {
  return (
    <>
      {/* Home page only for the FAQPage: the FAQ it describes must be visible where it is marked up. */}
      <JsonLd data={structuredData(faq)} />
      <PageShell>
        <Hero />
        <Features />
        <ClassicFeatures />
        <Experience />
        <Technology />
        <Downloads />
        <Docs />
        <Faq />
        <OpenSource />
      </PageShell>
    </>
  );
}

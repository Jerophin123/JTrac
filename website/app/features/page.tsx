import { ClassicFeatures } from '@/components/ClassicFeatures';
import { Experience } from '@/components/Experience';
import { Features } from '@/components/Features';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('features/');

export const metadata = buildMetadata(page);

export default function FeaturesPage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <Features />
      <ClassicFeatures />
      <Experience />
    </PageShell>
  );
}

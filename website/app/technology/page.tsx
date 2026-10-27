import { Technology } from '@/components/Technology';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('technology/');

export const metadata = buildMetadata(page);

export default function TechnologyPage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <Technology />
    </PageShell>
  );
}

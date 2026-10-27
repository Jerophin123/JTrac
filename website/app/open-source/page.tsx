import { OpenSource } from '@/components/OpenSource';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('open-source/');

export const metadata = buildMetadata(page);

export default function OpenSourcePage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <OpenSource />
    </PageShell>
  );
}

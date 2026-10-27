import { Docs } from '@/components/Docs';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('docs/');

export const metadata = buildMetadata(page);

export default function DocsPage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <Docs />
    </PageShell>
  );
}

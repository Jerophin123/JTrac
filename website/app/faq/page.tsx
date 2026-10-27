import { Faq } from '@/components/Faq';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('faq/');

export const metadata = buildMetadata(page);

export default function FaqPage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <Faq />
    </PageShell>
  );
}

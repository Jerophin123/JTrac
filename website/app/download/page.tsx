import { Downloads } from '@/components/Downloads';
import { JsonLd } from '@/components/JsonLd';
import { PageIntro } from '@/components/PageIntro';
import { PageShell } from '@/components/PageShell';
import { getPage } from '@/lib/pages';
import { buildMetadata, pageStructuredData } from '@/lib/seo';

const page = getPage('download/');

export const metadata = buildMetadata(page);

export default function DownloadPage() {
  return (
    <PageShell>
      <JsonLd data={pageStructuredData(page)} />
      <PageIntro page={page} />
      <Downloads />
    </PageShell>
  );
}

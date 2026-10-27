import { jsonLd } from '@/lib/seo';

/** schema.org structured data: static, build-time JSON with "<" escaped (see jsonLd). */
export function JsonLd({ data }: { data: unknown }) {
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: jsonLd(data) }} />;
}

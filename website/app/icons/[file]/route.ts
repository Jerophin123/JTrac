import { markIcon } from '@/lib/mark-icon';
import { faviconSizes } from '@/lib/seo';

export const dynamic = 'force-static';
export const dynamicParams = false;

/** /icons/icon-48.png ... /icons/icon-512.png: raster favicons for search results and the manifest. */
export function generateStaticParams() {
  return faviconSizes.map((size) => ({ file: `icon-${String(size)}.png` }));
}

export async function GET(_request: Request, { params }: { params: Promise<{ file: string }> }) {
  const { file } = await params;
  return markIcon(Number(/^icon-(\d+)\.png$/.exec(file)?.[1]));
}

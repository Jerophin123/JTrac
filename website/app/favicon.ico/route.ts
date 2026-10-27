import { markIcon } from '@/lib/mark-icon';

export const dynamic = 'force-static';

/**
 * /favicon.ico, which crawlers (Google's favicon fetcher among them) request directly.
 * An ICO file may hold a PNG image, so the 48x48 render is wrapped in a one-entry ICO header.
 */
export async function GET() {
  const png = Buffer.from(await markIcon(48).arrayBuffer());
  const header = Buffer.alloc(22);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // type: icon
  header.writeUInt16LE(1, 4); // image count
  header.writeUInt8(48, 6); // width
  header.writeUInt8(48, 7); // height
  header.writeUInt8(0, 8); // palette size
  header.writeUInt8(0, 9); // reserved
  header.writeUInt16LE(1, 10); // color planes
  header.writeUInt16LE(32, 12); // bits per pixel
  header.writeUInt32LE(png.length, 14); // image size
  header.writeUInt32LE(22, 18); // image offset
  return new Response(Buffer.concat([header, png]), { headers: { 'Content-Type': 'image/x-icon' } });
}

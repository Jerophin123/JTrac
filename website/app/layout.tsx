import '@fontsource-variable/ibm-plex-sans';
import '@fontsource/ibm-plex-mono/400.css';
import './product-tokens.css';
import './globals.css';

import type { Viewport } from 'next';
import type { ReactNode } from 'react';
import { MotionProvider } from '@/components/Reveal';
import { faq } from '@/lib/faq';
import { buildMetadata, jsonLd, structuredData } from '@/lib/seo';

export const metadata = buildMetadata();

export const viewport: Viewport = {
  themeColor: '#110f0d',
  colorScheme: 'dark',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" dir="ltr">
      <body>
        {/* Without JavaScript, show content that would otherwise wait for its entrance animation. */}
        <noscript>
          <style>{'.motion-ssr{opacity:1!important;transform:none!important;filter:none!important}'}</style>
        </noscript>
        <script
          type="application/ld+json"
          // Static, build-time JSON with "<" escaped (see jsonLd).
          dangerouslySetInnerHTML={{ __html: jsonLd(structuredData(faq)) }}
        />
        <MotionProvider>{children}</MotionProvider>
      </body>
    </html>
  );
}

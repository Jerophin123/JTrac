import '@fontsource-variable/ibm-plex-sans';
import '@fontsource/ibm-plex-mono/400.css';
import './product-tokens.css';
import './globals.css';

import type { Viewport } from 'next';
import type { ReactNode } from 'react';
import { GoogleAnalytics } from '@/components/GoogleAnalytics';
import { MotionProvider } from '@/components/Reveal';
import { buildMetadata } from '@/lib/seo';
import { gaMeasurementId } from '@/lib/site';

// Defaults for routes without their own metadata (the 404 page): no canonical or index
// directive, so a missing URL never claims to be the home page (Next adds noindex). Every
// real page exports buildMetadata itself.
export const metadata = { ...buildMetadata(), alternates: null, robots: null };

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
        <MotionProvider>{children}</MotionProvider>
        {gaMeasurementId ? <GoogleAnalytics id={gaMeasurementId} /> : null}
      </body>
    </html>
  );
}

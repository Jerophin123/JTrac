import type { NextConfig } from 'next';

// The landing page is a fully static export (out/): no server, API routes or runtime config.
const nextConfig: NextConfig = {
  output: 'export',
  trailingSlash: true,
  images: { unoptimized: true },
  poweredByHeader: false,
  reactStrictMode: true,
  devIndicators: false,
};

export default nextConfig;

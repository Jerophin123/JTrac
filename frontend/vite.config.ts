import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const backend = process.env.JTRAC_BACKEND_URL ?? process.env.JTAC_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Same-origin in development: the browser only talks to Vite, which proxies the API.
    proxy: {
      '/api': backend,
      '/actuator': backend,
    },
  },
  build: {
    outDir: 'dist',
    target: 'es2023',
    // Production builds ship no source maps, so the browser can't rebuild the original
    // source tree (ADR 0007). The release gate rejects any that slip into a package.
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
});

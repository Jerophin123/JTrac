import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end smoke tests against a running JTAC server that serves the built SPA and the
 * API from one origin (./gradlew :backend:bootJar -PwithFrontend). See README.md → "E2E".
 *
 * Uses the locally installed Microsoft Edge (channel "msedge") so no browser download is
 * needed; override with JTAC_E2E_CHANNEL (e.g. "chrome").
 */
const baseURL = process.env.JTRAC_E2E_BASE_URL ?? process.env.JTAC_E2E_BASE_URL ?? 'http://localhost:8080';
const channel = process.env.JTRAC_E2E_CHANNEL ?? process.env.JTAC_E2E_CHANNEL ?? 'msedge';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL,
    channel,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Edge'], channel, viewport: { width: 1280, height: 800 } } },
    { name: 'mobile', use: { channel, viewport: { width: 390, height: 844 }, hasTouch: true } },
  ],
});

import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * Credentials for the successful-login journey come from the environment (never from
 * files). Create the account first, e.g. `./gradlew :backend:createUser` with
 * JTAC_USER_EMAIL / JTAC_USER_PASSWORD set (see README.md).
 */
const email = process.env.JTRAC_E2E_EMAIL ?? process.env.JTAC_E2E_EMAIL;
const password = process.env.JTRAC_E2E_PASSWORD ?? process.env.JTAC_E2E_PASSWORD;

// Every journey must run without Content-Security-Policy violations.
const cspViolations: string[] = [];
test.beforeEach(({ page }) => {
  cspViolations.length = 0;
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy')) {
      cspViolations.push(message.text());
    }
  });
});
test.afterEach(() => {
  expect(cspViolations).toEqual([]);
});

async function expectNoSeriousA11yViolations(page: Page) {
  // Measure what people see: entrance animations fade content in, and mid-fade colors are
  // blended with the background, which would be reported as low contrast.
  await page.evaluate(() => Promise.all(document.getAnimations().map((a) => a.finished.catch(() => undefined))));
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  const report = serious.flatMap((v) =>
    v.nodes.map((node) => `${v.id}: ${node.target.join(' ')}: ${node.failureSummary?.split('\n').at(-1)?.trim() ?? ''}`),
  );
  expect(report).toEqual([]);
}

test('a visitor is sent to the sign-in page, which is accessible and CSP-protected', async ({ page }) => {
  const response = await page.goto('/');
  expect(response?.headers()['content-security-policy']).toContain("script-src 'self'");

  await expect(page).toHaveURL(/\/login(\?|$)/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await expectNoSeriousA11yViolations(page);
});

test('wrong credentials are rejected by the real backend with a clear message', async ({ page }) => {
  await page.goto('/login');
  // A unique address per run, so repeated runs never trip the sign-in throttle.
  await page.getByLabel('Email').fill(`nobody-${Date.now()}-${Math.floor(Math.random() * 1e6)}@jtac.test`);
  await page.getByLabel('Password', { exact: true }).fill('definitely-not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('alert')).toHaveText('That email and password combination is incorrect.');
  await expect(page).toHaveURL(/\/login/);
});

test('sign in, use the shell, and sign out', async ({ page, context }, testInfo) => {
  test.skip(!email || !password, 'Set JTAC_E2E_EMAIL and JTAC_E2E_PASSWORD to run the authenticated journey.');

  await page.goto('/');
  await page.getByLabel('Email').fill(email ?? '');
  await page.getByLabel('Password', { exact: true }).fill(password ?? '');
  await page.getByRole('button', { name: 'Sign in' }).click();

  const heading = page.getByRole('heading', { level: 1 });
  await expect(heading).not.toHaveText('Sign in');
  // First sign-in after the terms changed (or for a new account): accept them explicitly.
  if (await page.getByRole('heading', { name: 'Review our terms' }).isVisible()) {
    await expectNoSeriousA11yViolations(page);
    await page.getByLabel(/I have read and agree/).check();
    await page.getByRole('button', { name: 'Accept and continue' }).click();
    await expect(page.getByRole('heading', { name: 'Review our terms' })).toBeHidden();
  }
  if (await page.getByRole('heading', { name: 'Welcome to JTRAC' }).isVisible()) {
    await page.getByLabel('Name').fill('Acme Corp');
    await page.getByRole('button', { name: 'Create organization' }).click();
  }
  await expect(heading).toContainText(/Good (morning|afternoon|evening)/);
  await expect(page.getByRole('heading', { name: /Nothing assigned to you/ })).toBeVisible();

  // Session credentials live only in an HttpOnly cookie, never in web storage.
  const session = (await context.cookies()).find((c) => c.name === 'JTRAC_SESSION' || c.name === 'JTAC_SESSION');
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe('Lax');
  const storageKeys = await page.evaluate(() => [...Object.keys(localStorage), ...Object.keys(sessionStorage)]);
  expect(storageKeys.filter((key) => key !== 'jtrac.theme' && key !== 'jtrac.lastOrg' && key !== 'jtac.theme' && key !== 'jtac.lastOrg')).toEqual([]);

  const isMobile = testInfo.project.name === 'mobile';
  if (isMobile) {
    const openNav = page.getByRole('button', { name: 'Open navigation' });
    await openNav.click();
    await expect(page.getByRole('navigation', { name: 'Primary' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(openNav).toBeFocused();
    await openNav.click();
  }

  const sidebar = page.getByRole('complementary', { name: 'Sidebar' });
  await expect(sidebar.locator('button').filter({ hasText: '@' })).toBeVisible();
  // The service (API and database) reports ready.
  const readiness = await page.request.get('/actuator/health/readiness');
  expect(readiness.status()).toBe(200);
  expect(await readiness.text()).toContain('UP');
  await expectNoSeriousA11yViolations(page);

  // Dark theme must also meet contrast requirements.
  // The theme is chosen in account settings (Appearance), reached from the user menu.
  await sidebar.locator('button').filter({ hasText: '@' }).click();
  await page.getByRole('menuitem', { name: 'Appearance' }).click();
  await page.getByRole('radio', { name: /Dark/ }).check();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await expectNoSeriousA11yViolations(page);
  if (isMobile) {
    // Choosing a menu item closes the mobile navigation; reopen it to reach the user menu.
    await page.getByRole('button', { name: 'Open navigation' }).click();
  }

  await sidebar.locator('button').filter({ hasText: '@' }).click();
  await page.getByRole('menuitem', { name: 'Sign out' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'You’re signed out.' })).toBeVisible();

  // The old session is gone: protected pages redirect to sign-in again.
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
});

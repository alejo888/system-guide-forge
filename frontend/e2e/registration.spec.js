const { test, expect } = require('@playwright/test');

const fixtureUrl = (process.env.SGF_FIXTURE_URL || 'http://127.0.0.1:4173').replace(/\/$/, '');
const fixtureUsername = process.env.FIXTURE_USERNAME || 'fixture-user';
const fixturePassword = process.env.FIXTURE_PASSWORD || 'fixture-password';

test('registers and analyzes the deterministic fixture through the browser', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Good morning, builder.' })).toBeVisible();
    await page.getByRole('link', { name: 'Register system' }).first().click();

    await page.getByLabel('Project name').fill('Browser E2E fixture');
    await page.getByLabel('System name').fill('Deterministic fixture');
    await page.getByLabel('Base URL').fill(fixtureUrl);
    await page.getByLabel('Login URL').fill(`${fixtureUrl}/login.html`);
    await page.getByLabel('Username').fill(fixtureUsername);
    await page.getByLabel('Password', { exact: true }).fill(fixturePassword);
    await page.getByLabel('Maximum crawl depth').fill('1');
    await page.getByRole('button', { name: 'Register & test access' }).click();

    await expect(page.getByRole('heading', { name: 'Access verified.' })).toBeVisible();
    await page.getByRole('button', { name: 'Start analysis' }).click();

    await expect(page).toHaveURL(/\/analysis\/[^/]+$/);
    await expect(page.getByRole('heading', { name: 'Exploration complete.' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Pages and evidence' })).toBeVisible();

    const evidenceArticles = page.getByRole('article');
    const loginEvidence = evidenceArticles.filter({
      has: page.getByRole('heading', { name: 'SystemGuideForge Fixture Login' })
    });
    await expect(loginEvidence).toBeVisible();
    await expect(loginEvidence.getByText('Sign-in screen', { exact: true })).toBeVisible();
    await expect(evidenceArticles.first()).toContainText('Sign-in screen');

    const dashboardEvidence = page.getByRole('article').filter({
      has: page.getByRole('heading', { name: 'SystemGuideForge Fixture Dashboard' })
    });
    await expect(dashboardEvidence).toBeVisible();
    await dashboardEvidence.getByText('View evidence and technical details', { exact: true }).click();
    await expect(dashboardEvidence.getByText('More options', { exact: true })).toBeVisible();

    const reportsEvidence = page.getByRole('article').filter({
      has: page.getByRole('heading', { name: 'SystemGuideForge Fixture Reports' })
    });
    await expect(reportsEvidence).toBeVisible();
    await reportsEvidence.getByText('View evidence and technical details', { exact: true }).click();
    await expect(reportsEvidence.getByText('Preview report', { exact: true })).toBeVisible();
  } finally {
    await context.close();
  }
});

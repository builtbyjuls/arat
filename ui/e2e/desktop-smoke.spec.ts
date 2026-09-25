import { expect, test } from '@playwright/test';
import { createGroup, createPlan, expectAxePasses, expectNoHorizontalOverflow, selectActor } from './support';

test('desktop Chromium renders the journey primary pages', async ({ page }) => {
  const group = await createGroup(page);
  await createPlan(page, group.id);
  const planPath = new URL(page.url()).pathname;

  await selectActor(page, 'dani');
  await page.goto('/providers');
  const providerName = `Desktop provider ${crypto.randomUUID().slice(0, 8)}`;
  await page.getByLabel('Display name').fill(providerName);
  await page.getByLabel('COURT', { exact: true }).check();
  await page.getByLabel('Service area codes').fill('BGC');
  await page.getByRole('button', { name: 'Create provider organization' }).click();
  await expect(page.getByRole('heading', { level: 1, name: providerName })).toBeVisible();
  const providerPath = new URL(page.url()).pathname;

  await page.getByLabel('Evidence reference 1').fill(`registry:desktop-${crypto.randomUUID()}`);
  await page.getByRole('button', { name: 'Submit for verification review' }).click();
  await expect(page.getByText('Pending review (PENDING)')).toBeVisible();

  await selectActor(page, 'owen');
  await page.goto('/operations/provider-verifications');
  const providerReview = page.getByRole('article', { name: providerName });
  await providerReview.getByRole('button', { name: 'Review acceptance' }).click();
  const decision = providerReview.getByRole('alertdialog');
  await decision.getByRole('button', { name: 'Confirm accept' }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Decision recorded' })).toBeVisible();

  await selectActor(page, 'ari');
  await page.goto(planPath);
  await page.getByRole('radio').check();
  const offerDeadline = new Date(Date.now() + 23 * 24 * 60 * 60 * 1000);
  offerDeadline.setUTCHours(18, 0, 0, 0);
  await page.getByLabel('Offer deadline').fill(offerDeadline.toISOString().slice(0, 16));
  await page.getByRole('button', { name: 'Finalize requirements' }).click();
  await expect(page.getByRole('heading', { level: 4, name: 'Immutable finalization command result' })).toBeVisible();
  await page.getByRole('button', { name: /Review publication of finalization/ }).click();
  await page.getByRole('button', { name: 'Confirm and publish request' }).click();
  await expect(page.getByRole('heading', { level: 4, name: 'Current version 1' })).toBeVisible();
  const requestId = new URL(
    await page.getByRole('link', { name: 'Read version 1' }).getAttribute('href') as string,
    'http://browser.test',
  ).searchParams.get('requestId');
  expect(requestId).toMatch(/^[0-9a-f-]+$/);
  const requiredRequestId = requestId as string;

  const requestFeedPath = `${providerPath}/requests`;
  const requestDetailPath = `${requestFeedPath}?requestId=${requiredRequestId}`;

  const pages = [
    { actor: 'ari' as const, heading: group.name, name: 'group detail', path: `/groups/${group.id}` },
    { actor: 'ari' as const, heading: 'Browser foundation plan', name: 'published plan workspace', path: planPath },
    { actor: 'dani' as const, heading: providerName, name: 'provider detail', path: providerPath },
    { actor: 'dani' as const, heading: 'Your provider workspaces', name: 'provider index', path: '/providers' },
    { actor: 'dani' as const, heading: 'Provider requests', name: 'provider request feed', path: requestFeedPath },
    { actor: 'dani' as const, heading: 'Provider requests', name: 'provider request detail', path: requestDetailPath },
  ];

  for (const primaryPage of pages) {
    await selectActor(page, primaryPage.actor);
    await page.goto(primaryPage.path);
    await expect(page.getByRole('heading', { level: 1, name: primaryPage.heading })).toBeVisible();
    if (primaryPage.name === 'published plan workspace') {
      await expect(page.getByRole('heading', { level: 4, name: 'Current version 1' })).toBeVisible();
    }
    if (primaryPage.name === 'provider request feed') {
      const requestLink = page.getByRole('listitem').filter({ hasText: 'COURT' }).getByRole('link');
      await expect(requestLink).toHaveAttribute('href', new RegExp(`requestId=${requiredRequestId}$`));
    }
    if (primaryPage.name === 'provider request detail') {
      await expect(page.getByRole('heading', { level: 2, name: 'Provider request details' })).toBeVisible();
    }
    await expectAxePasses(page, `desktop ${primaryPage.name}`);
    await expectNoHorizontalOverflow(page, `desktop ${primaryPage.name}`);
  }
});

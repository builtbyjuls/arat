import { expect, test } from '@playwright/test';
import { expectAxePasses, expectNoHorizontalOverflow, selectActor, visitAsAri } from './support';

test('shell has one main landmark and accessible navigation', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('main')).toHaveCount(1);
  await expect(page.getByRole('navigation', { name: 'Primary navigation' })).toBeVisible();
  await expectAxePasses(page, 'shell');
  await expectNoHorizontalOverflow(page, 'shell');
});

test('a private deep link resolves to the safe unavailable state', async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem('arat.local-demo.selected-persona', 'ari');
  });
  await page.goto('/groups/00000000-0000-4000-8000-000000000099');

  await expect(page.getByRole('heading', { level: 1, name: 'Group unavailable' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('This group is unavailable.');
  await expectAxePasses(page, 'error pattern');
});

test('actor switching clears the current private route', async ({ page }) => {
  await page.goto('/');
  await selectActor(page, 'ari');
  await page.getByRole('link', { name: 'Groups' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Your groups' })).toBeFocused();

  await selectActor(page, 'bea');
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('Local actor verified: Bea Member')).toBeVisible();
  await expect(page.getByRole('heading', { level: 1, name: 'Welcome to Arat' })).toBeVisible();
});

test('keyboard skip navigation moves focus to the main landmark', async ({ page }) => {
  await visitAsAri(page, '/groups');
  await page.getByRole('link', { name: 'Skip to main content' }).focus();
  await page.keyboard.press('Enter');

  await expect(page.getByRole('main')).toBeFocused();
});

test('the group form exposes validation feedback accessibly', async ({ page }) => {
  await visitAsAri(page, '/groups');
  await expect(page.getByRole('heading', { level: 2, name: 'No private groups yet' })).toBeVisible();
  await expectAxePasses(page, 'empty pattern');
  await page.getByRole('button', { name: 'Create group' }).click();

  await expect(page.getByText('Enter a group name of at most 80 characters.')).toBeVisible();
  await expectAxePasses(page, 'form pattern');
});

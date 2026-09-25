import { expect, test } from '@playwright/test';
import { createGroup, createPlan, expectAxePasses, expectNoHorizontalOverflow, visitAsAri } from './support';

const viewports = [
  { name: '320 CSS pixels', width: 320 },
  { name: '360 CSS pixels', width: 360 },
  { name: '390 CSS pixels', width: 390 },
] as const;

test('representative patterns have no horizontal overflow at bounded mobile widths', async ({ page }) => {
  const group = await createGroup(page);
  await expectAxePasses(page, 'detail pattern');
  await createPlan(page, group.id);

  const patterns = [
    { name: 'shell', path: '/' },
    { name: 'list', path: '/groups' },
    { name: 'form', path: `/groups/${group.id}/plans` },
    { name: 'detail', path: `/groups/${group.id}` },
    { name: 'confirmation', path: page.url(), confirm: true },
    { name: 'error', path: '/plans/00000000-0000-4000-8000-000000000099' },
  ] as const;

  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: 844 });
    for (const pattern of patterns) {
      await visitAsAri(page, pattern.path);
      switch (pattern.name) {
        case 'shell':
          await expect(page.getByRole('heading', { level: 1, name: 'Welcome to Arat' })).toBeVisible();
          break;
        case 'list':
          await expect(page.getByRole('link', { name: group.name })).toBeVisible();
          break;
        case 'form':
          await expect(page.getByRole('heading', { level: 2, name: 'Create a collaborative plan' })).toBeVisible();
          break;
        case 'detail':
          await expect(page.getByRole('heading', { level: 1, name: group.name })).toBeVisible();
          break;
        case 'confirmation':
          await expect(page.getByRole('heading', { level: 1, name: 'Browser foundation plan' })).toBeVisible();
          break;
        case 'error':
          await expect(page.getByRole('heading', { level: 1, name: 'Plan unavailable' })).toBeVisible();
          await expect(page.getByRole('alert')).toContainText('This plan is unavailable.');
          break;
      }
      if (pattern.confirm) {
        await page.getByRole('button', { name: 'Review plan cancellation' }).click();
        await expect(page.getByRole('alertdialog')).toBeVisible();
        await expect(page.getByRole('button', { name: 'Confirm plan cancellation' })).toBeFocused();
        await expectAxePasses(page, 'confirmation pattern');
        await expectNoHorizontalOverflow(page, `${pattern.name} at ${viewport.name}`);
        await page.getByRole('button', { name: 'Keep plan active' }).click();
        continue;
      }
      await expectNoHorizontalOverflow(page, `${pattern.name} at ${viewport.name}`);
    }
  }
});

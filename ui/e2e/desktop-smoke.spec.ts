import { expect, test } from '@playwright/test';
import { expectAxePasses, expectNoHorizontalOverflow, visitAsAri } from './support';

test('desktop Chromium renders the same-origin group workspace', async ({ page }) => {
  await visitAsAri(page, '/groups');

  await expect(page.getByRole('heading', { level: 1, name: 'Your groups' })).toBeVisible();
  await expectAxePasses(page, 'desktop group workspace');
  await expectNoHorizontalOverflow(page, 'desktop group workspace');
});

import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

export type LocalActor = 'ari' | 'bea' | 'cruz' | 'dani' | 'owen';

const actorNames: Record<LocalActor, string> = {
  ari: 'Ari Organizer',
  bea: 'Bea Member',
  cruz: 'Cruz Outsider',
  dani: 'Dani Provider',
  owen: 'Owen Operator',
};

export async function selectActor(page: Page, actor: LocalActor): Promise<void> {
  await page.locator('#local-actor').selectOption(actor);
  await expect(page.getByText(`Local actor verified: ${actorNames[actor]}`)).toBeVisible();
}

export async function visitAsAri(page: Page, path: string): Promise<void> {
  await page.goto('/');
  await selectActor(page, 'ari');
  await page.goto(path);
}

export async function expectNoHorizontalOverflow(page: Page, pattern: string): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));

  expect(dimensions.scrollWidth, `${pattern} must not overflow horizontally`).toBeLessThanOrEqual(dimensions.clientWidth);
}

export async function expectAxePasses(page: Page, pattern: string): Promise<void> {
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations, `${pattern} must have no axe violations`).toEqual([]);
}

export interface BrowserGroup {
  readonly id: string;
  readonly name: string;
}

export async function createGroup(page: Page): Promise<BrowserGroup> {
  await visitAsAri(page, '/groups');
  const groupName = 'Browser foundation group';
  await page.getByLabel('Group name').fill(groupName);
  await page.getByRole('button', { name: 'Create group' }).click();
  await expect(page).toHaveURL(/\/groups\/[0-9a-f-]+$/);
  await expect(page.getByRole('heading', { level: 1, name: groupName })).toBeVisible();
  return {
    id: page.url().split('/').at(-1) as string,
    name: groupName,
  };
}

export async function createPlan(page: Page, groupId: string): Promise<void> {
  const startAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  startAt.setUTCHours(18, 0, 0, 0);
  const endAt = new Date(startAt.getTime() + 2 * 60 * 60 * 1000);
  await page.goto(`/groups/${groupId}/plans`);
  await expect(page.getByRole('heading', { level: 1, name: 'Plans' })).toBeVisible();
  await page.getByLabel('Plan title').fill('Browser foundation plan');
  await page.getByLabel('Starts').fill(startAt.toISOString().slice(0, 16));
  await page.getByLabel('Ends').fill(endAt.toISOString().slice(0, 16));
  await page.getByLabel('Area code').fill('BGC');
  await page.getByRole('button', { name: 'Create plan' }).click();
  await expect(page).toHaveURL(/\/plans\/[0-9a-f-]+$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Browser foundation plan' })).toBeVisible();
}

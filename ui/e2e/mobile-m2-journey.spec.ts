import { expect, test, type APIRequestContext, type Page, type Response } from '@playwright/test';
import { expectAxePasses, expectNoHorizontalOverflow, selectActor } from './support';

const tokens = {
  dani: 'arat-local-provider-token',
  owen: 'arat-local-operator-token',
} as const;

const accountIds = {
  ari: '10000000-0000-4000-8000-000000000001',
  bea: '10000000-0000-4000-8000-000000000002',
} as const;

const forbiddenProviderKeys = new Set([
  'attendance',
  'createdbyaccountid',
  'groupid',
  'groupname',
  'memberids',
  'members',
  'plandid',
  'plantitle',
  'preferences',
  'privatenote',
  'privatenotes',
  'recipientidentities',
  'recipientids',
  'recipients',
]);

test('mobile UI completes the M2 provider request journey without privacy leaks', async ({ page, request }) => {
  const suffix = crypto.randomUUID().slice(0, 8);
  const providerName = `Mobile Courts ${suffix}`;
  const providerDraftName = `Draft Venue ${suffix}`;
  const unrelatedProviderName = `Remote KTV ${suffix}`;
  const evidenceReference = `registry:mobile-${suffix}`;
  const groupName = `Mobile group ${suffix}`;
  const privateGroupDescription = `Private group context ${suffix}`;
  const planDraftTitle = `Draft court plan ${suffix}`;
  const planTitle = `Friday court plan ${suffix}`;
  const memberPreference = `near transit ${suffix}`;
  const privateMemberNote = `Private Bea note ${suffix}`;
  const providerSafeNote = `Covered court requested ${suffix}`;
  const mustHave = `covered court ${suffix}`;
  const schedule = futureSchedule();

  await page.goto('/');
  await selectActor(page, 'dani');
  await page.goto('/providers');
  await page.getByLabel('Display name').fill(providerDraftName);
  await page.getByLabel('KTV', { exact: true }).check();
  await page.getByLabel('Service area codes').fill('MAKATI');
  const createProviderResponse = page.waitForResponse(isApiResponse('POST', '/api/v1/providers', 201));
  await page.getByRole('button', { name: 'Create provider organization' }).click();
  await createProviderResponse;
  await expect(page).toHaveURL(/\/providers\/[0-9a-f-]+$/);
  const providerId = requiredPathId(page);

  await page.getByLabel('Display name').fill(providerName);
  await page.getByLabel('KTV', { exact: true }).uncheck();
  await page.getByLabel('COURT', { exact: true }).check();
  await page.getByLabel('Service area codes').fill('BGC');
  const profileResponse = page.waitForResponse(
    isApiResponse('PUT', `/api/v1/providers/${providerId}/profile`, 200),
  );
  await page.getByRole('button', { name: 'Save provider profile' }).click();
  await profileResponse;
  await expect(page.getByRole('heading', { level: 1, name: providerName })).toBeVisible();

  await page.getByLabel('Evidence reference 1').fill(evidenceReference);
  const submissionResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/providers/${providerId}/verification-submissions`, 200),
  );
  await page.getByRole('button', { name: 'Submit for verification review' }).click();
  await submissionResponse;
  await expect(page.getByText('Pending review (PENDING)')).toBeVisible();
  await expectAxePasses(page, 'pending provider detail');

  await selectActor(page, 'owen');
  await page.goto('/operations/provider-verifications');
  const providerReview = page.getByRole('article', { name: providerName });
  const reviewText = await providerReview.textContent();
  const submissionId = requiredCapture(reviewText, /Submission ID\s*([0-9a-f-]{36})/);
  await expect(providerReview).toContainText(submissionId);
  await expect(providerReview).toContainText(evidenceReference);
  await providerReview.getByRole('button', { name: 'Review acceptance' }).click();
  const exactDecision = providerReview.getByRole('alertdialog');
  await expect(exactDecision).toContainText(submissionId);
  await exactDecision.getByLabel('Optional decision reason').fill(`Reviewed submission ${suffix}`);
  const decisionResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/operations/providers/${providerId}/verification-decisions`, 200),
  );
  await exactDecision.getByRole('button', { name: 'Confirm accept' }).click();
  const decisionHttpResponse = await decisionResponse;
  expect(asRecord(decisionHttpResponse.request().postDataJSON())['submissionId']).toBe(submissionId);
  await expect(page.getByRole('heading', { level: 2, name: 'Decision recorded' })).toBeVisible();
  await expect(page.getByText('Status recorded by this decision: VERIFIED.')).toBeVisible();

  const unrelatedProviderId = await createVerifiedUnrelatedProvider(
    request,
    unrelatedProviderName,
    suffix,
  );

  await selectActor(page, 'ari');
  await page.goto('/groups');
  await page.getByLabel('Group name').fill(groupName);
  await page.getByLabel('Description (optional)').fill(privateGroupDescription);
  const groupResponse = page.waitForResponse(isApiResponse('POST', '/api/v1/groups', 201));
  await page.getByRole('button', { name: 'Create group' }).click();
  await groupResponse;
  await expect(page).toHaveURL(/\/groups\/[0-9a-f-]+$/);
  const groupId = requiredPathId(page);

  await page.getByLabel('Invitee account ID').fill(accountIds.bea);
  await page.getByLabel('Expiry hours, optional').fill('24');
  const invitationResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/groups/${groupId}/invites`, 201),
  );
  await page.getByRole('button', { name: 'Create invitation' }).click();
  await invitationResponse;
  const invitationToken = await page.getByText(/Copy this token now/)
    .locator('..').getByRole('code').innerText();
  await expect(page.getByText(invitationToken, { exact: true })).toBeVisible();

  await selectActor(page, 'bea');
  await page.goto('/invitations/accept');
  await page.getByLabel('Invitation token').fill(invitationToken);
  const acceptanceResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/v1/group-invites/')
      && response.status() === 200,
  );
  await page.getByRole('button', { name: 'Accept invitation' }).click();
  await acceptanceResponse;
  await expect(page).toHaveURL(new RegExp(`/groups/${groupId}$`));
  await page.reload();
  await expect(page.getByRole('heading', { level: 1, name: groupName })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Members' }).getByText('Bea Member', { exact: true })).toBeVisible();

  await selectActor(page, 'ari');
  await page.goto(`/groups/${groupId}/plans`);
  await fillRequirementFields(page, {
    title: planDraftTitle,
    startAt: schedule.startAt,
    endAt: schedule.endAt,
    providerSafeNote: `Initial public note ${suffix}`,
  });
  const planResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/groups/${groupId}/plans`, 201),
  );
  await page.getByRole('button', { name: 'Create plan' }).click();
  await planResponse;
  await expect(page).toHaveURL(/\/plans\/[0-9a-f-]+$/);
  const planId = requiredPathId(page);

  await page.getByLabel('Plan title').fill(planTitle);
  await page.getByLabel('Provider-safe notes (optional)').fill(providerSafeNote);
  await page.getByRole('button', { name: 'Add must-have' }).click();
  await page.getByLabel('Must-have 1').fill(mustHave);
  const editResponse = page.waitForResponse(
    isApiResponse('PUT', `/api/v1/plans/${planId}/requirements`, 200),
  );
  await page.getByRole('button', { name: 'Save requirements' }).click();
  await editResponse;
  await expect(page.getByRole('heading', { level: 1, name: planTitle })).toBeVisible();

  await selectActor(page, 'bea');
  await page.goto(`/plans/${planId}`);
  await page.getByLabel('Attendance').selectOption('JOINING');
  await page.getByLabel('Guest count').fill('1');
  await page.getByRole('group', { name: 'Selected windows' }).getByRole('checkbox').check();
  await page.getByLabel('Ranked preferences').fill(`${memberPreference}\ncovered`);
  await page.getByLabel('Private note').fill(privateMemberNote);
  const preferenceResponse = page.waitForResponse(
    isApiResponse('PUT', `/api/v1/plans/${planId}/members/me/preference`, 201),
  );
  await page.getByRole('button', { name: 'Save preference' }).click();
  await preferenceResponse;
  await expect(page.getByRole('button', { name: 'Update preference' })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Private note')).toHaveValue(privateMemberNote);

  await selectActor(page, 'ari');
  await page.goto(`/plans/${planId}`);
  await page.getByRole('radio').check();
  await page.getByLabel('Offer deadline').fill(schedule.offerDeadline);
  const finalizationResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/plans/${planId}/requirement-finalization`, 201),
  );
  await page.getByRole('button', { name: 'Finalize requirements' }).click();
  await finalizationResponse;
  await expect(page.getByRole('heading', { level: 4, name: 'Immutable finalization command result' })).toBeVisible();
  await page.getByRole('button', { name: /Review publication of finalization/ }).click();
  const publicationResponse = page.waitForResponse(
    isApiResponse('POST', `/api/v1/plans/${planId}/published-requests`, 201),
  );
  await page.getByRole('button', { name: 'Confirm and publish request' }).click();
  await publicationResponse;
  await expect(page.getByRole('heading', { level: 4, name: 'Current version 1' })).toBeVisible();
  await expect(page.getByText('Collaboration state: Open for provider offers')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { level: 4, name: 'Current version 1' })).toBeVisible();
  const versionLink = page.getByRole('link', { name: 'Read version 1' });
  const requestId = requiredQueryParameter(await versionLink.getAttribute('href'), 'requestId');
  await expectAxePasses(page, 'published plan workspace');

  await selectActor(page, 'dani');
  const feedCapture = captureJsonResponse(page, `/api/v1/providers/${providerId}/request-feed`);
  await feedCapture.ready;
  await page.goto(`/providers/${providerId}/requests`);
  const providerFeed = await feedCapture.result;
  expect(providerFeed.status).toBe(200);
  const providerFeedBody = providerFeed.body;
  const requestLink = page.getByRole('listitem').filter({ hasText: 'COURT' }).getByRole('link');
  await expect(requestLink).toBeVisible();
  const detailCapture = captureJsonResponse(
    page,
    `/api/v1/providers/${providerId}/published-requests/${requestId}`,
  );
  await detailCapture.ready;
  await requestLink.click();
  const providerDetail = await detailCapture.result;
  expect(providerDetail.status).toBe(200);
  const providerDetailBody = providerDetail.body;
  await expect(page.getByRole('heading', { level: 2, name: 'Provider request details' })).toBeVisible();
  await expect(page.getByText(providerSafeNote, { exact: true })).toBeVisible();
  await expect(page.getByText(mustHave, { exact: true })).toBeVisible();

  const forbiddenValues = [
    groupId,
    groupName,
    privateGroupDescription,
    planId,
    planTitle,
    memberPreference,
    privateMemberNote,
    accountIds.ari,
    accountIds.bea,
    'Ari Organizer',
    'Bea Member',
    providerId,
    unrelatedProviderId,
  ];
  assertProviderPrivacy(providerFeedBody, forbiddenValues);
  assertProviderPrivacy(providerDetailBody, forbiddenValues);
  const providerContent = page.getByRole('main');
  for (const value of forbiddenValues) {
    await expect(providerContent.getByText(value, { exact: false })).toHaveCount(0);
  }
  await expectAxePasses(page, 'provider-safe request detail');
  await expectNoHorizontalOverflow(page, 'mobile provider-safe request detail');

  const unrelatedFeedCapture = captureJsonResponse(
    page,
    `/api/v1/providers/${unrelatedProviderId}/request-feed`,
  );
  await unrelatedFeedCapture.ready;
  await page.goto(`/providers/${unrelatedProviderId}/requests`);
  const unrelatedFeedResponse = await unrelatedFeedCapture.result;
  expect(unrelatedFeedResponse.status).toBe(200);
  const unrelatedFeed = asRecord(unrelatedFeedResponse.body);
  expect(unrelatedFeed['items']).toEqual([]);
  await expect(page.getByRole('heading', { level: 2, name: 'No provider requests yet' })).toBeVisible();

  const unrelatedDetailResponse = page.waitForResponse(
    isApiResponse(
      'GET',
      `/api/v1/providers/${unrelatedProviderId}/published-requests/${requestId}`,
      404,
    ),
  );
  await page.goto(`/providers/${unrelatedProviderId}/requests?requestId=${requestId}`);
  expect((await unrelatedDetailResponse).status()).toBe(404);
  await expect(page.getByRole('heading', { level: 2, name: 'Request details are unavailable' })).toBeVisible();

  await selectActor(page, 'cruz');
  await page.goto('/groups');
  await expect(page.getByRole('heading', { level: 2, name: 'No private groups yet' })).toBeVisible();
  await expect(page.getByRole('link', { name: groupName })).toHaveCount(0);
  await page.goto(`/groups/${groupId}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Group unavailable' })).toBeVisible();
  await expectAxePasses(page, 'outsider private group denial');
});

async function createVerifiedUnrelatedProvider(
  request: APIRequestContext,
  providerName: string,
  suffix: string,
): Promise<string> {
  const providerResponse = await request.post('/api/v1/providers', {
    data: {
      displayName: providerName,
      serviceAreaCodes: ['CEBU'],
      supportedCategories: ['KTV'],
    },
    headers: authorizedHeaders(tokens.dani, `mobile-${suffix}-unrelated-provider`),
  });
  expect(providerResponse.status()).toBe(201);
  const provider = asRecord(await providerResponse.json());
  const providerId = requiredString(provider, 'providerId');

  const submissionResponse = await request.post(
    `/api/v1/providers/${providerId}/verification-submissions`,
    {
      data: { evidenceReferences: [`registry:unrelated-${suffix}`] },
      headers: authorizedHeaders(tokens.dani, `mobile-${suffix}-unrelated-submission`),
    },
  );
  expect(submissionResponse.status()).toBe(200);
  const submission = asRecord(await submissionResponse.json());

  const decisionResponse = await request.post(
    `/api/v1/operations/providers/${providerId}/verification-decisions`,
    {
      data: {
        decision: 'ACCEPT',
        note: 'Verified for recipient authorization coverage.',
        submissionId: requiredString(submission, 'submissionId'),
      },
      headers: authorizedHeaders(tokens.owen, `mobile-${suffix}-unrelated-decision`),
    },
  );
  expect(decisionResponse.status()).toBe(200);
  expect(asRecord(await decisionResponse.json())['verificationStatus']).toBe('VERIFIED');
  return providerId;
}

async function fillRequirementFields(
  page: Page,
  values: {
    readonly endAt: string;
    readonly providerSafeNote: string;
    readonly startAt: string;
    readonly title: string;
  },
): Promise<void> {
  await page.getByLabel('Plan title').fill(values.title);
  await page.getByLabel('Starts').fill(values.startAt);
  await page.getByLabel('Ends').fill(values.endAt);
  await page.getByLabel('Area code').fill('BGC');
  await page.getByLabel('Minimum headcount').fill('4');
  await page.getByLabel('Maximum headcount').fill('8');
  await page.getByLabel('Provider-safe notes (optional)').fill(values.providerSafeNote);
}

function authorizedHeaders(token: string, key: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': key,
  };
}

function isApiResponse(method: string, path: string, status: number) {
  return (response: Response): boolean =>
    response.request().method() === method
      && new URL(response.url()).pathname === path
      && response.status() === status;
}

function captureJsonResponse(
  page: Page,
  path: string,
): {
  readonly ready: ReturnType<Page['route']>;
  readonly result: Promise<{ readonly body: unknown; readonly status: number }>;
} {
  let resolveResult!: (value: { readonly body: unknown; readonly status: number }) => void;
  let rejectResult!: (reason: unknown) => void;
  const result = new Promise<{ readonly body: unknown; readonly status: number }>((resolve, reject) => {
    resolveResult = resolve;
    rejectResult = reject;
  });
  const ready = page.route(
    (url) => url.pathname === path,
    async (route) => {
      try {
        const response = await route.fetch();
        const body: unknown = JSON.parse(await response.text());
        await route.fulfill({ response });
        resolveResult({ body, status: response.status() });
      } catch (error: unknown) {
        rejectResult(error);
        await route.abort();
      }
    },
    { times: 1 },
  );
  return { ready, result };
}

function requiredString(record: Readonly<Record<string, unknown>>, key: string): string {
  const value = record[key];
  expect(typeof value, `${key} must be present in the server response`).toBe('string');
  return value as string;
}

function requiredPathId(page: Page): string {
  const id = new URL(page.url()).pathname.split('/').at(-1);
  expect(id, 'the created resource ID must be present in the browser route').toMatch(/^[0-9a-f-]+$/);
  return id as string;
}

function requiredQueryParameter(href: string | null, name: string): string {
  const value = href === null ? null : new URL(href, 'http://browser.test').searchParams.get(name);
  expect(value, `${name} must be present in the browser link`).toMatch(/^[0-9a-f-]+$/);
  return value as string;
}

function requiredCapture(value: string | null, pattern: RegExp): string {
  const match = value?.match(pattern);
  expect(match?.[1], `text must match ${pattern.source}`).toBeTruthy();
  return match?.[1] as string;
}

function asRecord(value: unknown): Readonly<Record<string, unknown>> {
  expect(value).not.toBeNull();
  expect(typeof value).toBe('object');
  expect(Array.isArray(value)).toBe(false);
  return value as Readonly<Record<string, unknown>>;
}

function assertProviderPrivacy(value: unknown, forbiddenValues: readonly string[]): void {
  const serialized = JSON.stringify(value);
  for (const forbiddenValue of forbiddenValues) {
    expect(serialized).not.toContain(forbiddenValue);
  }
  visitJson(value, (key) => {
    expect(forbiddenProviderKeys, `provider response must omit ${key}`).not.toContain(key.toLowerCase());
  });
}

function visitJson(value: unknown, visitKey: (key: string) => void): void {
  if (Array.isArray(value)) {
    for (const item of value) visitJson(item, visitKey);
    return;
  }
  if (value === null || typeof value !== 'object') return;
  for (const [key, nested] of Object.entries(value)) {
    visitKey(key);
    visitJson(nested, visitKey);
  }
}

function futureSchedule(): { endAt: string; offerDeadline: string; startAt: string } {
  const start = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  start.setUTCHours(18, 0, 0, 0);
  const end = new Date(start.getTime() + 2 * 60 * 60 * 1000);
  const deadline = new Date(start.getTime() - 7 * 24 * 60 * 60 * 1000);
  return {
    endAt: localDateTime(end),
    offerDeadline: localDateTime(deadline),
    startAt: localDateTime(start),
  };
}

function localDateTime(value: Date): string {
  return value.toISOString().slice(0, 16);
}

import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpClient,
  ApiHttpError,
  IDEMPOTENCY_KEY_GENERATOR,
  ifMatch,
  ifNoneMatch,
} from './api-http-client';

describe('ApiHttpClient', () => {
  let client: ApiHttpClient;
  let http: HttpTestingController;
  let keyNumber: number;

  beforeEach(() => {
    keyNumber = 0;
    TestBed.configureTestingModule({
      providers: [
        ApiHttpClient,
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: IDEMPOTENCY_KEY_GENERATOR,
          useValue: () => `intent-key-${++keyNumber}`,
        },
      ],
    });

    client = TestBed.inject(ApiHttpClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('captures the complete HTTP result', async () => {
    const resultPromise = firstValueFrom(client.mutate<{ groupId: string }, { name: string }>({
      method: 'POST',
      path: '/groups',
      body: { name: 'Weekend crew' },
    }));

    const request = http.expectOne('/api/v1/groups');
    request.flush(
      { groupId: 'group-id' },
      {
        status: 201,
        statusText: 'Created',
        headers: {
          ETag: '"7"',
          Location: '/api/v1/groups/group-id',
          'X-Correlation-Id': 'correlation-1',
        },
      },
    );

    await expect(resultPromise).resolves.toEqual({
      body: { groupId: 'group-id' },
      status: 201,
      etag: '"7"',
      location: '/api/v1/groups/group-id',
      correlationId: 'correlation-1',
    });
  });

  it('uses null for missing optional headers and a missing body', async () => {
    const resultPromise = firstValueFrom(client.mutate<never, undefined>({
      method: 'DELETE',
      path: '/groups/group-id/invites/invite-id',
    }));

    http.expectOne('/api/v1/groups/group-id/invites/invite-id').flush(null, {
      status: 204,
      statusText: 'No Content',
    });

    await expect(resultPromise).resolves.toEqual({
      body: null,
      status: 204,
      etag: null,
      location: null,
      correlationId: null,
    });
  });

  it('sends exact If-Match and If-None-Match preconditions', () => {
    client.mutate({
      method: 'PUT',
      path: '/plans/plan-id/requirements',
      body: {},
      precondition: ifMatch('"12"'),
    }).subscribe();

    const replacement = http.expectOne('/api/v1/plans/plan-id/requirements');
    expect(replacement.request.headers.get('If-Match')).toBe('"12"');
    expect(replacement.request.headers.has('If-None-Match')).toBe(false);
    replacement.flush({});

    client.mutate({
      method: 'PUT',
      path: '/plans/plan-id/members/me/preference',
      body: {},
      precondition: ifNoneMatch(),
    }).subscribe();

    const firstPreference = http.expectOne('/api/v1/plans/plan-id/members/me/preference');
    expect(firstPreference.request.headers.get('If-None-Match')).toBe('*');
    expect(firstPreference.request.headers.has('If-Match')).toBe(false);
    firstPreference.flush({});
  });

  it('surfaces 412 with safe text and response metadata without retrying', async () => {
    const resultPromise = firstValueFrom(client.mutate({
      method: 'PUT',
      path: '/plans/plan-id/requirements',
      body: { title: 'Changed' },
      precondition: ifMatch('"3"'),
    }));

    const request = http.expectOne('/api/v1/plans/plan-id/requirements');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    request.flush(
      {
        code: 'PRECONDITION_FAILED',
        status: 412,
        title: 'Precondition failed',
        detail: '<strong>The plan changed.</strong>',
        correlationId: 'body-correlation',
        violations: [],
      },
      {
        status: 412,
        statusText: 'Precondition Failed',
        headers: {
          'Content-Type': 'application/problem+json',
          ETag: '"4"',
          'X-Correlation-Id': 'header-correlation',
        },
      },
    );

    const error = await rejection(resultPromise);
    expect(error).toBeInstanceOf(ApiHttpError);
    expect(error).toMatchObject({
      status: 412,
      etag: '"4"',
      correlationId: 'header-correlation',
      problem: {
        code: 'PRECONDITION_FAILED',
        detail: '<strong>The plan changed.</strong>',
      },
    });
    http.expectNone('/api/v1/plans/plan-id/requirements');
  });

  it('reuses one key and request snapshot only after a transport failure', async () => {
    const body = { name: 'Original name' };
    const precondition = { header: 'If-Match' as const, value: '"3"' };
    const intent = client.beginIdempotentMutation({
      method: 'POST',
      path: '/groups',
      body,
      precondition,
    });
    body.name = 'Changed after click';
    precondition.value = '"4"';

    const firstAttempt = firstValueFrom(client.executeIdempotent(intent));
    const firstRequest = http.expectOne('/api/v1/groups');
    expect(firstRequest.request.headers.get('Idempotency-Key')).toBe('intent-key-1');
    expect(firstRequest.request.headers.get('If-Match')).toBe('"3"');
    expect(firstRequest.request.body).toEqual({ name: 'Original name' });
    firstRequest.error(new ProgressEvent('network error'));
    await expect(firstAttempt).rejects.toBeInstanceOf(ApiHttpError);

    const retryAttempt = firstValueFrom(client.executeIdempotent(intent));
    const retryRequest = http.expectOne('/api/v1/groups');
    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe('intent-key-1');
    expect(retryRequest.request.headers.get('If-Match')).toBe('"3"');
    expect(retryRequest.request.body).toEqual({ name: 'Original name' });
    retryRequest.flush({ groupId: 'group-id' }, {
      status: 201,
      statusText: 'Created',
    });
    await expect(retryAttempt).resolves.toMatchObject({ status: 201 });

    const secondIntent = client.beginIdempotentMutation({
      method: 'POST',
      path: '/groups',
      body: { name: 'New intent' },
    });
    expect(secondIntent.idempotencyKey).toBe('intent-key-2');
  });

  it('reuses an invitation key without retaining the raw token', async () => {
    let token = 'one-time-invitation-token';
    expect(() => client.beginIdempotentMutation({
      method: 'POST',
      path: `/group-invites/${token}/accept`,
    })).toThrow('Invitation acceptance requires its token-safe intent helper.');

    const intent = await client.beginInvitationAcceptance(token);
    expect(JSON.stringify(intent)).not.toContain(token);

    const firstAttempt = firstValueFrom(
      client.executeInvitationAcceptance(intent, token),
    );
    const firstRequest = await waitForRequest(
      http,
      '/api/v1/group-invites/one-time-invitation-token/accept',
    );
    expect(firstRequest.request.headers.get('Idempotency-Key')).toBe('intent-key-1');
    firstRequest.error(new ProgressEvent('network error'));
    await expect(firstAttempt).rejects.toBeInstanceOf(ApiHttpError);

    token = '';
    expect(token).toBe('');
    expect(JSON.stringify(intent)).not.toContain('one-time-invitation-token');

    await expect(firstValueFrom(
      client.executeInvitationAcceptance(intent, 'different-token'),
    )).rejects.toThrow('A changed invitation token requires a new intent.');
    http.expectNone('/api/v1/group-invites/different-token/accept');

    token = 'one-time-invitation-token';
    const retryAttempt = firstValueFrom(
      client.executeInvitationAcceptance<{ groupId: string }>(intent, token),
    );
    const retryRequest = await waitForRequest(
      http,
      '/api/v1/group-invites/one-time-invitation-token/accept',
    );
    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe('intent-key-1');
    retryRequest.flush({ groupId: 'group-id' });
    await expect(retryAttempt).resolves.toMatchObject({
      body: { groupId: 'group-id' },
    });
  });

  it('does not resend an intent after an application response', async () => {
    const intent = client.beginIdempotentMutation({
      method: 'POST',
      path: '/groups',
      body: { name: 'Weekend crew' },
    });

    const firstAttempt = firstValueFrom(client.executeIdempotent(intent));
    http.expectOne('/api/v1/groups').flush(
      {
        code: 'VALIDATION_FAILED',
        status: 422,
        title: 'Validation failed',
        detail: 'The request is invalid.',
        violations: [],
      },
      {
        status: 422,
        statusText: 'Unprocessable Content',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    await expect(firstAttempt).rejects.toBeInstanceOf(ApiHttpError);

    await expect(firstValueFrom(client.executeIdempotent(intent)))
      .rejects.toThrow('This mutation intent cannot be sent again.');
    http.expectNone('/api/v1/groups');
  });

  it('maps malformed error bodies to a bounded unknown problem', async () => {
    const resultPromise = firstValueFrom(client.read('/groups'));
    const request = http.expectOne('/api/v1/groups');
    request.flush(
      { code: '<script>', detail: 'x'.repeat(5000), status: '500' },
      {
        status: 400,
        statusText: 'Bad Request',
        headers: {
          'Content-Type': 'application/problem+json; charset=utf-8',
          'X-Correlation-Id': 'c'.repeat(400),
        },
      },
    );

    const error = await rejection(resultPromise);
    expect(error).toMatchObject({
      status: 400,
      problem: {
        code: 'UNKNOWN_ERROR',
        title: 'Request failed',
        detail: 'The request could not be completed.',
        violations: [],
      },
    });
    expect((error as ApiHttpError).correlationId).toHaveLength(255);
  });

  it('retries only a bounded number of transient read failures', async () => {
    const resultPromise = firstValueFrom(client.read<{ items: unknown[] }>('/groups'));

    http.expectOne('/api/v1/groups').flush(null, {
      status: 503,
      statusText: 'Service Unavailable',
    });
    http.expectOne('/api/v1/groups').flush(null, {
      status: 503,
      statusText: 'Service Unavailable',
    });
    http.expectOne('/api/v1/groups').flush({ items: [] });

    await expect(resultPromise).resolves.toMatchObject({
      body: { items: [] },
      status: 200,
    });
  });

  it('does not retry non-transient read failures or any mutation response', async () => {
    const readPromise = firstValueFrom(client.read('/groups'));
    http.expectOne('/api/v1/groups').flush(null, {
      status: 412,
      statusText: 'Precondition Failed',
    });
    await expect(readPromise).rejects.toBeInstanceOf(ApiHttpError);
    http.expectNone('/api/v1/groups');

    const mutationPromise = firstValueFrom(client.mutate({
      method: 'POST',
      path: '/groups',
      body: { name: 'Weekend crew' },
    }));
    http.expectOne('/api/v1/groups').flush(null, {
      status: 503,
      statusText: 'Service Unavailable',
    });
    await expect(mutationPromise).rejects.toBeInstanceOf(ApiHttpError);
    http.expectNone('/api/v1/groups');
  });
});

async function rejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
    throw new Error('Expected promise to reject.');
  } catch (error: unknown) {
    return error;
  }
}

async function waitForRequest(
  http: HttpTestingController,
  url: string,
): Promise<TestRequest> {
  let request: TestRequest | undefined;
  await vi.waitFor(() => {
    const matches = http.match(url);
    if (matches.length === 1) {
      [request] = matches;
    }
    expect(request).toBeDefined();
  });

  if (request === undefined) {
    throw new Error(`No request was made to ${url}.`);
  }
  return request;
}

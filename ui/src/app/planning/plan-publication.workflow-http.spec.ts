import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanPublicationService } from './plan-publication.service';

describe('plan publication HTTP workflow', () => {
  let publications: PlanPublicationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'publication-key' },
      ],
    });
    publications = TestBed.inject(PlanPublicationService);
    http = TestBed.inject(HttpTestingController);
    publications.usePlan('plan id');
  });

  afterEach(() => http.verify());

  it('publishes an initial request with one finalization, key, and plan ETag', async () => {
    const publication = publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    );
    const request = http.expectOne('/api/v1/plans/plan%20id/published-requests');

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('publication-key');
    expect(request.request.headers.get('If-Match')).toBe('"7"');
    expect(request.request.body).toEqual({ finalizationId: 'finalization-1' });
    request.flush(publishedRequest(1), {
      status: 201,
      statusText: 'Created',
      headers: {
        ETag: '"8"',
        Location: '/api/v1/plans/plan-id/published-requests/request-1',
      },
    });

    expect(await publication).toEqual(expect.objectContaining({
      etag: '"8"',
      exactRetry: false,
      location: '/api/v1/plans/plan-id/published-requests/request-1',
      outcome: 'initial',
    }));
    expect(publications.state()).toBe('succeeded');
  });

  it('labels a publication from an open plan as an atomic replacement', async () => {
    const publication = publications.publish(
      'plan id', 'finalization-2', '"8"', 'OPEN_FOR_OFFERS',
    );
    http.expectOne('/api/v1/plans/plan%20id/published-requests').flush(
      publishedRequest(2),
      {
        status: 201,
        statusText: 'Created',
        headers: {
          ETag: '"9"',
          Location: '/api/v1/plans/plan-id/published-requests/request-2',
        },
      },
    );

    expect((await publication)?.outcome).toBe('replacement');
  });

  it('prevents a second action while one publication intent is in flight', async () => {
    const first = publications.publish('plan id', 'finalization-1', '"7"', 'COLLABORATING');
    const pending = http.expectOne('/api/v1/plans/plan%20id/published-requests');

    await expect(publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    )).resolves.toBeNull();
    http.expectNone('/api/v1/plans/plan%20id/published-requests');

    pending.flush(
      publishedRequest(1),
      {
        status: 201,
        statusText: 'Created',
        headers: {
          ETag: '"8"',
          Location: '/api/v1/plans/plan-id/published-requests/request-1',
        },
      },
    );
    await first;
  });

  it('retries the exact publication with the same key, body, and plan ETag', async () => {
    const first = publications.publish('plan id', 'finalization-1', '"7"', 'COLLABORATING');
    const firstHttp = http.expectOne('/api/v1/plans/plan%20id/published-requests');
    firstHttp.error(new ProgressEvent('error'));
    await first;

    expect(publications.state()).toBe('network-error');
    const retry = publications.retry('plan id');
    const retryHttp = http.expectOne('/api/v1/plans/plan%20id/published-requests');
    expect(retryHttp.request.headers.get('Idempotency-Key')).toBe('publication-key');
    expect(retryHttp.request.headers.get('If-Match')).toBe('"7"');
    expect(retryHttp.request.body).toEqual({ finalizationId: 'finalization-1' });
    retryHttp.flush(publishedRequest(1), {
      status: 201,
      statusText: 'Created',
      headers: {
        ETag: '"8"',
        Location: '/api/v1/plans/plan-id/published-requests/request-1',
      },
    });

    expect((await retry)?.exactRetry).toBe(true);
    expect(publications.state()).toBe('succeeded');
  });

  it('surfaces stale ETag and finalization-version changes as recoverable conflicts', async () => {
    const staleEtag = publications.publish('plan id', 'finalization-1', '"6"', 'COLLABORATING');
    http.expectOne('/api/v1/plans/plan%20id/published-requests').flush(
      problem('PRECONDITION_FAILED', 412),
      problemOptions(412, 'Precondition Failed'),
    );
    await staleEtag;

    expect(publications.state()).toBe('conflict');
    publications.dismiss();

    const staleFinalization = publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    );
    http.expectOne('/api/v1/plans/plan%20id/published-requests').flush(
      problem('FINALIZATION_VERSION_CHANGED', 409),
      problemOptions(409, 'Conflict'),
    );
    await staleFinalization;

    expect(publications.state()).toBe('conflict');
    expect(publications.problemCode()).toBe('FINALIZATION_VERSION_CHANGED');
  });

  it.each([
    'NO_ELIGIBLE_PROVIDERS',
    'RECIPIENT_LIMIT_EXCEEDED',
    'REQUEST_DEADLINE_EXPIRED',
    'INVALID_PLAN_STATE',
  ])('retains the distinct publication problem %s', async (code) => {
    const publication = publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    );
    http.expectOne('/api/v1/plans/plan%20id/published-requests').flush(
      problem(code, 409),
      problemOptions(409, 'Conflict'),
    );
    await publication;

    expect(publications.state()).toBe('error');
    expect(publications.problemCode()).toBe(code);
    publications.dismiss();
  });

  it('rejects a success missing required ETag or Location metadata', async () => {
    const publication = publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    );
    http.expectOne('/api/v1/plans/plan%20id/published-requests').flush(
      publishedRequest(1),
      { status: 201, statusText: 'Created' },
    );
    await publication;

    expect(publications.state()).toBe('error');
    expect(publications.problemCode()).toBe('INVALID_PUBLICATION_RESPONSE');
  });

  it('clears publication state and ignores a late response after an actor change', async () => {
    const publication = publications.publish(
      'plan id', 'finalization-1', '"7"', 'COLLABORATING',
    );
    const pending = http.expectOne('/api/v1/plans/plan%20id/published-requests');

    TestBed.inject(ActorScopeResetService).reset();
    pending.flush(publishedRequest(1), {
      status: 201,
      statusText: 'Created',
      headers: {
        ETag: '"8"',
        Location: '/api/v1/plans/plan-id/published-requests/request-1',
      },
    });
    await publication;

    expect(publications.state()).toBe('idle');
    expect(publications.result()).toBeNull();
  });
});

function publishedRequest(requestVersion: number) {
  return {
    requestId: `request-${requestVersion}`,
    requestVersion,
    publishedAt: '2027-01-08T01:00:00Z',
    state: 'OPEN',
    actionable: true,
    category: 'COURT',
    timeZone: 'Asia/Manila',
    area: { code: 'BGC', radiusKm: 5 },
    requestedWindow: {
      startAt: '2027-01-09T01:00:00Z',
      endAt: '2027-01-09T03:00:00Z',
    },
    headcount: { minimum: 4, maximum: 10 },
    mustHaves: ['parking'],
    categoryAttributes: { hasParking: true },
    offerDeadline: '2027-01-08T02:00:00Z',
  };
}

function problem(code: string, status: number) {
  return {
    code,
    status,
    title: 'Request failed',
    detail: 'Do not render this raw detail.',
    violations: [],
    correlationId: 'correlation-1',
  };
}

function problemOptions(status: number, statusText: string) {
  return {
    status,
    statusText,
    headers: { 'Content-Type': 'application/problem+json' },
  };
}

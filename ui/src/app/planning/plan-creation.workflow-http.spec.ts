import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiHttpClient, IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { CreatePlanRequest, PlanApi } from './plan-api.service';
import { PlanCreationService } from './plan-creation.service';

describe('plan creation HTTP workflow', () => {
  let creation: PlanCreationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiHttpClient,
        PlanApi,
        PlanCreationService,
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'plan-intent-key' },
      ],
    });
    creation = TestBed.inject(PlanCreationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('retries and exactly replays the original all-fields request with one key', async () => {
    const request = completeRequest();
    const firstAttempt = creation.create('group id', request);
    const first = http.expectOne('/api/v1/groups/group%20id/plans');
    expect(first.request.headers.get('Idempotency-Key')).toBe('plan-intent-key');
    expect(first.request.body).toEqual(request);
    first.error(new ProgressEvent('network error'));
    await firstAttempt;

    const retry = creation.retry();
    const replay = http.expectOne('/api/v1/groups/group%20id/plans');
    expect(replay.request.headers.get('Idempotency-Key')).toBe('plan-intent-key');
    expect(replay.request.body).toEqual(request);
    replay.flush({ planId: 'plan-1', createdByAccountId: 'actor-1', state: 'COLLABORATING', version: 1 }, {
      status: 201,
      statusText: 'Created',
      headers: { ETag: '"1"', Location: '/api/v1/plans/plan-1' },
    });

    await expect(retry).resolves.toMatchObject({
      etag: '"1"',
      location: '/api/v1/plans/plan-1',
    });
  });

  it.each([
    [404, 'PRIVATE_RESOURCE_NOT_FOUND'],
    [409, 'IDEMPOTENCY_KEY_REUSED'],
    [422, 'VALIDATION_FAILED'],
  ])('keeps the %i %s server result visible without automatic mutation retry', async (status, code) => {
    const pending = creation.create('group-1', completeRequest());
    http.expectOne('/api/v1/groups/group-1/plans').flush({
      code,
      status,
      title: 'Request failed',
      detail: 'The request was not accepted.',
      violations: status === 422 ? [{ field: 'headcount.minimum', message: 'Minimum is invalid.' }] : [],
      correlationId: 'correlation-1',
    }, {
      status,
      statusText: 'Request failed',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    await pending;

    expect(creation.problemCode()).toBe(code);
    expect(creation.correlationId()).toBe('correlation-1');
    await expect(creation.retry()).resolves.toBeNull();
  });
});

function completeRequest(): CreatePlanRequest {
  return {
    title: 'Friday badminton',
    category: 'COURT',
    timeZone: 'Asia/Manila',
    candidateWindows: [{ startAt: '2027-01-09T09:00:00+08:00', endAt: '2027-01-09T11:00:00+08:00' }],
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
    budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
    mustHaves: ['parking', 'shower'],
    providerSafeNotes: 'Indoor court preferred.',
    categoryAttributes: { hasParking: true, courtCount: 2 },
  };
}

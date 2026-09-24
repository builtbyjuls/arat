import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiHttpError, ApiHttpResult, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreatePlanRequest, PlanApi, PlanRepresentation } from './plan-api.service';
import { PlanCreationService } from './plan-creation.service';

describe('PlanCreationService', () => {
  it('creates only one intent for duplicate clicks', async () => {
    const response = new Subject<ApiHttpResult<PlanRepresentation>>();
    const api = fakeApi([response]);
    const service = setup(api);

    const first = service.create('group-1', request());
    const second = await service.create('group-1', request());

    expect(second).toBeNull();
    expect(api.createIntent).toHaveBeenCalledOnce();
    expect(api.create).toHaveBeenCalledOnce();
    response.next(result());
    response.complete();
    await first;
  });

  it('retries a network failure with the exact original intent', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR')), of(result())]);
    const service = setup(api);

    await service.create('group-1', request());
    await service.retry();

    expect(api.create).toHaveBeenCalledTimes(2);
    expect(api.create.mock.calls[0][0]).toBe(api.create.mock.calls[1][0]);
    expect(api.create.mock.calls[0][0].idempotencyKey).toBe('plan-key-1');
  });

  it('does not replace an ambiguous network intent with a new create intent', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR'))]);
    const service = setup(api);

    await service.create('group-1', request());
    const replacement = await service.create('group-1', { ...request(), title: 'Changed title' });

    expect(replacement).toBeNull();
    expect(api.createIntent).toHaveBeenCalledOnce();
    expect(api.create).toHaveBeenCalledOnce();
  });

  it.each([
    [404, 'PRIVATE_RESOURCE_NOT_FOUND'],
    [409, 'IDEMPOTENCY_KEY_REUSED'],
    [422, 'VALIDATION_FAILED'],
  ])('does not retry an application response %i %s', async (status, code) => {
    const api = fakeApi([throwError(() => problem(status, code))]);
    const service = setup(api);

    await service.create('group-1', request());

    expect(service.problemCode()).toBe(code);
    await expect(service.retry()).resolves.toBeNull();
    expect(api.create).toHaveBeenCalledOnce();
  });

  it('retains server validation and correlation details', async () => {
    const api = fakeApi([throwError(() => new ApiHttpError({
      code: 'VALIDATION_FAILED',
      status: 422,
      title: 'Validation failed',
      detail: 'The request was not accepted.',
      violations: [{ field: 'candidateWindows[0].startAt', message: 'Start is invalid.' }],
      correlationId: 'correlation-1',
    }, null, null))]);
    const service = setup(api);

    await service.create('group-1', request());

    expect(service.violations()).toEqual([{ field: 'candidateWindows[0].startAt', message: 'Start is invalid.' }]);
    expect(service.correlationId()).toBe('correlation-1');
  });

  it('discards retry state and stale completion on actor switch', async () => {
    const response = new Subject<ApiHttpResult<PlanRepresentation>>();
    const api = fakeApi([response]);
    const service = setup(api);

    const pending = service.create('group-1', request());
    TestBed.inject(ActorScopeResetService).reset();
    response.error(problem(0, 'UNKNOWN_ERROR'));
    await pending;

    expect(service.state()).toBe('idle');
    await expect(service.retry()).resolves.toBeNull();
  });

  it('discards a retry intent when the group route changes', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR'))]);
    const service = setup(api);

    await service.create('group-1', request());
    service.useGroup('group-2');

    expect(service.state()).toBe('idle');
    await expect(service.retry()).resolves.toBeNull();
  });

  it('ignores completion after the route releases its group context', async () => {
    const response = new Subject<ApiHttpResult<PlanRepresentation>>();
    const api = fakeApi([response]);
    const service = setup(api);

    const pending = service.create('group-1', request());
    service.releaseGroup('group-1');
    response.next(result());
    response.complete();

    await expect(pending).resolves.toBeNull();
    expect(service.state()).toBe('idle');
  });
});

function setup(api: ReturnType<typeof fakeApi>): PlanCreationService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: PlanApi, useValue: api }] });
  return TestBed.inject(PlanCreationService);
}

function fakeApi(responses: readonly Observable<ApiHttpResult<PlanRepresentation>>[]) {
  let requestIndex = 0;
  return {
    createIntent: vi.fn((_groupId: string, _request: CreatePlanRequest) => ({ idempotencyKey: `plan-key-${requestIndex + 1}` })),
    create: vi.fn((_intent: IdempotentMutationIntent<CreatePlanRequest>) => responses[requestIndex++]),
  };
}

function request(): CreatePlanRequest {
  return {
    title: 'Friday badminton',
    category: 'COURT',
    timeZone: 'Asia/Manila',
    candidateWindows: [{ startAt: '2027-01-09T09:00:00+08:00', endAt: '2027-01-09T11:00:00+08:00' }],
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
    mustHaves: [],
    categoryAttributes: {},
  };
}

function result(): ApiHttpResult<PlanRepresentation> {
  return {
    body: { planId: 'plan-1', createdByAccountId: 'actor-1', state: 'COLLABORATING', version: 1 },
    status: 201,
    etag: '"1"',
    location: '/api/v1/plans/plan-1',
    correlationId: null,
  };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({
    code,
    status,
    title: 'Request failed',
    detail: 'Request failed.',
    violations: [],
    correlationId: null,
  }, null, null);
}

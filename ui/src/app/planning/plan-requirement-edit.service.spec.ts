import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  PlanApi,
  PlanRequirements,
  RequirementReplacementRequest,
} from './plan-api.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';

describe('PlanRequirementEditService', () => {
  it('submits one replacement with the loaded plan ETag', async () => {
    const api = fakeApi([of(result())]);
    const service = setup(api);
    service.usePlan('plan-1');

    const response = await service.replace('plan-1', request(), '"7"');

    expect(api.replaceRequirements).toHaveBeenCalledWith('plan-1', request(), '"7"');
    expect(response?.etag).toBe('"8"');
    expect(service.state()).toBe('idle');
  });

  it('retries an ambiguous network failure with the exact request and ETag', async () => {
    const api = fakeApi([
      throwError(() => problem(0, 'UNKNOWN_ERROR')),
      of(result()),
    ]);
    const service = setup(api);
    service.usePlan('plan-1');

    await service.replace('plan-1', request(), '"7"');
    await service.retry();

    expect(api.replaceRequirements).toHaveBeenCalledTimes(2);
    expect(api.replaceRequirements.mock.calls[1][1]).toBe(api.replaceRequirements.mock.calls[0][1]);
    expect(api.replaceRequirements.mock.calls[1][2]).toBe('"7"');
  });

  it('requires a refresh and an explicit reapply after a stale ETag', async () => {
    const api = fakeApi([
      throwError(() => problem(412, 'PRECONDITION_FAILED')),
      of(result()),
    ]);
    const service = setup(api);
    service.usePlan('plan-1');

    await service.replace('plan-1', request(), '"7"');
    expect(service.state()).toBe('conflict');

    service.markRefreshed('"8"');
    expect(service.state()).toBe('reapply-ready');
    expect(api.replaceRequirements).toHaveBeenCalledOnce();

    await service.reapply('plan-1', { ...request(), title: 'Deliberately reapplied' });
    expect(api.replaceRequirements).toHaveBeenNthCalledWith(
      2,
      'plan-1',
      expect.objectContaining({ title: 'Deliberately reapplied' }),
      '"8"',
    );
  });

  it.each([
    [403, 'FORBIDDEN_ROLE'],
    [409, 'INVALID_PLAN_STATE'],
    [422, 'VALIDATION_FAILED'],
  ])('presents application outcome %i %s without retrying', async (status, code) => {
    const error = new ApiHttpError({
      code,
      status,
      title: 'Request failed',
      detail: 'Do not render this raw detail.',
      violations: status === 422 ? [{ field: 'title', message: 'Title is invalid.' }] : [],
      correlationId: 'correlation-1',
    }, null, null);
    const api = fakeApi([throwError(() => error)]);
    const service = setup(api);
    service.usePlan('plan-1');

    await service.replace('plan-1', request(), '"7"');

    expect(service.state()).toBe('error');
    expect(service.problemCode()).toBe(code);
    expect(service.correlationId()).toBe('correlation-1');
    expect(service.violations()).toEqual(status === 422
      ? [{ field: 'title', message: 'Title is invalid.' }]
      : []);
    await expect(service.retry()).resolves.toBeNull();
    expect(api.replaceRequirements).toHaveBeenCalledOnce();
  });

  it('rejects a success response without the required replacement ETag', async () => {
    const api = fakeApi([of({ ...result(), etag: null })]);
    const service = setup(api);
    service.usePlan('plan-1');

    await service.replace('plan-1', request(), '"7"');

    expect(service.state()).toBe('error');
    expect(service.problemCode()).toBe('INVALID_REPLACEMENT_RESPONSE');
  });

  it('discards an in-flight completion and retry state on actor switch', async () => {
    const response = new Subject<ApiHttpResult<PlanRequirements>>();
    const api = fakeApi([response]);
    const service = setup(api);
    service.usePlan('plan-1');

    const pending = service.replace('plan-1', request(), '"7"');
    TestBed.inject(ActorScopeResetService).reset();
    response.error(problem(0, 'UNKNOWN_ERROR'));
    await pending;

    expect(service.state()).toBe('idle');
    await expect(service.retry()).resolves.toBeNull();
  });
});

function setup(api: ReturnType<typeof fakeApi>): PlanRequirementEditService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: PlanApi, useValue: api }] });
  return TestBed.inject(PlanRequirementEditService);
}

function fakeApi(responses: readonly Observable<ApiHttpResult<PlanRequirements>>[]) {
  let index = 0;
  return {
    replaceRequirements: vi.fn((
      _planId: string,
      _request: RequirementReplacementRequest,
      _etag: string,
    ) => responses[index++]),
  };
}

function request(): RequirementReplacementRequest {
  return {
    title: 'Friday badminton',
    category: 'COURT',
    timeZone: 'Asia/Manila',
    candidateWindows: [{
      id: 'window-1',
      startAt: '2027-01-09T09:00:00+08:00',
      endAt: '2027-01-09T11:00:00+08:00',
    }],
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
    budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
    mustHaves: ['parking'],
    providerSafeNotes: 'Indoor court preferred.',
    categoryAttributes: { hasParking: true },
  };
}

function result(): ApiHttpResult<PlanRequirements> {
  return {
    body: request(),
    status: 200,
    etag: '"8"',
    location: null,
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

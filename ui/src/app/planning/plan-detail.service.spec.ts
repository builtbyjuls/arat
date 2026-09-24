import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanApi, PlanDetail } from './plan-api.service';
import { PlanDetailService } from './plan-detail.service';

describe('PlanDetailService', () => {
  it('loads the private detail and retains its ETag for later commands', async () => {
    const api = { read: vi.fn(() => of(detailResult())) };
    const service = setup(api);

    await service.load('plan-1');

    expect(api.read).toHaveBeenCalledWith('plan-1');
    expect(service.plan()?.title).toBe('Friday badminton');
    expect(service.etag()).toBe('"7"');
    expect(service.state()).toBe('ready');
  });

  it('presents every private 404 as unavailable without retaining plan data', async () => {
    const service = setup({ read: vi.fn(() => throwError(() => problem(404))) });

    await service.load('unknown-or-revoked-plan');

    expect(service.state()).toBe('not-found');
    expect(service.plan()).toBeNull();
    expect(service.etag()).toBeNull();
  });

  it('clears the old ETag while a different plan is loading', async () => {
    const nextRead = new Subject<ApiHttpResult<PlanDetail>>();
    const api = { read: vi.fn((): Observable<ApiHttpResult<PlanDetail>> => api.read.mock.calls.length === 1 ? of(detailResult()) : nextRead) };
    const service = setup(api);

    await service.load('plan-1');
    const changedPlan = service.load('plan-2');

    expect(service.plan()).toBeNull();
    expect(service.etag()).toBeNull();
    nextRead.next(detailResult({ planId: 'plan-2', title: 'Saturday karaoke' }));
    nextRead.complete();
    await changedPlan;

    expect(service.plan()?.planId).toBe('plan-2');
  });

  it('clears private plan data and ETag on actor change', async () => {
    const service = setup({ read: vi.fn(() => of(detailResult())) });

    await service.load('plan-1');
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.plan()).toBeNull();
    expect(service.etag()).toBeNull();
  });

  it('clears a loaded plan when a later command proves it inaccessible', async () => {
    const service = setup({ read: vi.fn(() => of(detailResult())) });

    await service.load('plan-1');
    service.discardInaccessiblePlan('plan-1');

    expect(service.state()).toBe('not-found');
    expect(service.plan()).toBeNull();
    expect(service.etag()).toBeNull();
  });
});

function setup(api: Pick<PlanApi, 'read'>): PlanDetailService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: PlanApi, useValue: api }] });
  return TestBed.inject(PlanDetailService);
}

function detailResult(overrides: Partial<PlanDetail> = {}): ApiHttpResult<PlanDetail> {
  return {
    body: {
      planId: 'plan-1', title: 'Friday badminton', state: 'COLLABORATING', version: 7,
      requirements: { category: 'COURT', timeZone: 'Asia/Manila' },
      ...overrides,
    }, status: 200, etag: '"7"', location: null, correlationId: null,
  };
}

function problem(status: number): ApiHttpError {
  return new ApiHttpError({
    code: 'PRIVATE_RESOURCE_NOT_FOUND', status, title: 'Private resource not found',
    detail: 'Do not render this raw detail.', violations: [], correlationId: 'correlation-1',
  }, null, null);
}

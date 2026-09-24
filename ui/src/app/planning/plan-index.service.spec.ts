import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanApi, PlanPage } from './plan-api.service';
import { PlanIndexService } from './plan-index.service';

describe('PlanIndexService', () => {
  it('loads a group page and shows the empty state', async () => {
    const service = setup(planApi([page([])]));
    await service.refresh('group-1');
    expect(service.state()).toBe('empty');
  });

  it('appends stable pages without duplicate plan summaries', async () => {
    const api = planApi([page([{ planId: 'plan-1', title: 'First', state: 'COLLABORATING' }], 'next'), page([
      { planId: 'plan-1', title: 'First', state: 'COLLABORATING' },
      { planId: 'plan-2', title: 'Second', state: 'OPEN_FOR_OFFERS' },
    ])]);
    const service = setup(api);
    await service.refresh('group-1');
    await service.loadMore();
    expect(api.list).toHaveBeenNthCalledWith(2, 'group-1', 'next');
    expect(service.plans().map((plan) => plan.planId)).toEqual(['plan-1', 'plan-2']);
  });

  it('reloads every loaded page for a same-group refresh', async () => {
    const api = planApi([page([{ planId: 'plan-1', title: 'Old', state: 'COLLABORATING' }], 'next'), page([{ planId: 'plan-2', title: 'Second', state: 'COLLABORATING' }]), page([{ planId: 'plan-1', title: 'Updated', state: 'COLLABORATING' }], 'next'), page([{ planId: 'plan-2', title: 'Second updated', state: 'COLLABORATING' }])]);
    const service = setup(api);
    await service.refresh('group-1'); await service.loadMore(); await service.refresh('group-1');
    expect(service.plans().map((plan) => plan.title)).toEqual(['Updated', 'Second updated']);
  });

  it('clears pages and cursors on actor change', async () => {
    const service = setup(planApi([page([{ planId: 'plan-1', title: 'First', state: 'COLLABORATING' }], 'next')]));
    await service.refresh('group-1');
    TestBed.inject(ActorScopeResetService).reset();
    expect(service.plans()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
  });

  it('uses one unavailable state for private 404 responses', async () => {
    const service = setup({ list: vi.fn(() => throwError(() => problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'))) });
    await service.refresh('unavailable-group');
    expect(service.state()).toBe('not-found');
  });

  it('clears loaded plans and paging when access is revoked', async () => {
    const api = { list: vi.fn(() => api.list.mock.calls.length === 1
      ? of(page([{ planId: 'plan-1', title: 'Visible before revocation', state: 'COLLABORATING' }], 'next'))
      : throwError(() => problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'))) };
    const service = setup(api);
    await service.refresh('group-1');
    await service.refresh('group-1');
    expect(service.plans()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
    expect(service.state()).toBe('not-found');
  });

  it('shows a safe error for a malformed cursor response', async () => {
    const service = setup({ list: vi.fn(() => throwError(() => problem(400, 'INVALID_CURSOR'))) });
    await service.refresh('group-1');
    expect(service.state()).toBe('error');
    expect(service.correlationId()).toBe('correlation-1');
  });

  it('discards a late old-actor response', async () => {
    const pending = new Subject<ApiHttpResult<PlanPage>>();
    const api = { list: vi.fn(() => api.list.mock.calls.length === 1 ? pending : of(page([{ planId: 'plan-2', title: 'New actor', state: 'COLLABORATING' }]))) };
    const service = setup(api);
    const oldLoad = service.refresh('group-1');
    TestBed.inject(ActorScopeResetService).reset();
    await service.refresh('group-2');
    pending.next(page([{ planId: 'plan-1', title: 'Old actor', state: 'COLLABORATING' }])); pending.complete(); await oldLoad;
    expect(service.plans().map((plan) => plan.planId)).toEqual(['plan-2']);
  });

  it('loads a new group while the old group request is pending', async () => {
    const pending = new Subject<ApiHttpResult<PlanPage>>();
    const api = { list: vi.fn(() => api.list.mock.calls.length === 1 ? pending : of(page([{ planId: 'plan-2', title: 'Second group', state: 'COLLABORATING' }]))) };
    const service = setup(api);
    const oldLoad = service.refresh('group-1');
    await service.refresh('group-2');
    pending.next(page([{ planId: 'plan-1', title: 'First group', state: 'COLLABORATING' }])); pending.complete(); await oldLoad;
    expect(service.plans().map((plan) => plan.planId)).toEqual(['plan-2']);
  });
});

function setup(api: Pick<PlanApi, 'list'>): PlanIndexService { TestBed.resetTestingModule(); TestBed.configureTestingModule({ providers: [{ provide: PlanApi, useValue: api }] }); return TestBed.inject(PlanIndexService); }
function planApi(pages: readonly ApiHttpResult<PlanPage>[]): Pick<PlanApi, 'list'> & { list: ReturnType<typeof vi.fn> } { let index = 0; return { list: vi.fn((): Observable<ApiHttpResult<PlanPage>> => of(pages[index++])) }; }
function page(items: NonNullable<PlanPage['items']>, nextCursor: string | null = null): ApiHttpResult<PlanPage> { return { body: { items, nextCursor: nextCursor ?? undefined }, status: 200, etag: null, location: null, correlationId: null }; }
function problem(status: number, code: string): ApiHttpError { return new ApiHttpError({ code, status, title: 'Request failed', detail: 'Do not render raw details.', violations: [], correlationId: 'correlation-1' }, null, null); }

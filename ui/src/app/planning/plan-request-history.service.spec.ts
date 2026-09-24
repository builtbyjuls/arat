import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupPublishedRequest, PlanApi, PublishedRequestPage } from './plan-api.service';
import { PlanRequestHistoryService } from './plan-request-history.service';

describe('PlanRequestHistoryService', () => {
  it('reads current status separately from descending historical versions', async () => {
    const api = fakeApi({
      current: request('request-2', 2, 'OPEN'),
      pages: [page([request('request-3', 3, 'SUPERSEDED'), request('request-2', 2, 'OPEN')])],
    });
    const service = setup(api);

    await service.load('plan-1');

    expect(api.readCurrentPublishedRequest).toHaveBeenCalledWith('plan-1');
    expect(api.listPublishedRequestHistory).toHaveBeenCalledWith('plan-1', null);
    expect(service.current()?.requestId).toBe('request-2');
    expect(service.items().map((item) => item.requestId)).toEqual(['request-3', 'request-2']);
  });

  it('keeps an empty current request distinct from private history access', async () => {
    const service = setup(fakeApi({ currentError: problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'), pages: [page([])] }));

    await service.load('plan-1');

    expect(service.currentState()).toBe('absent');
    expect(service.historyState()).toBe('ready');
  });

  it('appends cursor pages without duplicate request versions', async () => {
    const api = fakeApi({
      current: request('request-3', 3, 'OPEN'),
      pages: [page([request('request-3', 3, 'OPEN'), request('request-2', 2, 'SUPERSEDED')], 'next'), page([request('request-2', 2, 'SUPERSEDED'), request('request-1', 1, 'CLOSED')])],
    });
    const service = setup(api);

    await service.load('plan-1');
    await service.loadMore('plan-1');

    expect(api.listPublishedRequestHistory).toHaveBeenLastCalledWith('plan-1', 'next');
    expect(service.items().map((item) => item.requestId)).toEqual(['request-3', 'request-2', 'request-1']);
  });

  it.each(['OPEN', 'SUPERSEDED', 'CLOSED', 'CANCELLED'] as const)('reads immutable %s version detail', async (state) => {
    const detail = request(`request-${state}`, 1, state);
    const service = setup(fakeApi({ current: null, pages: [page([])], detail }));

    await service.readDetail('plan-1', detail.requestId ?? null);

    expect(service.detailState()).toBe('ready');
    expect(service.detail()?.state).toBe(state);
  });

  it('keeps authorized request reads when an unknown copied version is unavailable', async () => {
    const service = setup(fakeApi({
      current: request('request-1', 1, 'OPEN'), pages: [page([request('request-1', 1, 'OPEN')])],
      detailError: problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'),
    }));
    await service.load('plan-1');
    await service.readDetail('plan-1', 'request-foreign');

    expect(service.items().map((item) => item.requestId)).toEqual(['request-1']);
    expect(service.current()?.requestId).toBe('request-1');
    expect(service.detailProblemCode()).toBe('PRIVATE_RESOURCE_NOT_FOUND');
    expect(service.detailState()).toBe('error');
  });

  it('clears request data when group-private history access is revoked', async () => {
    const api = {
      readCurrentPublishedRequest: vi.fn(() => of(result(request('request-1', 1, 'OPEN')))),
      listPublishedRequestHistory: vi.fn(() => throwError(() => problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'))),
      readPublishedRequest: vi.fn(() => of(result<GroupPublishedRequest>(null))),
    };
    const service = setup(api);

    await service.load('plan-1');

    expect(service.items()).toEqual([]);
    expect(service.historyProblemCode()).toBe('PRIVATE_RESOURCE_NOT_FOUND');
  });

  it('refreshes an open selected version after replacement changes its lifecycle', async () => {
    let detail = request('request-1', 1, 'OPEN');
    const api = {
      readCurrentPublishedRequest: vi.fn(() => of(result(request('request-2', 2, 'OPEN')))),
      listPublishedRequestHistory: vi.fn(() => of(page([request('request-2', 2, 'OPEN'), detail]))),
      readPublishedRequest: vi.fn(() => of(result(detail))),
    };
    const service = setup(api);
    await service.readDetail('plan-1', 'request-1');
    detail = request('request-1', 1, 'SUPERSEDED');

    await service.load('plan-1');

    expect(service.detail()?.state).toBe('SUPERSEDED');
    expect(api.readPublishedRequest).toHaveBeenCalledTimes(2);
  });

  it('clears scoped data and discards late old-actor responses', async () => {
    const current = new Subject<ApiHttpResult<GroupPublishedRequest>>();
    const history = new Subject<ApiHttpResult<PublishedRequestPage>>();
    const api = {
      readCurrentPublishedRequest: vi.fn(() => current),
      listPublishedRequestHistory: vi.fn(() => history),
      readPublishedRequest: vi.fn(),
    };
    const service = setup(api);
    const oldLoad = service.load('plan-1');

    TestBed.inject(ActorScopeResetService).reset();
    current.next(result(request('request-old', 1, 'OPEN')));
    current.complete();
    history.next(page([request('request-old', 1, 'OPEN')]));
    history.complete();
    await oldLoad;

    expect(service.current()).toBeNull();
    expect(service.items()).toEqual([]);
  });
});

function setup(api: Pick<PlanApi, 'readCurrentPublishedRequest' | 'listPublishedRequestHistory' | 'readPublishedRequest'>): PlanRequestHistoryService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: PlanApi, useValue: api }] });
  return TestBed.inject(PlanRequestHistoryService);
}

function fakeApi(options: {
  current?: GroupPublishedRequest | null;
  currentError?: ApiHttpError;
  pages: readonly ApiHttpResult<PublishedRequestPage>[];
  detail?: GroupPublishedRequest;
  detailError?: ApiHttpError;
}) {
  let pageIndex = 0;
  return {
    readCurrentPublishedRequest: vi.fn(() => options.currentError === undefined
      ? of(result<GroupPublishedRequest>(options.current ?? null)) : throwError(() => options.currentError)),
    listPublishedRequestHistory: vi.fn(() => of(options.pages[pageIndex++])),
    readPublishedRequest: vi.fn(() => options.detailError === undefined
      ? of(result<GroupPublishedRequest>(options.detail ?? null)) : throwError(() => options.detailError)),
  };
}

function request(requestId: string, requestVersion: number, state: 'OPEN' | 'SUPERSEDED' | 'CLOSED' | 'CANCELLED'): GroupPublishedRequest {
  return { requestId, requestVersion, state, actionable: state === 'OPEN', timeZone: 'Asia/Manila' };
}

function page(items: readonly GroupPublishedRequest[], nextCursor?: string): ApiHttpResult<PublishedRequestPage> {
  return result({ items: [...items], nextCursor });
}

function result<T>(body: T | null): ApiHttpResult<T> {
  return { body, status: 200, etag: null, location: null, correlationId: null };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({ code, status, title: 'Request failed', detail: 'Do not render raw details.', violations: [], correlationId: 'correlation-1' }, null, null);
}

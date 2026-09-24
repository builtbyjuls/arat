import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderPublishedRequest, ProviderRequestFeed } from './provider-api.service';
import { ProviderRequestFeedService } from './provider-request-feed.service';

describe('ProviderRequestFeedService', () => {
  it('pages only the selected provider feed without duplicate request IDs', async () => {
    const api = fakeApi([page([request('request-2', 'OPEN')], 'next'), page([request('request-2', 'OPEN'), request('request-1', 'CLOSED')])]);
    const service = setup(api);

    await service.refresh('provider-1');
    await service.loadMore('provider-1');

    expect(api.listRequestFeed).toHaveBeenNthCalledWith(1, 'provider-1');
    expect(api.listRequestFeed).toHaveBeenNthCalledWith(2, 'provider-1', 'next');
    expect(service.items().map((item) => item.requestId)).toEqual(['request-2', 'request-1']);
  });

  it('clears loaded feed and detail when paging loses provider authorization', async () => {
    const api = {
      listRequestFeed: vi.fn()
        .mockReturnValueOnce(of(page([request('request-1', 'OPEN')], 'next')))
        .mockReturnValueOnce(throwError(() => problem('PRIVATE_RESOURCE_NOT_FOUND'))),
      readPublishedRequest: vi.fn(() => of(result(request('request-1', 'OPEN')))),
    };
    const service = setup(api);
    await service.refresh('provider-1');
    await service.readDetail('provider-1', 'request-1');

    await service.loadMore('provider-1');

    expect(service.items()).toEqual([]);
    expect(service.detail()).toBeNull();
    expect(service.detailState()).toBe('absent');
  });

  it('revalidates selected detail after refresh and clears an old restored grant', async () => {
    const api = {
      listRequestFeed: vi.fn(() => of(page([]))),
      readPublishedRequest: vi.fn()
        .mockReturnValueOnce(of(result(request('request-1', 'OPEN'))))
        .mockReturnValueOnce(throwError(() => problem('PRIVATE_RESOURCE_NOT_FOUND'))),
    };
    const service = setup(api);
    await service.readDetail('provider-1', 'request-1');

    await service.refresh('provider-1');

    expect(api.readPublishedRequest).toHaveBeenCalledTimes(2);
    expect(service.detail()).toBeNull();
    expect(service.detailState()).toBe('error');
  });

  it('revalidates selected detail after refresh when its lifecycle changes', async () => {
    const api = {
      listRequestFeed: vi.fn(() => of(page([request('request-1', 'CLOSED')]))),
      readPublishedRequest: vi.fn()
        .mockReturnValueOnce(of(result(request('request-1', 'OPEN'))))
        .mockReturnValueOnce(of(result(request('request-1', 'CLOSED')))),
    };
    const service = setup(api);
    await service.readDetail('provider-1', 'request-1');

    await service.refresh('provider-1');

    expect(service.detail()?.state).toBe('CLOSED');
  });

  it('does not replace a newer query-selected detail while refresh is pending', async () => {
    const feed = new Subject<ApiHttpResult<ProviderRequestFeed>>();
    const api = {
      listRequestFeed: vi.fn(() => feed),
      readPublishedRequest: vi.fn((_providerId: string, requestId: string) => of(result(request(requestId, 'OPEN')))),
    };
    const service = setup(api);
    await service.readDetail('provider-1', 'request-a');
    const refreshing = service.refresh('provider-1');
    await service.readDetail('provider-1', 'request-b');
    feed.next(page([]));
    feed.complete();
    await refreshing;

    expect(service.detail()?.requestId).toBe('request-b');
    expect(api.readPublishedRequest).toHaveBeenCalledTimes(2);
  });

  it('resolves detail loading after a failed refresh and recovers on retry', async () => {
    const api = {
      listRequestFeed: vi.fn()
        .mockReturnValueOnce(throwError(() => problem('INTERNAL_ERROR')))
        .mockReturnValueOnce(of(page([request('request-1', 'OPEN')]))),
      readPublishedRequest: vi.fn(() => of(result(request('request-1', 'OPEN')))),
    };
    const service = setup(api);
    await service.readDetail('provider-1', 'request-1');

    await service.refresh('provider-1');
    expect(service.detailState()).toBe('error');

    await service.refresh('provider-1');
    expect(service.detailState()).toBe('ready');
    expect(service.detail()?.requestId).toBe('request-1');
  });

  it('does not retain request data when the provider feed access is revoked', async () => {
    const service = setup({
      listRequestFeed: vi.fn(() => throwError(() => problem('PRIVATE_RESOURCE_NOT_FOUND'))),
      readPublishedRequest: vi.fn(),
    });

    await service.refresh('provider-unrelated');

    expect(service.state()).toBe('error');
    expect(service.items()).toEqual([]);
    expect(service.detail()).toBeNull();
  });

  it('treats suspended or restored old provider access as the same unavailable response', async () => {
    const service = setup({
      listRequestFeed: vi.fn(() => throwError(() => problem('PRIVATE_RESOURCE_NOT_FOUND'))),
      readPublishedRequest: vi.fn(),
    });

    await service.refresh('provider-suspended');
    await service.refresh('provider-restored-with-old-grant');

    expect(service.items()).toEqual([]);
    expect(service.state()).toBe('error');
  });

  it('keeps the feed while an unrelated copied request ID is unavailable', async () => {
    const service = setup({
      listRequestFeed: vi.fn(() => of(page([request('request-1', 'OPEN')]))),
      readPublishedRequest: vi.fn(() => throwError(() => problem('PRIVATE_RESOURCE_NOT_FOUND'))),
    });
    await service.refresh('provider-1');
    await service.readDetail('provider-1', 'request-not-recipient');

    expect(service.items().map((item) => item.requestId)).toEqual(['request-1']);
    expect(service.detail()).toBeNull();
    expect(service.detailState()).toBe('error');
  });

  it('clears scoped requests and ignores late responses after an actor switch', async () => {
    const feed = new Subject<ApiHttpResult<ProviderRequestFeed>>();
    const service = setup({ listRequestFeed: vi.fn(() => feed), readPublishedRequest: vi.fn() });
    const pending = service.refresh('provider-1');

    TestBed.inject(ActorScopeResetService).reset();
    feed.next(page([request('request-old', 'OPEN')]));
    feed.complete();
    await pending;

    expect(service.items()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
  });

  it.each(['OPEN', 'SUPERSEDED', 'CLOSED', 'CANCELLED'] as const)('reads provider-safe %s detail', async (state) => {
    const detail = request(`request-${state}`, state);
    const service = setup({ listRequestFeed: vi.fn(() => of(page([]))), readPublishedRequest: vi.fn(() => of(result(detail))) });

    await service.readDetail('provider-1', detail.requestId ?? 'missing');

    expect(service.detailState()).toBe('ready');
    expect(service.detail()?.state).toBe(state);
  });
});

function setup(api: Pick<ProviderApi, 'listRequestFeed' | 'readPublishedRequest'>): ProviderRequestFeedService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
  return TestBed.inject(ProviderRequestFeedService);
}

function fakeApi(pages: readonly ApiHttpResult<ProviderRequestFeed>[]) {
  let index = 0;
  return { listRequestFeed: vi.fn(() => of(pages[index++])), readPublishedRequest: vi.fn(() => of(result<ProviderPublishedRequest>(null))) };
}

function request(requestId: string, state: 'OPEN' | 'SUPERSEDED' | 'CLOSED' | 'CANCELLED'): ProviderPublishedRequest {
  return {
    requestId, state, actionable: state === 'OPEN', category: 'COURT', timeZone: 'Asia/Manila',
    offerDeadline: '2027-01-09T09:00:00Z', headcount: { minimum: 4, maximum: 10 },
    budget: { currency: 'PHP', minimumAmount: '100.00', maximumAmount: '200.00' },
  };
}

function page(items: readonly ProviderPublishedRequest[], nextCursor?: string): ApiHttpResult<ProviderRequestFeed> {
  return result({ items: [...items], nextCursor });
}

function result<T>(body: T | null): ApiHttpResult<T> {
  return { body, status: 200, etag: null, location: null, correlationId: null };
}

function problem(code: string): ApiHttpError {
  return new ApiHttpError({ code, status: 404, title: 'Unavailable', detail: 'Do not render raw details.', violations: [], correlationId: 'correlation-1' }, null, null);
}

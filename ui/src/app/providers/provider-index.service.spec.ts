import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderPage, ProviderSummary } from './provider-api.service';
import { ProviderIndexService } from './provider-index.service';

describe('ProviderIndexService', () => {
  it('keeps each provider once while paging active staff contexts', async () => {
    const api = providerApi([
      page([provider('provider-1', 'First')], 'next-page'),
      page([provider('provider-1', 'First'), provider('provider-2', 'Second', 'STAFF')]),
    ]);
    const service = setup(api);

    await service.refresh();
    await service.loadMore();

    expect(api.list).toHaveBeenNthCalledWith(2, 'next-page');
    expect(service.providers().map((provider) => provider.providerId)).toEqual(['provider-1', 'provider-2']);
    expect(service.nextCursor()).toBeNull();
  });

  it('clears provider pages and cursors on actor change', async () => {
    const service = setup(providerApi([page([provider('provider-1', 'First')], 'next-page')]));

    await service.refresh();
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.providers()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
  });

  it('uses the server empty page for actors without provider memberships', async () => {
    const service = setup(providerApi([page([])]));

    await service.refresh();

    expect(service.state()).toBe('empty');
  });

  it('discards a late old-actor response after the new actor reloads', async () => {
    const oldActorRequest = new Subject<ApiHttpResult<ProviderPage>>();
    const api = {
      list: vi.fn(() => api.list.mock.calls.length === 1
        ? oldActorRequest
        : of(page([provider('provider-2', 'New actor')]))),
    };
    const service = setup(api);

    const oldRefresh = service.refresh();
    TestBed.inject(ActorScopeResetService).reset();
    await service.refresh();
    oldActorRequest.next(page([provider('provider-1', 'Old actor')]));
    oldActorRequest.complete();
    await oldRefresh;

    expect(service.providers().map((provider) => provider.providerId)).toEqual(['provider-2']);
  });

  it('keeps problem details bounded to a safe error state and correlation ID', async () => {
    const error = new ApiHttpError({
      code: 'INVALID_CURSOR', status: 400, title: 'Invalid cursor', detail: 'Raw details must not be rendered.', violations: [], correlationId: 'correlation-1',
    }, null, null);
    const service = setup({ list: vi.fn(() => throwError(() => error)) });

    await service.refresh();

    expect(service.state()).toBe('error');
    expect(service.correlationId()).toBe('correlation-1');
  });
});

function setup(api: Pick<ProviderApi, 'list'>): ProviderIndexService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
  return TestBed.inject(ProviderIndexService);
}

function providerApi(pages: readonly ApiHttpResult<ProviderPage>[]): Pick<ProviderApi, 'list'> & { list: ReturnType<typeof vi.fn> } {
  let pageIndex = 0;
  return { list: vi.fn((): Observable<ApiHttpResult<ProviderPage>> => of(pages[pageIndex++])) };
}

function provider(
  providerId: string,
  displayName: string,
  callerStaffRole: 'ADMIN' | 'STAFF' = 'ADMIN',
): ProviderSummary {
  return { providerId, displayName, callerStaffRole, verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 1 };
}

function page(items: NonNullable<ProviderPage['items']>, nextCursor: string | null = null): ApiHttpResult<ProviderPage> {
  return { body: { items, nextCursor: nextCursor ?? undefined }, status: 200, etag: null, location: null, correlationId: null };
}

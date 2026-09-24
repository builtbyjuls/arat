import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi, GroupPage } from './group-api.service';
import { GroupIndexService } from './group-index.service';

describe('GroupIndexService', () => {
  it('replaces the displayed groups with the server response on refresh', async () => {
    const api = groupApi([page([{ groupId: 'group-1', name: 'Weekend crew', role: 'ORGANIZER' }])]);
    const service = setup(api);

    await service.refresh();

    expect(api.list).toHaveBeenCalledWith(null);
    expect(service.groups()).toEqual([{ groupId: 'group-1', name: 'Weekend crew', role: 'ORGANIZER' }]);
    expect(service.state()).toBe('ready');
  });

  it('shows the empty state for an empty page', async () => {
    const service = setup(groupApi([page([])]));

    await service.refresh();

    expect(service.groups()).toEqual([]);
    expect(service.state()).toBe('empty');
  });

  it('appends a later cursor page without duplicate group cards', async () => {
    const api = groupApi([
      page([{ groupId: 'group-1', name: 'First', role: 'MEMBER' }], 'next-page'),
      page([
        { groupId: 'group-1', name: 'First', role: 'MEMBER' },
        { groupId: 'group-2', name: 'Second', role: 'ORGANIZER' },
      ]),
    ]);
    const service = setup(api);

    await service.refresh();
    await service.loadMore();

    expect(api.list).toHaveBeenNthCalledWith(2, 'next-page');
    expect(service.groups().map((group) => group.groupId)).toEqual(['group-1', 'group-2']);
    expect(service.nextCursor()).toBeNull();
  });

  it('discards cached pages and cursors when the actor changes', async () => {
    const api = groupApi([page([{ groupId: 'group-1', name: 'First', role: 'MEMBER' }], 'next-page')]);
    const service = setup(api);

    await service.refresh();
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.groups()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
  });

  it('revalidates every loaded page when returning to the group index', async () => {
    const api = groupApi([
      page([{ groupId: 'group-1', name: 'First', role: 'MEMBER' }], 'next-page'),
      page([{ groupId: 'group-2', name: 'Second', role: 'MEMBER' }]),
      page([{ groupId: 'group-1', name: 'First updated', role: 'MEMBER' }], 'next-page'),
      page([{ groupId: 'group-2', name: 'Second updated', role: 'MEMBER' }]),
    ]);
    const service = setup(api);

    await service.refresh();
    await service.loadMore();
    await service.refresh();

    expect(api.list).toHaveBeenNthCalledWith(3, null);
    expect(api.list).toHaveBeenNthCalledWith(4, 'next-page');
    expect(service.groups().map((group) => group.name)).toEqual(['First updated', 'Second updated']);
  });

  it('serializes refresh and paging while a response is pending', async () => {
    const refreshPending = new Subject<ApiHttpResult<GroupPage>>();
    const pagingPending = new Subject<ApiHttpResult<GroupPage>>();
    const api = {
      list: vi.fn(() => {
        switch (api.list.mock.calls.length) {
          case 1:
            return of(page([{ groupId: 'group-1', name: 'First', role: 'MEMBER' }], 'next-page'));
          case 2:
            return refreshPending;
          default:
            return pagingPending;
        }
      }),
    };
    const service = setup(api);

    await service.refresh();
    const refresh = service.refresh();
    await service.loadMore();

    expect(api.list).toHaveBeenCalledTimes(2);
    refreshPending.next(page([{ groupId: 'group-1', name: 'Refreshed', role: 'MEMBER' }], 'next-page'));
    refreshPending.complete();
    await refresh;
    expect(service.state()).toBe('ready');

    const loadMore = service.loadMore();
    await service.refresh();

    expect(api.list).toHaveBeenCalledTimes(3);
    pagingPending.next(page([{ groupId: 'group-2', name: 'Second', role: 'MEMBER' }]));
    pagingPending.complete();
    await loadMore;
    expect(service.groups().map((group) => group.groupId)).toEqual(['group-1', 'group-2']);
  });

  it('ignores an old actor failure after the new actor has loaded groups', async () => {
    const oldActorRequest = new Subject<ApiHttpResult<GroupPage>>();
    const api = {
      list: vi.fn(() => api.list.mock.calls.length === 1
        ? oldActorRequest
        : of(page([{ groupId: 'group-2', name: 'New actor group', role: 'MEMBER' }]))),
    };
    const service = setup(api);

    const oldRefresh = service.refresh();
    TestBed.inject(ActorScopeResetService).reset();
    await service.refresh();
    oldActorRequest.error(new ApiHttpError({
      code: 'INTERNAL_ERROR',
      status: 500,
      title: 'Internal error',
      detail: 'Old actor failure',
      violations: [],
      correlationId: 'old-correlation',
    }, null, null));
    await oldRefresh;

    expect(service.groups().map((group) => group.groupId)).toEqual(['group-2']);
    expect(service.state()).toBe('ready');
    expect(service.correlationId()).toBeNull();
  });

  it('keeps problem details bounded to the safe error state and correlation ID', async () => {
    const error = new ApiHttpError({
      code: 'PRIVATE_RESOURCE_NOT_FOUND',
      status: 404,
      title: 'Private resource not found',
      detail: 'Raw details must not be rendered.',
      violations: [],
      correlationId: 'correlation-1',
    }, null, null);
    const service = setup({ list: vi.fn(() => throwError(() => error)) });

    await service.refresh();

    expect(service.state()).toBe('error');
    expect(service.correlationId()).toBe('correlation-1');
  });
});

function setup(api: Pick<GroupApi, 'list'>): GroupIndexService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: GroupApi, useValue: api }] });
  return TestBed.inject(GroupIndexService);
}

function groupApi(pages: readonly ApiHttpResult<GroupPage>[]): Pick<GroupApi, 'list'> & { list: ReturnType<typeof vi.fn> } {
  let pageIndex = 0;
  return {
    list: vi.fn((): Observable<ApiHttpResult<GroupPage>> => of(pages[pageIndex++])),
  };
}

function page(
  items: NonNullable<GroupPage['items']>,
  nextCursor: string | null = null,
): ApiHttpResult<GroupPage> {
  return {
    body: { items, nextCursor: nextCursor ?? undefined },
    status: 200,
    etag: null,
    location: null,
    correlationId: null,
  };
}

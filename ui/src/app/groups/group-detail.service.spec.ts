import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { LocalActorSession } from '../identity/local-actor-session.service';
import { GroupApi, GroupDetail } from './group-api.service';
import { GroupDetailService } from './group-detail.service';

describe('GroupDetailService', () => {
  it('loads the server-authorized detail and preserves its ETag and caller role', async () => {
    const api = { read: vi.fn(() => of(detailResult())) };
    const service = setup(api);

    await service.load('group-1');

    expect(api.read).toHaveBeenCalledWith('group-1');
    expect(service.group()?.name).toBe('Weekend crew');
    expect(service.etag()).toBe('"7"');
    expect(service.callerRole()).toBe('ORGANIZER');
    expect(service.state()).toBe('ready');
  });

  it('presents every private 404 as unavailable without retaining resource data', async () => {
    const api = { read: vi.fn(() => throwError(() => problem(404, 'PRIVATE_RESOURCE_NOT_FOUND'))) };
    const service = setup(api);

    await service.load('unknown-or-revoked-group');

    expect(service.state()).toBe('not-found');
    expect(service.group()).toBeNull();
    expect(service.etag()).toBeNull();
  });

  it('does not present an incomplete successful response as a loaded group', async () => {
    const service = setup({ read: vi.fn(() => of({ ...detailResult(), body: null })) });

    await service.load('group-1');

    expect(service.state()).toBe('error');
    expect(service.group()).toBeNull();
    expect(service.etag()).toBeNull();
  });

  it('clears the old ETag while a changed resource is loading', async () => {
    const nextRead = new Subject<ApiHttpResult<GroupDetail>>();
    const api = {
      read: vi.fn((): Observable<ApiHttpResult<GroupDetail>> => api.read.mock.calls.length === 1
        ? of(detailResult())
        : nextRead),
    };
    const service = setup(api);

    await service.load('group-1');
    const changedResource = service.load('group-2');

    expect(service.group()).toBeNull();
    expect(service.etag()).toBeNull();
    nextRead.next(detailResult({ groupId: 'group-2', name: 'Second group' }));
    nextRead.complete();
    await changedResource;

    expect(service.group()?.groupId).toBe('group-2');
  });

  it('clears private detail and ETag when the actor scope changes', async () => {
    const service = setup({ read: vi.fn(() => of(detailResult())) });

    await service.load('group-1');
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.group()).toBeNull();
    expect(service.etag()).toBeNull();
  });
});

function setup(api: Pick<GroupApi, 'read'>): GroupDetailService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: GroupApi, useValue: api },
      { provide: LocalActorSession, useValue: { actor: signal({ actorId: 'actor-1' }) } },
    ],
  });
  return TestBed.inject(GroupDetailService);
}

function detailResult(overrides: Partial<GroupDetail> = {}): ApiHttpResult<GroupDetail> {
  return {
    body: {
      groupId: 'group-1',
      name: 'Weekend crew',
      description: 'Saturday activities',
      members: [
        { accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' },
        { accountId: 'actor-2', displayName: 'Bea Member', role: 'MEMBER' },
      ],
      version: 7,
      ...overrides,
    },
    status: 200,
    etag: '"7"',
    location: null,
    correlationId: null,
  };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({
    code,
    status,
    title: 'Private resource not found',
    detail: 'Do not render this raw detail.',
    violations: [],
    correlationId: 'correlation-1',
  }, null, null);
}

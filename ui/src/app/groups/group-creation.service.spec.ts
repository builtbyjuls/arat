import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi, CreateGroupRequest, GroupRepresentation } from './group-api.service';
import { GroupCreationService } from './group-creation.service';

describe('GroupCreationService', () => {
  it('creates one intent for a rapid duplicate action', async () => {
    const response = new Subject<ApiHttpResult<GroupRepresentation>>();
    const api = fakeApi([response]);
    const service = setup(api);

    const first = service.create({ name: 'Weekend crew' });
    const second = await service.create({ name: 'Weekend crew' });

    expect(api.createIntent).toHaveBeenCalledOnce();
    expect(api.create).toHaveBeenCalledOnce();
    expect(second).toBeNull();

    response.next(result());
    response.complete();
    await first;
  });

  it('retries a transport failure with the exact original intent key', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR')), of(result())]);
    const service = setup(api);

    await service.create({ name: 'Weekend crew', description: 'Saturday activities' });
    await service.retry();

    expect(api.create).toHaveBeenCalledTimes(2);
    expect(api.create.mock.calls[0][0]).toBe(api.create.mock.calls[1][0]);
    expect(api.create.mock.calls[0][0].idempotencyKey).toBe('create-key-1');
  });

  it('does not retry after an idempotency key misuse response', async () => {
    const api = fakeApi([throwError(() => problem(409, 'IDEMPOTENCY_KEY_REUSED'))]);
    const service = setup(api);

    await service.create({ name: 'Weekend crew' });
    const retry = await service.retry();

    expect(service.state()).toBe('error');
    expect(retry).toBeNull();
    expect(api.create).toHaveBeenCalledOnce();
  });

  it('retains safe validation violations for their matching form controls', async () => {
    const api = fakeApi([throwError(() => new ApiHttpError({
      code: 'VALIDATION_FAILED',
      status: 422,
      title: 'Validation failed',
      detail: 'Request failed.',
      violations: [{ field: 'name', message: 'Name is required.' }],
      correlationId: 'correlation-1',
    }, null, null))]);
    const service = setup(api);

    await service.create({ name: 'Weekend crew' });

    expect(service.problemCode()).toBe('VALIDATION_FAILED');
    expect(service.violationFor('name')).toBe('Name is required.');
    expect(service.violationFor('description')).toBeNull();
  });

  it('discards a retryable intent when the actor scope changes', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR'))]);
    const service = setup(api);

    await service.create({ name: 'Weekend crew' });
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.state()).toBe('idle');
    await expect(service.retry()).resolves.toBeNull();
  });

  it('ignores an old actor completion while the replacement actor is submitting', async () => {
    const oldActor = new Subject<ApiHttpResult<GroupRepresentation>>();
    const newActor = new Subject<ApiHttpResult<GroupRepresentation>>();
    const api = fakeApi([oldActor, newActor]);
    const service = setup(api);

    const oldCreate = service.create({ name: 'Ari group' });
    TestBed.inject(ActorScopeResetService).reset();
    const newCreate = service.create({ name: 'Bea group' });
    oldActor.error(problem(0, 'UNKNOWN_ERROR'));
    await oldCreate;

    expect(service.state()).toBe('submitting');
    await expect(service.create({ name: 'Duplicate Bea group' })).resolves.toBeNull();
    expect(api.create).toHaveBeenCalledTimes(2);

    newActor.next(result());
    newActor.complete();
    await newCreate;
  });
});

function setup(api: Pick<GroupApi, 'create' | 'createIntent'>): GroupCreationService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: GroupApi, useValue: api }] });
  return TestBed.inject(GroupCreationService);
}

function fakeApi(
  responses: readonly Observable<ApiHttpResult<GroupRepresentation>>[],
): Pick<GroupApi, 'create' | 'createIntent'> & { create: ReturnType<typeof vi.fn>; createIntent: ReturnType<typeof vi.fn> } {
  let requestIndex = 0;
  return {
    createIntent: vi.fn((_request: CreateGroupRequest) => ({ idempotencyKey: `create-key-${requestIndex + 1}` })),
    create: vi.fn((_intent: IdempotentMutationIntent<CreateGroupRequest>) => responses[requestIndex++]),
  };
}

function result(): ApiHttpResult<GroupRepresentation> {
  return {
    body: { groupId: 'group-1', name: 'Weekend crew', version: 1 },
    status: 201,
    etag: '"1"',
    location: '/api/v1/groups/group-1',
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

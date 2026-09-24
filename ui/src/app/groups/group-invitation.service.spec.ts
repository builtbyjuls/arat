import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateInvitationRequest, GroupApi, InvitationRepresentation } from './group-api.service';
import { GroupDetailService } from './group-detail.service';
import { GroupInvitationService } from './group-invitation.service';

describe('GroupInvitationService', () => {
  it('keeps a returned token only in transient state and clears it when the actor changes', async () => {
    const intent = { idempotencyKey: 'invite-key' } as IdempotentMutationIntent<CreateInvitationRequest>;
    const api = {
      createInvitationIntent: vi.fn(() => intent),
      createInvitation: vi.fn(() => of(invitationResult('raw-invitation-token'))),
    };
    const service = setup(api);

    await service.create('group-1', inviteRequest());

    expect(service.token()).toBe('raw-invitation-token');
    expect(service.correlationId()).toBeNull();
    TestBed.inject(ActorScopeResetService).reset();

    expect(service.token()).toBeNull();
    expect(service.state()).toBe('idle');
  });

  it('discards a late invitation result after the actor changes', async () => {
    const response = new Subject<ApiHttpResult<InvitationRepresentation>>();
    const api = {
      createInvitationIntent: vi.fn(() => ({ idempotencyKey: 'invite-key' })),
      createInvitation: vi.fn(() => response),
    };
    const service = setup(api);
    const creating = service.create('group-1', inviteRequest());

    TestBed.inject(ActorScopeResetService).reset();
    response.next(invitationResult('raw-invitation-token'));
    response.complete();
    await creating;

    expect(service.token()).toBeNull();
    expect(service.state()).toBe('idle');
  });

  it('replays exactly one failed transport intent with the same key and command', async () => {
    const intent = { idempotencyKey: 'invite-key' } as IdempotentMutationIntent<CreateInvitationRequest>;
    const api = {
      createInvitationIntent: vi.fn(() => intent),
      createInvitation: vi.fn()
        .mockReturnValueOnce(throwError(() => problem(0, 'UNKNOWN_ERROR', 'correlation-1')))
        .mockReturnValueOnce(of(invitationResult('raw-invitation-token'))),
    };
    const service = setup(api);

    await service.create('group-1', inviteRequest());
    await service.retry();

    expect(api.createInvitationIntent).toHaveBeenCalledOnce();
    expect(api.createInvitation).toHaveBeenCalledTimes(2);
    expect(api.createInvitation).toHaveBeenNthCalledWith(1, intent);
    expect(api.createInvitation).toHaveBeenNthCalledWith(2, intent);
    expect(service.token()).toBe('raw-invitation-token');
  });

  it('does not retain a token when an application error, including key misuse, is returned', async () => {
    const api = {
      createInvitationIntent: vi.fn(() => ({ idempotencyKey: 'invite-key' })),
      createInvitation: vi.fn(() => throwError(() => problem(409, 'IDEMPOTENCY_KEY_REUSED', 'correlation-1'))),
    };
    const service = setup(api);

    await service.create('group-1', inviteRequest());

    expect(service.state()).toBe('error');
    expect(service.token()).toBeNull();
    expect(service.correlationId()).toBe('correlation-1');
    expect(service.errorCode()).toBe('IDEMPOTENCY_KEY_REUSED');
  });

  it('preserves server validation violations without retaining the invitation token', async () => {
    const api = {
      createInvitationIntent: vi.fn(() => ({ idempotencyKey: 'invite-key' })),
      createInvitation: vi.fn(() => throwError(() => problem(422, 'VALIDATION_FAILED', 'correlation-1', [
        { field: 'expiryHours', message: 'must be at most 168' },
      ]))),
    };
    const service = setup(api);

    await service.create('group-1', inviteRequest());

    expect(service.errorCode()).toBe('VALIDATION_FAILED');
    expect(service.violationFor('expiryHours')).toEqual({ field: 'expiryHours', message: 'must be at most 168' });
    expect(service.token()).toBeNull();
  });
});

function setup(api: Pick<GroupApi, 'createInvitation' | 'createInvitationIntent'>): GroupInvitationService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: GroupApi, useValue: api },
      { provide: GroupDetailService, useValue: { load: vi.fn().mockResolvedValue(undefined) } },
    ],
  });
  return TestBed.inject(GroupInvitationService);
}

function inviteRequest(): CreateInvitationRequest {
  return { inviteeAccountId: '10000000-0000-4000-8000-000000000002', expiryHours: 72 };
}

function invitationResult(token: string): ApiHttpResult<InvitationRepresentation> {
  return {
    body: {
      groupId: 'group-1',
      inviteId: 'invite-1',
      inviteeAccountId: '10000000-0000-4000-8000-000000000002',
      expiresAt: '2027-01-01T00:00:00Z',
      token,
    },
    status: 201,
    etag: null,
    location: '/api/v1/groups/group-1/invites/invite-1',
    correlationId: null,
  };
}

function problem(
  status: number,
  code: string,
  correlationId: string,
  violations: readonly { field: string; message: string }[] = [],
): ApiHttpError {
  return new ApiHttpError({
    code,
    status,
    title: 'Request failed',
    detail: 'Safe error message.',
    violations,
    correlationId,
  }, null, null);
}

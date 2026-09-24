import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  InvitationAcceptanceIntent,
  InvitationAcceptanceTokenMismatchError,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi, GroupMembership } from './group-api.service';
import { GroupInvitationAcceptanceService } from './group-invitation-acceptance.service';

describe('GroupInvitationAcceptanceService', () => {
  it('accepts a pasted token and retains no token after the result', async () => {
    const intent = { idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent;
    const api = {
      beginInvitationAcceptance: vi.fn(async () => intent),
      acceptInvitation: vi.fn(() => of(membershipResult())),
    };
    const service = setup(api);

    await expect(service.accept('raw-invitation-token')).resolves.toEqual(membershipResult().body);

    expect(api.acceptInvitation).toHaveBeenCalledWith(intent, 'raw-invitation-token');
    expect(JSON.stringify(service)).not.toContain('raw-invitation-token');
    expect(service.state()).toBe('idle');
  });

  it.each([
    ['already consumed', 409, 'INVITATION_UNAVAILABLE'],
    ['expired', 409, 'INVITATION_UNAVAILABLE'],
    ['revoked', 409, 'INVITATION_UNAVAILABLE'],
    ['wrong account', 403, 'ACCESS_DENIED'],
    ['malformed token', 400, 'MALFORMED_REQUEST'],
  ])('shows the same unavailable state for a %s token', async (_outcome, status, code) => {
    const api = {
      beginInvitationAcceptance: vi.fn(async () => ({ idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent)),
      acceptInvitation: vi.fn(() => throwError(() => problem(status, code))),
    };
    const service = setup(api);

    await service.accept('raw-invitation-token');

    expect(service.state()).toBe('unavailable');
    expect(service.correlationId()).toBe('correlation-1');
    expect(JSON.stringify(service)).not.toContain('raw-invitation-token');
  });

  it('shows idempotency key reuse as a conflict without retaining the token', async () => {
    const api = {
      beginInvitationAcceptance: vi.fn(async () => ({ idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent)),
      acceptInvitation: vi.fn(() => throwError(() => problem(409, 'IDEMPOTENCY_KEY_REUSED'))),
    };
    const service = setup(api);

    await service.accept('raw-invitation-token');

    expect(service.state()).toBe('conflict');
    expect(JSON.stringify(service)).not.toContain('raw-invitation-token');
  });

  it('retries a transport failure with the same intent after the token is pasted again', async () => {
    const intent = { idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent;
    const api = {
      beginInvitationAcceptance: vi.fn(async () => intent),
      acceptInvitation: vi.fn()
        .mockReturnValueOnce(throwError(() => problem(0, 'UNKNOWN_ERROR')))
        .mockReturnValueOnce(of(membershipResult())),
    };
    const service = setup(api);

    await service.accept('raw-invitation-token');
    await service.accept('raw-invitation-token');

    expect(api.beginInvitationAcceptance).toHaveBeenCalledOnce();
    expect(api.acceptInvitation).toHaveBeenNthCalledWith(1, intent, 'raw-invitation-token');
    expect(api.acceptInvitation).toHaveBeenNthCalledWith(2, intent, 'raw-invitation-token');
    expect(service.state()).toBe('idle');
  });

  it('keeps the retry intent when the pasted token does not match it', async () => {
    const intent = { idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent;
    const api = {
      beginInvitationAcceptance: vi.fn(async () => intent),
      acceptInvitation: vi.fn()
        .mockReturnValueOnce(throwError(() => problem(0, 'UNKNOWN_ERROR')))
        .mockReturnValueOnce(throwError(() => new InvitationAcceptanceTokenMismatchError()))
        .mockReturnValueOnce(of(membershipResult())),
    };
    const service = setup(api);

    await service.accept('raw-invitation-token');
    await service.accept('mistyped-token');
    await service.accept('raw-invitation-token');

    expect(api.beginInvitationAcceptance).toHaveBeenCalledOnce();
    expect(api.acceptInvitation).toHaveBeenNthCalledWith(3, intent, 'raw-invitation-token');
    expect(service.state()).toBe('idle');
  });

  it('clears a pending intent and discards late results when the actor changes', async () => {
    const result = new Subject<ApiHttpResult<GroupMembership>>();
    const api = {
      beginInvitationAcceptance: vi.fn(async () => ({ idempotencyKey: 'accept-key' } as InvitationAcceptanceIntent)),
      acceptInvitation: vi.fn(() => result),
    };
    const service = setup(api);
    const accepting = service.accept('raw-invitation-token');
    await Promise.resolve();

    TestBed.inject(ActorScopeResetService).reset();
    result.next(membershipResult());
    result.complete();

    await expect(accepting).resolves.toBeNull();
    expect(service.state()).toBe('idle');
    expect(JSON.stringify(service)).not.toContain('raw-invitation-token');
  });
});

function setup(api: Pick<GroupApi, 'acceptInvitation' | 'beginInvitationAcceptance'>): GroupInvitationAcceptanceService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: GroupApi, useValue: api }] });
  return TestBed.inject(GroupInvitationAcceptanceService);
}

function membershipResult(): ApiHttpResult<GroupMembership> {
  return {
    body: { accountId: 'account-1', groupId: 'group-1', groupVersion: 2, role: 'MEMBER' },
    status: 200,
    etag: '"2"',
    location: null,
    correlationId: null,
  };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({
    code,
    status,
    title: 'Request failed',
    detail: 'Safe error message.',
    violations: [],
    correlationId: 'correlation-1',
  }, null, null);
}

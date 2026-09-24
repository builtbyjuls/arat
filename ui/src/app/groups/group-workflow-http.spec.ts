import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiHttpClient, IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { LocalActorSession } from '../identity/local-actor-session.service';
import { GroupApi } from './group-api.service';
import { GroupCreationService } from './group-creation.service';
import { GroupDetailService } from './group-detail.service';
import { GroupInvitationService } from './group-invitation.service';

describe('group creation, invitation, and private detail HTTP workflow', () => {
  let creation: GroupCreationService;
  let detail: GroupDetailService;
  let invitations: GroupInvitationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiHttpClient,
        GroupApi,
        GroupCreationService,
        GroupDetailService,
        GroupInvitationService,
        {
          provide: IDEMPOTENCY_KEY_GENERATOR,
          useValue: () => 'create-intent-key',
        },
        {
          provide: LocalActorSession,
          useValue: { actor: signal({ actorId: 'actor-1' }) },
        },
      ],
    });
    creation = TestBed.inject(GroupCreationService);
    detail = TestBed.inject(GroupDetailService);
    invitations = TestBed.inject(GroupInvitationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('retries the exact create intent and reloads the copied group URL with a fresh ETag', async () => {
    const firstAttempt = creation.create({ name: 'Weekend crew', description: 'Saturday activities' });
    const firstRequest = http.expectOne('/api/v1/groups');
    expect(firstRequest.request.headers.get('Idempotency-Key')).toBe('create-intent-key');
    expect(firstRequest.request.body).toEqual({ name: 'Weekend crew', description: 'Saturday activities' });
    firstRequest.error(new ProgressEvent('network error'));
    await firstAttempt;

    const replay = creation.retry();
    const replayRequest = http.expectOne('/api/v1/groups');
    expect(replayRequest.request.headers.get('Idempotency-Key')).toBe('create-intent-key');
    expect(replayRequest.request.body).toEqual({ name: 'Weekend crew', description: 'Saturday activities' });
    replayRequest.flush({ groupId: 'group-1', name: 'Weekend crew', version: 1 }, {
      status: 201,
      statusText: 'Created',
      headers: {
        ETag: '"1"',
        Location: '/api/v1/groups/group-1',
      },
    });
    await expect(replay).resolves.toMatchObject({
      location: '/api/v1/groups/group-1',
      etag: '"1"',
    });

    const copiedUrlRead = detail.load('group-1');
    const detailRequest = http.expectOne('/api/v1/groups/group-1');
    detailRequest.flush({
      groupId: 'group-1',
      name: 'Weekend crew',
      description: 'Saturday activities',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
      version: 2,
    }, {
      headers: { ETag: '"2"' },
    });
    await copiedUrlRead;

    expect(detail.group()?.groupId).toBe('group-1');
    expect(detail.etag()).toBe('"2"');
    expect(detail.callerRole()).toBe('ORGANIZER');
  });

  it('retries an invitation with its exact key and never includes its returned token in a request', async () => {
    const request = {
      inviteeAccountId: '10000000-0000-4000-8000-000000000002',
      expiryHours: 24,
    };
    const firstAttempt = invitations.create('group-1', request);
    const firstRequest = http.expectOne('/api/v1/groups/group-1/invites');
    expect(firstRequest.request.headers.get('Idempotency-Key')).toBe('create-intent-key');
    expect(firstRequest.request.body).toEqual(request);
    firstRequest.error(new ProgressEvent('network error'));
    await firstAttempt;

    const replay = invitations.retry();
    const replayRequest = http.expectOne('/api/v1/groups/group-1/invites');
    expect(replayRequest.request.headers.get('Idempotency-Key')).toBe('create-intent-key');
    expect(replayRequest.request.body).toEqual(request);
    replayRequest.flush({
      groupId: 'group-1',
      inviteId: 'invite-1',
      inviteeAccountId: request.inviteeAccountId,
      expiresAt: '2027-01-01T00:00:00Z',
      token: 'raw-invitation-token',
    }, { status: 201, statusText: 'Created' });
    await replay;

    expect(invitations.token()).toBe('raw-invitation-token');
  });

  it('discards stale group detail after an inaccessible invitation outcome', async () => {
    const loaded = detail.load('group-1');
    http.expectOne('/api/v1/groups/group-1').flush({
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
      version: 1,
    });
    await loaded;

    const failed = invitations.create('group-1', {
      inviteeAccountId: '10000000-0000-4000-8000-000000000002',
    });
    http.expectOne('/api/v1/groups/group-1/invites').flush({
      code: 'PRIVATE_RESOURCE_NOT_FOUND',
      status: 404,
      title: 'Private resource not found',
      detail: 'This group is unavailable.',
      violations: [],
    }, {
      status: 404,
      statusText: 'Not Found',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    await Promise.resolve();
    http.expectOne('/api/v1/groups/group-1').flush({
      code: 'PRIVATE_RESOURCE_NOT_FOUND',
      status: 404,
      title: 'Private resource not found',
      detail: 'This group is unavailable.',
      violations: [],
    }, {
      status: 404,
      statusText: 'Not Found',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    await failed;

    expect(detail.group()).toBeNull();
    expect(detail.state()).toBe('not-found');
  });

  it('keeps an unknown-account validation response actionable without a raw token', async () => {
    const failed = invitations.create('group-1', {
      inviteeAccountId: '10000000-0000-4000-8000-000000000099',
    });
    http.expectOne('/api/v1/groups/group-1/invites').flush({
      code: 'VALIDATION_FAILED',
      status: 422,
      title: 'Validation failed',
      detail: 'The request was not accepted.',
      violations: [],
    }, {
      status: 422,
      statusText: 'Unprocessable Content',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    await failed;

    expect(invitations.errorCode()).toBe('VALIDATION_FAILED');
    expect(invitations.violations()).toEqual([]);
    expect(invitations.token()).toBeNull();
  });
});

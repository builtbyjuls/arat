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

describe('group creation and private detail HTTP workflow', () => {
  let creation: GroupCreationService;
  let detail: GroupDetailService;
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
});

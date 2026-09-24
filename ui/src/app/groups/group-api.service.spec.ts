import { TestBed } from '@angular/core/testing';
import { HttpParams } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiHttpClient, IdempotentMutationIntent } from '../api/api-http-client';
import { CreateInvitationRequest, GroupApi } from './group-api.service';

describe('GroupApi', () => {
  it('reads only the current actor group index with the bounded page parameters', () => {
    const read = vi.fn((_path: string, _params: HttpParams) => of({ body: { items: [] } }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(GroupApi).list('opaque-cursor').subscribe();

    expect(read).toHaveBeenCalledWith('/groups', expect.any(HttpParams));
    const params = read.mock.calls[0][1] as HttpParams;
    expect(params.get('cursor')).toBe('opaque-cursor');
    expect(params.get('limit')).toBe('20');
  });

  it('keeps group creation on the explicit idempotent HTTP boundary', () => {
    const intent = { idempotencyKey: 'create-key' } as IdempotentMutationIntent<{ name: string }>;
    const beginIdempotentMutation = vi.fn(() => intent);
    const executeIdempotent = vi.fn(() => of({ body: { groupId: 'group-1' } }));
    TestBed.configureTestingModule({
      providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation, executeIdempotent } }],
    });
    const api = TestBed.inject(GroupApi);

    const createdIntent = api.createIntent({ name: 'Weekend crew' });
    api.create(createdIntent).subscribe();

    expect(beginIdempotentMutation).toHaveBeenCalledWith({
      method: 'POST',
      path: '/groups',
      body: { name: 'Weekend crew' },
    });
    expect(executeIdempotent).toHaveBeenCalledWith(createdIntent);
  });

  it('reads a named group through its encoded private resource path', () => {
    const read = vi.fn(() => of({ body: null }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(GroupApi).read('group/id').subscribe();

    expect(read).toHaveBeenCalledWith('/groups/group%2Fid');
  });

  it('keeps invitation creation on the explicit idempotent HTTP boundary', () => {
    const request: CreateInvitationRequest = {
      inviteeAccountId: '10000000-0000-4000-8000-000000000002',
      expiryHours: 72,
    };
    const intent = { idempotencyKey: 'invitation-key' } as IdempotentMutationIntent<CreateInvitationRequest>;
    const beginIdempotentMutation = vi.fn(() => intent);
    const executeIdempotent = vi.fn(() => of({ body: { token: 'secret-token' } }));
    TestBed.configureTestingModule({
      providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation, executeIdempotent } }],
    });
    const api = TestBed.inject(GroupApi);

    const createdIntent = api.createInvitationIntent('group/id', request);
    api.createInvitation(createdIntent).subscribe();

    expect(beginIdempotentMutation).toHaveBeenCalledWith({
      method: 'POST',
      path: '/groups/group%2Fid/invites',
      body: request,
    });
    expect(executeIdempotent).toHaveBeenCalledWith(createdIntent);
  });
});

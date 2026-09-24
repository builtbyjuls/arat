import { HttpParams } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiHttpClient, IdempotentMutationIntent } from '../api/api-http-client';
import { PlanApi } from './plan-api.service';

describe('PlanApi', () => {
  it('uses the group-scoped endpoint with bounded page parameters', () => {
    const read = vi.fn((_path: string, _params: HttpParams) => of({ body: { items: [] } }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(PlanApi).list('group id', 'opaque-cursor').subscribe();

    expect(read).toHaveBeenCalledWith('/groups/group%20id/plans', expect.any(HttpParams));
    const params = read.mock.calls[0][1] as HttpParams;
    expect(params.get('cursor')).toBe('opaque-cursor');
    expect(params.get('limit')).toBe('20');
  });

  it('reads a private plan through its canonical plan route', () => {
    const read = vi.fn(() => of({ body: null }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(PlanApi).read('plan id').subscribe();

    expect(read).toHaveBeenCalledWith('/plans/plan%20id');
  });

  it('replaces requirements with the loaded plan ETag and no command key', () => {
    const mutate = vi.fn(() => of({ body: null }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { mutate } }] });
    const request = {
      title: 'Friday badminton', category: 'COURT' as const, timeZone: 'Asia/Manila',
      candidateWindows: [{ id: 'window-1', startAt: '2027-01-09T09:00:00+08:00', endAt: '2027-01-09T11:00:00+08:00' }],
      area: { code: 'BGC', radiusKm: 5 }, headcount: { minimum: 4, maximum: 10 },
      mustHaves: [], categoryAttributes: {},
    };

    TestBed.inject(PlanApi).replaceRequirements('plan id', request, '"7"').subscribe();

    expect(mutate).toHaveBeenCalledWith({
      method: 'PUT', path: '/plans/plan%20id/requirements', body: request,
      precondition: { header: 'If-Match', value: '"7"' },
    });
  });

  it('creates a complete group-scoped idempotent intent', () => {
    const intent = { idempotencyKey: 'key-1' } as IdempotentMutationIntent<unknown>;
    const beginIdempotentMutation = vi.fn(() => intent);
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation } }] });
    const request = {
      title: 'Friday badminton', category: 'COURT' as const, timeZone: 'Asia/Manila',
      candidateWindows: [{ startAt: '2027-01-09T09:00:00+08:00', endAt: '2027-01-09T11:00:00+08:00' }],
      area: { code: 'BGC', radiusKm: 5 }, headcount: { minimum: 4, maximum: 10 },
      mustHaves: [], categoryAttributes: {},
    };

    expect(TestBed.inject(PlanApi).createIntent('group id', request)).toBe(intent);
    expect(beginIdempotentMutation).toHaveBeenCalledWith({
      method: 'POST', path: '/groups/group%20id/plans', body: request,
    });
  });

  it('pages private finalization history with an opaque cursor', () => {
    const read = vi.fn((_path: string, _params: HttpParams) => of({ body: { items: [] } }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(PlanApi).listFinalizations('plan id', 'opaque-cursor').subscribe();

    expect(read).toHaveBeenCalledWith(
      '/plans/plan%20id/requirement-finalizations',
      expect.any(HttpParams),
    );
    const params = read.mock.calls[0][1] as HttpParams;
    expect(params.get('cursor')).toBe('opaque-cursor');
    expect(params.get('limit')).toBe('20');
  });

  it('creates one versioned finalization intent and executes it', () => {
    const intent = { idempotencyKey: 'key-1' } as IdempotentMutationIntent<unknown>;
    const beginIdempotentMutation = vi.fn(() => intent);
    const executeIdempotent = vi.fn(() => of({ body: null }));
    TestBed.configureTestingModule({
      providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation, executeIdempotent } }],
    });
    const api = TestBed.inject(PlanApi);
    const request = { candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00:00+08:00' };

    const createdIntent = api.createFinalizationIntent('plan id', request, '"7"');
    api.finalizeRequirements(createdIntent).subscribe();

    expect(createdIntent).toBe(intent);
    expect(beginIdempotentMutation).toHaveBeenCalledWith({
      method: 'POST',
      path: '/plans/plan%20id/requirement-finalization',
      body: request,
      precondition: { header: 'If-Match', value: '"7"' },
    });
    expect(executeIdempotent).toHaveBeenCalledWith(intent);
  });

  it('creates one versioned publication intent and executes it', () => {
    const intent = { idempotencyKey: 'key-1' } as IdempotentMutationIntent<unknown>;
    const beginIdempotentMutation = vi.fn(() => intent);
    const executeIdempotent = vi.fn(() => of({ body: null }));
    TestBed.configureTestingModule({
      providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation, executeIdempotent } }],
    });
    const api = TestBed.inject(PlanApi);
    const request = { finalizationId: 'finalization-1' };

    const createdIntent = api.createPublicationIntent('plan id', request, '"7"');
    api.publishRequest(createdIntent).subscribe();

    expect(createdIntent).toBe(intent);
    expect(beginIdempotentMutation).toHaveBeenCalledWith({
      method: 'POST',
      path: '/plans/plan%20id/published-requests',
      body: request,
      precondition: { header: 'If-Match', value: '"7"' },
    });
    expect(executeIdempotent).toHaveBeenCalledWith(intent);
  });

  it('reads current, paged, and exact group-private request versions', () => {
    const read = vi.fn((_path: string, _params?: HttpParams) => of({ body: null }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });
    const api = TestBed.inject(PlanApi);

    api.readCurrentPublishedRequest('plan id').subscribe();
    api.listPublishedRequestHistory('plan id', 'opaque-cursor').subscribe();
    api.readPublishedRequest('plan id', 'request id').subscribe();

    expect(read).toHaveBeenNthCalledWith(1, '/plans/plan%20id/published-requests/current');
    expect(read).toHaveBeenNthCalledWith(
      2,
      '/plans/plan%20id/published-requests',
      expect.any(HttpParams),
    );
    const params = read.mock.calls[1][1] as HttpParams;
    expect(params.get('cursor')).toBe('opaque-cursor');
    expect(params.get('limit')).toBe('20');
    expect(read).toHaveBeenNthCalledWith(
      3,
      '/plans/plan%20id/published-requests/request%20id',
    );
  });
});

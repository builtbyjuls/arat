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
});

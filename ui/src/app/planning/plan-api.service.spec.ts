import { HttpParams } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiHttpClient } from '../api/api-http-client';
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
});

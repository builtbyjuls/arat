import { TestBed } from '@angular/core/testing';
import { HttpParams } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiHttpClient } from '../api/api-http-client';
import { GroupApi } from './group-api.service';

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
});

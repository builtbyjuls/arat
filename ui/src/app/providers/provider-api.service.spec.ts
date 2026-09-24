import { HttpParams } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ApiHttpClient } from '../api/api-http-client';
import { ProviderApi } from './provider-api.service';

describe('ProviderApi', () => {
  it('reads only the current actor provider index with bounded page parameters', () => {
    const read = vi.fn((_path: string, _params: HttpParams) => of({ body: { items: [] } }));
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { read } }] });

    TestBed.inject(ProviderApi).list('opaque-cursor').subscribe();

    expect(read).toHaveBeenCalledWith('/providers', expect.any(HttpParams));
    const params = read.mock.calls[0][1] as HttpParams;
    expect(params.get('cursor')).toBe('opaque-cursor');
    expect(params.get('limit')).toBe('20');
  });
});

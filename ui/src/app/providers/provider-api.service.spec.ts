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

  it('keeps provider creation idempotent and reads a staff-private route', () => {
    const beginIdempotentMutation = vi.fn(() => ({ idempotencyKey: 'provider-key' }));
    const executeIdempotent = vi.fn(() => of({ body: null }));
    const read = vi.fn(() => of({ body: null }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { beginIdempotentMutation, executeIdempotent, read } }] });
    const api = TestBed.inject(ProviderApi);

    const intent = api.createIntent({ displayName: 'BGC Courts', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'] });
    api.create(intent).subscribe();
    api.read('provider/id').subscribe();

    expect(beginIdempotentMutation).toHaveBeenCalledWith({ method: 'POST', path: '/providers', body: { displayName: 'BGC Courts', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'] } });
    expect(executeIdempotent).toHaveBeenCalledWith(intent);
    expect(read).toHaveBeenCalledWith('/providers/provider%2Fid');
  });

  it('replaces the provider profile with its exact loaded ETag', () => {
    const mutate = vi.fn(() => of({ body: null }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: ApiHttpClient, useValue: { mutate } }] });

    TestBed.inject(ProviderApi).replaceProfile('provider/id', {
      displayName: 'Updated Courts',
      supportedCategories: ['KTV'],
      serviceAreaCodes: ['BGC', 'MAKATI'],
    }, '"7"').subscribe();

    expect(mutate).toHaveBeenCalledWith({
      method: 'PUT',
      path: '/providers/provider%2Fid/profile',
      body: { displayName: 'Updated Courts', supportedCategories: ['KTV'], serviceAreaCodes: ['BGC', 'MAKATI'] },
      precondition: { header: 'If-Match', value: '"7"' },
    });
  });
});

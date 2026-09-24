import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderDetail } from './provider-api.service';
import { ProviderDetailService } from './provider-detail.service';

describe('ProviderDetailService', () => {
  it('reads the staff-private provider detail and retains the ETag', async () => {
    const service = setup({ read: vi.fn(() => of(detailResult())) });
    await service.load('provider-1');
    expect(service.provider()?.callerStaffRole).toBe('ADMIN');
    expect(service.etag()).toBe('"2"');
  });

  it('presents every private 404 as unavailable and never retains the prior provider', async () => {
    const api = { read: vi.fn().mockReturnValueOnce(of(detailResult())).mockReturnValueOnce(throwError(() => problem(404))) };
    const service = setup(api);
    await service.load('provider-1');
    await service.load('unrelated-provider');
    expect(service.state()).toBe('not-found');
    expect(service.provider()).toBeNull();
  });

  it('discards a late read after actor switch', async () => {
    const pending = new Subject<ApiHttpResult<ProviderDetail>>();
    const service = setup({ read: vi.fn(() => pending) });
    const loading = service.load('provider-1');
    TestBed.inject(ActorScopeResetService).reset();
    pending.next(detailResult());
    pending.complete();
    await loading;
    expect(service.provider()).toBeNull();
  });

  it('updates the loaded detail only from a successful profile response', async () => {
    const service = setup({ read: vi.fn(() => of(detailResult())) });
    await service.load('provider-1');

    service.applyProfile({
      providerId: 'provider-1',
      displayName: 'Updated Courts',
      supportedCategories: ['KTV'],
      serviceAreaCodes: ['MAKATI'],
      verificationStatus: 'UNVERIFIED',
      version: 3,
    }, '"3"');

    expect(service.provider()).toMatchObject({
      callerStaffRole: 'ADMIN', displayName: 'Updated Courts', supportedCategories: ['KTV'], serviceAreaCodes: ['MAKATI'], version: 3,
    });
    expect(service.etag()).toBe('"3"');
  });

  it('does not let an older pending read overwrite a successful profile replacement', async () => {
    const pending = new Subject<ApiHttpResult<ProviderDetail>>();
    const api = { read: vi.fn().mockReturnValueOnce(of(detailResult())).mockReturnValueOnce(pending) };
    const service = setup(api);
    await service.load('provider-1');
    const reload = service.load('provider-1');

    service.applyProfile({
      providerId: 'provider-1', displayName: 'Updated Courts', supportedCategories: ['KTV'], serviceAreaCodes: ['MAKATI'], verificationStatus: 'UNVERIFIED', version: 3,
    }, '"3"');
    pending.next(detailResult());
    pending.complete();
    await reload;

    expect(service.provider()).toMatchObject({ displayName: 'Updated Courts', version: 3 });
    expect(service.etag()).toBe('"3"');
  });
});

function setup(api: Pick<ProviderApi, 'read'>): ProviderDetailService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
  return TestBed.inject(ProviderDetailService);
}

function detailResult(): ApiHttpResult<ProviderDetail> {
  return { body: { providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 2 }, status: 200, etag: '"2"', location: null, correlationId: null };
}

function problem(status: number): ApiHttpError {
  return new ApiHttpError({ code: 'PRIVATE_RESOURCE_NOT_FOUND', status, title: 'Unavailable', detail: 'Unavailable.', violations: [], correlationId: 'correlation-1' }, null, null);
}

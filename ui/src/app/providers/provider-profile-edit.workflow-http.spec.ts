import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiHttpClient } from '../api/api-http-client';
import { CreateProviderRequest, ProviderApi } from './provider-api.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';

describe('provider profile edit HTTP workflow', () => {
  let edit: ProviderProfileEditService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiHttpClient,
        ProviderApi,
        ProviderProfileEditService,
      ],
    });
    edit = TestBed.inject(ProviderProfileEditService);
    edit.useProvider('provider id');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends exact replacement fields and the loaded provider ETag only', async () => {
    const body = request();
    const pending = edit.replace('provider id', body, '"7"');
    const replacement = http.expectOne('/api/v1/providers/provider%20id/profile');

    expect(replacement.request.method).toBe('PUT');
    expect(replacement.request.headers.get('If-Match')).toBe('"7"');
    expect(replacement.request.headers.has('Idempotency-Key')).toBe(false);
    expect(replacement.request.body).toEqual(body);
    replacement.flush({ providerId: 'provider id', ...body, verificationStatus: 'UNVERIFIED', version: 8 }, { headers: { ETag: '"8"' } });

    await expect(pending).resolves.toMatchObject({ etag: '"8"' });
  });

  it('does not resubmit the stale profile while refreshing the ETag', async () => {
    const first = edit.replace('provider id', request(), '"7"');
    http.expectOne('/api/v1/providers/provider%20id/profile').flush({
      code: 'PRECONDITION_FAILED', status: 412, title: 'Precondition failed', detail: 'The provider changed.', violations: [], correlationId: 'correlation-1',
    }, { status: 412, statusText: 'Precondition Failed', headers: { 'Content-Type': 'application/problem+json' } });
    await first;

    edit.markRefreshed('"8"');
    http.expectNone('/api/v1/providers/provider%20id/profile');

    const reapplied = edit.reapply('provider id', { ...request(), displayName: 'Reviewed Courts' });
    const replacement = http.expectOne('/api/v1/providers/provider%20id/profile');
    expect(replacement.request.headers.get('If-Match')).toBe('"8"');
    expect(replacement.request.body).toEqual(expect.objectContaining({ displayName: 'Reviewed Courts' }));
    replacement.flush({ providerId: 'provider id', ...request(), verificationStatus: 'UNVERIFIED', version: 9 }, { headers: { ETag: '"9"' } });
    await reapplied;
  });
});

function request(): CreateProviderRequest {
  return { displayName: 'BGC Courts', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC', 'MAKATI'] };
}

import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Observable, Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateProviderRequest, ProviderApi, ProviderDetail, ProviderPage, ProviderRepresentation } from './provider-api.service';
import { ProviderCreationService } from './provider-creation.service';
import { ProviderDetailService } from './provider-detail.service';
import { ProviderIndexService } from './provider-index.service';

describe('ProviderCreationService', () => {
  it('uses one intent for duplicate creation and exact replay after a network retry', async () => {
    const api = fakeApi([throwError(() => problem(0, 'UNKNOWN_ERROR')), of(result())]);
    const service = setup(api);

    await service.create(request());
    await service.retry();

    expect(api.createIntent).toHaveBeenCalledOnce();
    expect(api.create.mock.calls[0][0]).toBe(api.create.mock.calls[1][0]);
    expect(api.create.mock.calls[0][0].idempotencyKey).toBe('provider-key-1');
  });

  it('does not retry a reused key response', async () => {
    const service = setup(fakeApi([throwError(() => problem(409, 'IDEMPOTENCY_KEY_REUSED'))]));

    await service.create(request());

    expect(service.state()).toBe('error');
    await expect(service.retry()).resolves.toBeNull();
  });

  it('discards an in-flight provider intent on actor switch', async () => {
    const pending = new Subject<ApiHttpResult<ProviderRepresentation>>();
    const service = setup(fakeApi([pending]));
    const creating = service.create(request());

    TestBed.inject(ActorScopeResetService).reset();
    pending.next(result());
    pending.complete();

    await expect(creating).resolves.toBeNull();
    await expect(service.retry()).resolves.toBeNull();
  });

  it('rediscovers a created provider through a fresh scoped index and canonical detail read', async () => {
    const api = providerWorkflowApi();
    const creation = setup(api);

    const created = await creation.create(request());
    expect(created?.location).toBe('/api/v1/providers/provider-1');

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
    const index = TestBed.inject(ProviderIndexService);
    const detail = TestBed.inject(ProviderDetailService);
    await index.refresh();
    const providerId = index.providers()[0]?.providerId;
    expect(providerId).toBe('provider-1');
    if (providerId === undefined) throw new Error('The provider index did not return the created provider.');
    await detail.load(providerId);

    expect(index.providers().map((provider) => provider.providerId)).toEqual(['provider-1']);
    expect(detail.provider()?.displayName).toBe('BGC Courts');
    expect(api.read).toHaveBeenCalledWith('provider-1');
  });
});

function setup(api: Pick<ProviderApi, 'create' | 'createIntent'>): ProviderCreationService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
  return TestBed.inject(ProviderCreationService);
}

function fakeApi(responses: readonly Observable<ApiHttpResult<ProviderRepresentation>>[]): Pick<ProviderApi, 'create' | 'createIntent'> & { create: ReturnType<typeof vi.fn>; createIntent: ReturnType<typeof vi.fn> } {
  let index = 0;
  return {
    createIntent: vi.fn((_request: CreateProviderRequest) => ({ idempotencyKey: `provider-key-${index + 1}` })),
    create: vi.fn((_intent: IdempotentMutationIntent<CreateProviderRequest>) => responses[index++]),
  };
}

function request(): CreateProviderRequest {
  return { displayName: 'BGC Courts', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'] };
}

function result(): ApiHttpResult<ProviderRepresentation> {
  return { body: { providerId: 'provider-1', displayName: 'BGC Courts', verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 1 }, status: 201, etag: '"1"', location: '/api/v1/providers/provider-1', correlationId: null };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({ code, status, title: 'Request failed', detail: 'Request failed.', violations: [], correlationId: null }, null, null);
}

function providerWorkflowApi(): Pick<ProviderApi, 'create' | 'createIntent' | 'list' | 'read'> & { read: ReturnType<typeof vi.fn> } {
  return {
    createIntent: vi.fn(() => ({ idempotencyKey: 'provider-key-1' })),
    create: vi.fn(() => of(result())),
    list: vi.fn(() => of({ body: { items: [{ providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 1 }] }, status: 200, etag: null, location: null, correlationId: null } satisfies ApiHttpResult<ProviderPage>)),
    read: vi.fn(() => of({ body: { providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 1 }, status: 200, etag: '"1"', location: null, correlationId: null } satisfies ApiHttpResult<ProviderDetail>)),
  };
}

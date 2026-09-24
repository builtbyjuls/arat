import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Subject, of, throwError } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateProviderRequest, ProviderApi, ProviderRepresentation } from './provider-api.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';

describe('ProviderProfileEditService', () => {
  it('keeps the loaded ETag and exact profile fields for replacement', async () => {
    const replaceProfile = vi.fn(() => of(result()));
    const edit = setup({ replaceProfile });
    edit.useProvider('provider-1');

    await edit.replace('provider-1', request(), '"2"');

    expect(replaceProfile).toHaveBeenCalledWith('provider-1', request(), '"2"');
    expect(edit.state()).toBe('idle');
  });

  it('does not automatically resubmit after a stale ETag and needs deliberate reapply', async () => {
    const replaceProfile = vi.fn()
      .mockReturnValueOnce(throwError(() => problem(412, 'PRECONDITION_FAILED')))
      .mockReturnValueOnce(of(result('"4"')));
    const edit = setup({ replaceProfile });
    edit.useProvider('provider-1');

    await edit.replace('provider-1', request(), '"2"');
    expect(edit.state()).toBe('conflict');

    edit.markRefreshed('"3"');
    expect(edit.state()).toBe('reapply-ready');
    expect(replaceProfile).toHaveBeenCalledTimes(1);

    await edit.reapply('provider-1', { ...request(), displayName: 'Reviewed Courts' });
    expect(replaceProfile).toHaveBeenLastCalledWith('provider-1', { ...request(), displayName: 'Reviewed Courts' }, '"3"');
  });

  it('clears a late replacement when the local actor changes', async () => {
    const pending = new Subject<ApiHttpResult<ProviderRepresentation>>();
    const edit = setup({ replaceProfile: vi.fn(() => pending) });
    edit.useProvider('provider-1');
    const replacement = edit.replace('provider-1', request(), '"2"');

    TestBed.inject(ActorScopeResetService).reset();
    pending.next(result());
    pending.complete();

    await expect(replacement).resolves.toBeNull();
    expect(edit.state()).toBe('idle');
  });

  it('retains a server forbidden-role response for the visible administrator error', async () => {
    const edit = setup({ replaceProfile: vi.fn(() => throwError(() => problem(403, 'FORBIDDEN_ROLE'))) });
    edit.useProvider('provider-1');

    await edit.replace('provider-1', request(), '"2"');

    expect(edit.state()).toBe('error');
    expect(edit.problemCode()).toBe('FORBIDDEN_ROLE');
  });
});

function setup(api: Pick<ProviderApi, 'replaceProfile'>): ProviderProfileEditService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderApi, useValue: api }] });
  return TestBed.inject(ProviderProfileEditService);
}

function request(): CreateProviderRequest {
  return { displayName: 'BGC Courts', supportedCategories: ['COURT', 'KTV'], serviceAreaCodes: ['BGC', 'MAKATI'] };
}

function result(etag = '"3"'): ApiHttpResult<ProviderRepresentation> {
  return {
    body: { providerId: 'provider-1', displayName: 'BGC Courts', supportedCategories: ['COURT', 'KTV'], serviceAreaCodes: ['BGC', 'MAKATI'], verificationStatus: 'UNVERIFIED', version: 3 },
    status: 200,
    etag,
    location: null,
    correlationId: null,
  };
}

function problem(status: number, code: string): ApiHttpError {
  return new ApiHttpError({ code, status, title: 'Replacement failed', detail: 'Replacement failed.', violations: [], correlationId: 'correlation-1' }, null, null);
}

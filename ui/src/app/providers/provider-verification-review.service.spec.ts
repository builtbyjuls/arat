import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  PendingProviderVerification,
  PendingProviderVerificationPage,
  ProviderOperationsApi,
  ProviderVerificationDecision,
} from './provider-operations-api.service';
import { ProviderVerificationReviewService } from './provider-verification-review.service';

describe('ProviderVerificationReviewService', () => {
  it('pages stable pending submissions without duplicates', async () => {
    const api = operationsApi([
      page([submission('submission-1')], 'next-page'),
      page([submission('submission-1'), submission('submission-2')]),
    ]);
    const service = setup(api);

    await service.refresh();
    await service.loadMore();

    expect(api.listPending).toHaveBeenNthCalledWith(2, 'next-page');
    expect(service.items().map((item) => item.submissionId)).toEqual(['submission-1', 'submission-2']);
    expect(service.nextCursor()).toBeNull();
  });

  it('clears queued evidence, paging, and decision state on actor change', async () => {
    const pendingDecision = new Subject<ApiHttpResult<ProviderVerificationDecision>>();
    const api = operationsApi([page([submission('submission-1')], 'next-page')]);
    api.decide.mockReturnValue(pendingDecision);
    const service = setup(api);
    await service.refresh();
    void service.decide(submission('submission-1'), 'ACCEPT', 'Reviewed');

    TestBed.inject(ActorScopeResetService).reset();

    expect(service.items()).toEqual([]);
    expect(service.nextCursor()).toBeNull();
    expect(service.decisionState()).toBe('idle');
    expect(service.decisionResult()).toBeNull();
    pendingDecision.next(result(decision('ACCEPT')));
    pendingDecision.complete();
    await Promise.resolve();
    expect(service.items()).toEqual([]);
  });

  it('maps non-operator access to a cleared forbidden state', async () => {
    const service = setup(operationsApi([], problemError(403, 'FORBIDDEN_PLATFORM_ROLE')));

    await service.refresh();

    expect(service.queueState()).toBe('forbidden');
    expect(service.items()).toEqual([]);
    expect(service.queueCorrelationId()).toBe('correlation-1');
  });

  it('sends the exact submission and removes it only after an accepted outcome', async () => {
    const api = operationsApi([page([submission('submission-1')]), page([])]);
    api.decide.mockReturnValue(of(result(decision('ACCEPT'))));
    const service = setup(api);
    await service.refresh();

    const completed = await service.decide(submission('submission-1'), 'ACCEPT', 'Registration checked');

    expect(api.createDecisionIntent).toHaveBeenCalledWith('provider-1', {
      submissionId: 'submission-1', decision: 'ACCEPT', note: 'Registration checked',
    });
    expect(completed?.decision.verificationStatus).toBe('VERIFIED');
    expect(service.items()).toEqual([]);
    expect(api.listPending).toHaveBeenCalledTimes(2);
  });

  it('invalidates an older queue read and refreshes after a successful decision', async () => {
    const staleRefresh = new Subject<ApiHttpResult<PendingProviderVerificationPage>>();
    const api = operationsApi([]);
    let listCall = 0;
    api.listPending.mockImplementation(() => {
      listCall += 1;
      if (listCall === 1) return of(page([submission('submission-1')]));
      if (listCall === 2) return staleRefresh;
      return of(page([]));
    });
    api.decide.mockReturnValue(of(result(decision('ACCEPT'))));
    const service = setup(api);
    await service.refresh();

    const olderRefresh = service.refresh();
    await service.decide(submission('submission-1'), 'ACCEPT', undefined);
    await Promise.resolve();
    staleRefresh.next(page([submission('submission-1')]));
    staleRefresh.complete();
    await olderRefresh;

    expect(api.listPending).toHaveBeenCalledTimes(3);
    expect(service.items()).toEqual([]);
    expect(service.queueState()).toBe('empty');
  });

  it('keeps the row and suppresses a duplicate action while a decision is in flight', async () => {
    const pendingDecision = new Subject<ApiHttpResult<ProviderVerificationDecision>>();
    const api = operationsApi([page([submission('submission-1')])]);
    api.decide.mockReturnValue(pendingDecision);
    const service = setup(api);
    await service.refresh();

    const first = service.decide(submission('submission-1'), 'REJECT', undefined);
    await expect(service.decide(submission('submission-1'), 'ACCEPT', undefined)).resolves.toBeNull();

    expect(api.createDecisionIntent).toHaveBeenCalledOnce();
    expect(service.items()).toHaveLength(1);
    pendingDecision.error(problemError(409, 'INVALID_PROVIDER_STATE'));
    await first;
    expect(service.items()).toHaveLength(1);
  });

  it('keeps stale and idempotency reuse outcomes distinct and does not remove evidence', async () => {
    const api = operationsApi([page([submission('submission-1')])]);
    const service = setup(api);
    await service.refresh();

    api.decide.mockReturnValueOnce(throwError(() => problemError(409, 'INVALID_PROVIDER_STATE')));
    await service.decide(submission('submission-1'), 'REJECT', undefined);
    expect(service.decisionState()).toBe('conflict');
    expect(service.items()).toHaveLength(1);

    service.dismissDecision();
    api.decide.mockReturnValueOnce(throwError(() => problemError(409, 'IDEMPOTENCY_KEY_REUSED')));
    await service.decide(submission('submission-1'), 'ACCEPT', undefined);
    expect(service.decisionState()).toBe('error');
    expect(service.decisionProblemCode()).toBe('IDEMPOTENCY_KEY_REUSED');
    expect(service.items()).toHaveLength(1);
  });

  it.each([
    ['ACCEPT', 'VERIFIED'],
    ['REJECT', 'REJECTED'],
  ] as const)('validates the %s outcome as %s', async (command, status) => {
    const api = operationsApi([page([submission('submission-1')]), page([])]);
    api.decide.mockReturnValue(of(result(decision(command))));
    const service = setup(api);
    await service.refresh();

    const completed = await service.decide(submission('submission-1'), command, undefined);

    expect(completed?.decision.verificationStatus).toBe(status);
    expect(service.decisionState()).toBe('succeeded');
  });
});

function setup(api: ReturnType<typeof operationsApi>): ProviderVerificationReviewService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ProviderOperationsApi, useValue: api }] });
  return TestBed.inject(ProviderVerificationReviewService);
}

function operationsApi(
  pages: readonly ApiHttpResult<PendingProviderVerificationPage>[],
  listError?: ApiHttpError,
) {
  let pageIndex = 0;
  return {
    listPending: vi.fn((): Observable<ApiHttpResult<PendingProviderVerificationPage>> => listError === undefined
      ? of(pages[pageIndex++])
      : throwError(() => listError)),
    createDecisionIntent: vi.fn(() => ({ idempotencyKey: 'decision-key' })),
    decide: vi.fn((): Observable<ApiHttpResult<ProviderVerificationDecision>> => of(result(decision('ACCEPT')))),
  };
}

function submission(submissionId: string): PendingProviderVerification {
  return {
    submissionId,
    providerId: 'provider-1',
    providerVersion: 7,
    displayName: 'BGC Courts',
    verificationStatus: 'PENDING',
    supportedCategories: ['COURT'],
    serviceAreaCodes: ['BGC'],
    submittedAt: '2027-01-01T00:00:00Z',
    evidenceReferences: ['registration-123'],
  };
}

function decision(command: 'ACCEPT' | 'REJECT'): ProviderVerificationDecision {
  return {
    decisionId: 'decision-1',
    providerId: 'provider-1',
    submissionId: 'submission-1',
    decision: command,
    providerVersion: 8,
    eligibilityVersion: 2,
    verificationStatus: command === 'ACCEPT' ? 'VERIFIED' : 'REJECTED',
  };
}

function page(
  items: readonly PendingProviderVerification[],
  nextCursor: string | null = null,
): ApiHttpResult<PendingProviderVerificationPage> {
  return { body: { items, nextCursor: nextCursor ?? undefined }, status: 200, etag: null, location: null, correlationId: null };
}

function result(body: ProviderVerificationDecision): ApiHttpResult<ProviderVerificationDecision> {
  return { body, status: 200, etag: '"8"', location: null, correlationId: null };
}

function problemError(status: number, code: string): ApiHttpError {
  return new ApiHttpError({
    code, status, title: 'Request failed', detail: 'Safe detail.', violations: [], correlationId: 'correlation-1',
  }, null, null);
}

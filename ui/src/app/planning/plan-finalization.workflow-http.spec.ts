import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanFinalizationService } from './plan-finalization.service';

describe('plan finalization HTTP workflow', () => {
  let finalizations: PlanFinalizationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'finalization-key' },
      ],
    });
    finalizations = TestBed.inject(PlanFinalizationService);
    http = TestBed.inject(HttpTestingController);
    finalizations.usePlan('plan id');
  });

  afterEach(() => http.verify());

  it('retries the exact command with one key, body, and plan ETag', async () => {
    const request = { candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00:00+08:00' };
    const first = finalizations.finalize('plan id', request, '"7"');
    const firstHttp = http.expectOne('/api/v1/plans/plan%20id/requirement-finalization');
    expect(firstHttp.request.headers.get('Idempotency-Key')).toBe('finalization-key');
    expect(firstHttp.request.headers.get('If-Match')).toBe('"7"');
    expect(firstHttp.request.body).toEqual(request);
    firstHttp.error(new ProgressEvent('error'));
    await first;

    expect(finalizations.saveState()).toBe('network-error');
    const retry = finalizations.retry('plan id');
    const retryHttp = http.expectOne('/api/v1/plans/plan%20id/requirement-finalization');
    expect(retryHttp.request.headers.get('Idempotency-Key')).toBe('finalization-key');
    expect(retryHttp.request.headers.get('If-Match')).toBe('"7"');
    expect(retryHttp.request.body).toEqual(request);
    retryHttp.flush(finalization('finalization-1', true), {
      status: 201,
      statusText: 'Created',
      headers: { ETag: '"7"', Location: '/api/v1/finalizations/finalization-1' },
    });
    await retry;

    expect(finalizations.commandResult()?.finalizationId).toBe('finalization-1');
    expect(finalizations.selected()).toBeNull();
    expect(finalizations.saveState()).toBe('idle');
  });

  it('surfaces a stale plan ETag without creating a hidden retry', async () => {
    const save = finalizations.finalize('plan id', {
      candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00:00+08:00',
    }, '"6"');
    http.expectOne('/api/v1/plans/plan%20id/requirement-finalization').flush(
      problem('PRECONDITION_FAILED', 412),
      { status: 412, statusText: 'Precondition Failed', headers: { 'Content-Type': 'application/problem+json' } },
    );
    await save;

    expect(finalizations.saveState()).toBe('conflict');
    expect(finalizations.problemCode()).toBe('PRECONDITION_FAILED');
    await finalizations.retry('plan id');
    http.expectNone('/api/v1/plans/plan%20id/requirement-finalization');
  });

  it('recovers the newest current-basis candidate and pages stale history', async () => {
    const firstPage = finalizations.load('plan id');
    const first = http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations');
    expect(first.request.params.get('limit')).toBe('20');
    first.flush({ items: [finalization('current-1', true)], nextCursor: 'older-page' });
    await firstPage;

    expect(finalizations.recoverable()?.finalizationId).toBe('current-1');
    const more = finalizations.loadMore('plan id');
    const second = http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations');
    expect(second.request.params.get('cursor')).toBe('older-page');
    second.flush({ items: [finalization('stale-1', false)], nextCursor: null });
    await more;

    expect(finalizations.items().map((item) => [item.finalizationId, item.currentBasis])).toEqual([
      ['current-1', true],
      ['stale-1', false],
    ]);
    expect(finalizations.selected()?.finalizationId).toBe('current-1');
  });

  it('represents no history and clears private state on actor switch', async () => {
    const loading = finalizations.load('plan id');
    http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations')
      .flush({ items: [], nextCursor: null });
    await loading;

    expect(finalizations.historyState()).toBe('ready');
    expect(finalizations.items()).toEqual([]);
    expect(finalizations.selected()).toBeNull();

    TestBed.inject(ActorScopeResetService).reset();

    expect(finalizations.items()).toEqual([]);
    expect(finalizations.selected()).toBeNull();
    expect(finalizations.nextCursor()).toBeNull();
  });

  it('keeps an exact command result separate and ignores an older pending history response', async () => {
    const initialHistory = finalizations.load('plan id');
    const pendingHistory = http.expectOne(
      (request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations',
    );
    const save = finalizations.finalize('plan id', {
      candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00:00+08:00',
    }, '"7"');
    http.expectOne('/api/v1/plans/plan%20id/requirement-finalization')
      .flush(finalization('replayed-old', true), { status: 201, statusText: 'Created' });
    await save;

    expect(finalizations.commandResult()?.finalizationId).toBe('replayed-old');
    expect(finalizations.selected()).toBeNull();
    pendingHistory.flush({ items: [], nextCursor: null });
    await initialHistory;
    expect(finalizations.commandResult()?.finalizationId).toBe('replayed-old');
    expect(finalizations.selected()).toBeNull();

    const refresh = finalizations.refreshHistory('plan id');
    http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations')
      .flush({ items: [finalization('new-current', true)], nextCursor: null });
    await refresh;

    expect(finalizations.commandResult()?.finalizationId).toBe('replayed-old');
    expect(finalizations.selected()?.finalizationId).toBe('new-current');
  });

  it('clears finalization content when paging or a mutation proves access was revoked', async () => {
    const load = finalizations.load('plan id');
    http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations')
      .flush({ items: [finalization('current-1', true)], nextCursor: 'older-page' });
    await load;

    const more = finalizations.loadMore('plan id');
    http.expectOne((request) => request.url === '/api/v1/plans/plan%20id/requirement-finalizations')
      .flush(problem('PRIVATE_RESOURCE_NOT_FOUND', 404), {
        status: 404,
        statusText: 'Not Found',
        headers: { 'Content-Type': 'application/problem+json' },
      });
    await more;

    expect(finalizations.items()).toEqual([]);
    expect(finalizations.selected()).toBeNull();
    expect(finalizations.nextCursor()).toBeNull();

    finalizations.dismiss();
    const save = finalizations.finalize('plan id', {
      candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00:00+08:00',
    }, '"7"');
    http.expectOne('/api/v1/plans/plan%20id/requirement-finalization')
      .flush(problem('PRIVATE_RESOURCE_NOT_FOUND', 404), {
        status: 404,
        statusText: 'Not Found',
        headers: { 'Content-Type': 'application/problem+json' },
      });
    await save;

    expect(finalizations.items()).toEqual([]);
    expect(finalizations.commandResult()).toBeNull();
  });
});

function finalization(finalizationId: string, currentBasis: boolean) {
  return {
    finalizationId,
    basisPlanVersion: currentBasis ? 7 : 6,
    currentBasis,
    candidateWindowId: 'window-1',
    selectedStartAt: '2027-01-09T01:00:00Z',
    selectedEndAt: '2027-01-09T03:00:00Z',
    offerDeadline: '2027-01-08T02:00:00Z',
    category: 'COURT',
    timeZone: 'Asia/Manila',
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
    mustHaves: ['parking'],
    categoryAttributes: { hasParking: true },
    currentPreferenceCount: 2,
    stalePreferenceCount: 1,
    warnings: ['STALE_PREFERENCE_INPUT_PRESENT'],
  };
}

function problem(code: string, status: number) {
  return {
    code,
    status,
    title: 'Request failed',
    detail: 'The plan changed.',
    violations: [],
    correlationId: 'correlation-1',
  };
}

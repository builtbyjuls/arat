import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanCancellationService } from './plan-cancellation.service';

describe('plan cancellation HTTP workflow', () => {
  let cancellations: PlanCancellationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'cancellation-key' },
      ],
    });
    cancellations = TestBed.inject(PlanCancellationService);
    http = TestBed.inject(HttpTestingController);
    cancellations.usePlan('plan id');
  });

  afterEach(() => http.verify());

  it('cancels the plan with one key and the exact current plan ETag', async () => {
    const cancellation = cancellations.cancel('plan id', '"7"');
    const request = http.expectOne('/api/v1/plans/plan%20id/cancellation');

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('cancellation-key');
    expect(request.request.headers.get('If-Match')).toBe('"7"');
    expect(request.request.body).toBeNull();
    request.flush(cancelledPlan('plan id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });

    expect(await cancellation).toEqual(expect.objectContaining({
      etag: '"8"',
      exactRetry: false,
    }));
    expect(cancellations.state()).toBe('succeeded');
  });

  it('prevents duplicate clicks while one cancellation intent is in flight', async () => {
    const first = cancellations.cancel('plan id', '"7"');
    const pending = http.expectOne('/api/v1/plans/plan%20id/cancellation');

    await expect(cancellations.cancel('plan id', '"7"')).resolves.toBeNull();
    http.expectNone('/api/v1/plans/plan%20id/cancellation');

    pending.flush(cancelledPlan('plan id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });
    await first;
  });

  it('retries an ambiguous transport failure with the same key and ETag', async () => {
    const first = cancellations.cancel('plan id', '"7"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation')
      .error(new ProgressEvent('error'));
    await first;

    expect(cancellations.state()).toBe('network-error');
    const retry = cancellations.retry('plan id');
    const retryHttp = http.expectOne('/api/v1/plans/plan%20id/cancellation');
    expect(retryHttp.request.headers.get('Idempotency-Key')).toBe('cancellation-key');
    expect(retryHttp.request.headers.get('If-Match')).toBe('"7"');
    retryHttp.flush(cancelledPlan('plan id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });

    expect((await retry)?.exactRetry).toBe(true);
  });

  it('surfaces stale ETag and distinct domain failures without automatic resubmission', async () => {
    const stale = cancellations.cancel('plan id', '"6"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation').flush(
      problem('PRECONDITION_FAILED', 412),
      problemOptions(412),
    );
    await stale;

    expect(cancellations.state()).toBe('conflict');
    expect(cancellations.problemCode()).toBe('PRECONDITION_FAILED');
    http.expectNone('/api/v1/plans/plan%20id/cancellation');
  });

  it.each([
    ['INVALID_PLAN_STATE', 409],
    ['PRIVATE_RESOURCE_NOT_FOUND', 404],
    ['FORBIDDEN_ROLE', 403],
    ['IDEMPOTENCY_KEY_REUSED', 409],
  ])('retains the distinct cancellation problem %s', async (code, status) => {
    const cancellation = cancellations.cancel('plan id', '"7"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation').flush(
      problem(code, status),
      problemOptions(status),
    );
    await cancellation;

    expect(cancellations.state()).toBe('error');
    expect(cancellations.problemCode()).toBe(code);
  });

  it('rejects a success for the wrong plan, wrong state, or missing ETag', async () => {
    const wrongPlan = cancellations.cancel('plan id', '"7"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation').flush(
      cancelledPlan('other-plan'),
      { status: 200, statusText: 'OK', headers: { ETag: '"8"' } },
    );
    await wrongPlan;
    expect(cancellations.problemCode()).toBe('INVALID_CANCELLATION_RESPONSE');
    cancellations.dismiss();

    const wrongState = cancellations.cancel('plan id', '"7"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation').flush(
      { ...cancelledPlan('plan id'), state: 'COLLABORATING' },
      { status: 200, statusText: 'OK', headers: { ETag: '"8"' } },
    );
    await wrongState;
    expect(cancellations.problemCode()).toBe('INVALID_CANCELLATION_RESPONSE');
    cancellations.dismiss();

    const missingEtag = cancellations.cancel('plan id', '"7"');
    http.expectOne('/api/v1/plans/plan%20id/cancellation').flush(
      cancelledPlan('plan id'),
      { status: 200, statusText: 'OK' },
    );
    await missingEtag;
    expect(cancellations.problemCode()).toBe('INVALID_CANCELLATION_RESPONSE');
  });

  it('clears cancellation state and ignores a late response after an actor switch', async () => {
    const cancellation = cancellations.cancel('plan id', '"7"');
    const pending = http.expectOne('/api/v1/plans/plan%20id/cancellation');

    TestBed.inject(ActorScopeResetService).reset();
    pending.flush(cancelledPlan('plan id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });
    await cancellation;

    expect(cancellations.state()).toBe('idle');
    expect(cancellations.result()).toBeNull();
  });
});

function cancelledPlan(planId: string) {
  return { planId, createdByAccountId: 'actor-1', state: 'CANCELLED', version: 8 };
}

function problem(code: string, status: number) {
  return {
    code,
    status,
    title: 'Request failed',
    detail: 'Do not render this raw detail.',
    violations: [],
    correlationId: 'correlation-1',
  };
}

function problemOptions(status: number) {
  return {
    status,
    statusText: 'Request failed',
    headers: { 'Content-Type': 'application/problem+json' },
  };
}

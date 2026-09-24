import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanRequestClosureService } from './plan-request-closure.service';

describe('plan request closure HTTP workflow', () => {
  let closures: PlanRequestClosureService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'closure-key' },
      ],
    });
    closures = TestBed.inject(PlanRequestClosureService);
    http = TestBed.inject(HttpTestingController);
    closures.usePlan('plan id');
  });

  afterEach(() => http.verify());

  it('closes the named request with one key and the exact current plan ETag', async () => {
    const closure = closures.close('plan id', 'request id', '"7"');
    const request = http.expectOne('/api/v1/published-requests/request%20id/closure');

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('closure-key');
    expect(request.request.headers.get('If-Match')).toBe('"7"');
    expect(request.request.body).toBeNull();
    request.flush(closedRequest('request id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });

    expect(await closure).toEqual(expect.objectContaining({
      etag: '"8"',
      exactRetry: false,
    }));
    expect(closures.state()).toBe('succeeded');
  });

  it('prevents duplicate clicks while one close intent is in flight', async () => {
    const first = closures.close('plan id', 'request id', '"7"');
    const pending = http.expectOne('/api/v1/published-requests/request%20id/closure');

    await expect(closures.close('plan id', 'request id', '"7"')).resolves.toBeNull();
    http.expectNone('/api/v1/published-requests/request%20id/closure');

    pending.flush(closedRequest('request id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });
    await first;
  });

  it('retries an ambiguous transport failure with the same key and ETag', async () => {
    const first = closures.close('plan id', 'request id', '"7"');
    http.expectOne('/api/v1/published-requests/request%20id/closure')
      .error(new ProgressEvent('error'));
    await first;

    expect(closures.state()).toBe('network-error');
    const retry = closures.retry('plan id');
    const retryHttp = http.expectOne('/api/v1/published-requests/request%20id/closure');
    expect(retryHttp.request.headers.get('Idempotency-Key')).toBe('closure-key');
    expect(retryHttp.request.headers.get('If-Match')).toBe('"7"');
    retryHttp.flush(closedRequest('request id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });

    expect((await retry)?.exactRetry).toBe(true);
    expect(closures.state()).toBe('succeeded');
  });

  it('surfaces a stale plan ETag without automatically resubmitting', async () => {
    const closure = closures.close('plan id', 'request id', '"6"');
    http.expectOne('/api/v1/published-requests/request%20id/closure').flush(
      problem('PRECONDITION_FAILED', 412),
      problemOptions(412, 'Precondition Failed'),
    );
    await closure;

    expect(closures.state()).toBe('conflict');
    expect(closures.problemCode()).toBe('PRECONDITION_FAILED');
    http.expectNone('/api/v1/published-requests/request%20id/closure');
  });

  it.each([
    ['INVALID_REQUEST_STATE', 409],
    ['PRIVATE_RESOURCE_NOT_FOUND', 404],
    ['FORBIDDEN_ROLE', 403],
    ['IDEMPOTENCY_KEY_REUSED', 409],
  ])('retains the distinct close problem %s', async (code, status) => {
    const closure = closures.close('plan id', 'request id', '"7"');
    http.expectOne('/api/v1/published-requests/request%20id/closure').flush(
      problem(code, status),
      problemOptions(status, 'Request failed'),
    );
    await closure;

    expect(closures.state()).toBe('error');
    expect(closures.problemCode()).toBe(code);
  });

  it('rejects a success for the wrong request, wrong state, or missing plan ETag', async () => {
    const wrongRequest = closures.close('plan id', 'request id', '"7"');
    http.expectOne('/api/v1/published-requests/request%20id/closure').flush(
      closedRequest('other-request'),
      { status: 200, statusText: 'OK', headers: { ETag: '"8"' } },
    );
    await wrongRequest;

    expect(closures.state()).toBe('error');
    expect(closures.problemCode()).toBe('INVALID_CLOSURE_RESPONSE');
    closures.dismiss();

    const wrongState = closures.close('plan id', 'request id', '"7"');
    http.expectOne('/api/v1/published-requests/request%20id/closure').flush(
      { ...closedRequest('request id'), state: 'OPEN' },
      { status: 200, statusText: 'OK', headers: { ETag: '"8"' } },
    );
    await wrongState;
    expect(closures.problemCode()).toBe('INVALID_CLOSURE_RESPONSE');
    closures.dismiss();

    const missingEtag = closures.close('plan id', 'request id', '"7"');
    http.expectOne('/api/v1/published-requests/request%20id/closure').flush(
      closedRequest('request id'),
      { status: 200, statusText: 'OK' },
    );
    await missingEtag;
    expect(closures.problemCode()).toBe('INVALID_CLOSURE_RESPONSE');
  });

  it('clears close state and ignores a late response after an actor switch', async () => {
    const closure = closures.close('plan id', 'request id', '"7"');
    const pending = http.expectOne('/api/v1/published-requests/request%20id/closure');

    TestBed.inject(ActorScopeResetService).reset();
    pending.flush(closedRequest('request id'), {
      status: 200,
      statusText: 'OK',
      headers: { ETag: '"8"' },
    });
    await closure;

    expect(closures.state()).toBe('idle');
    expect(closures.result()).toBeNull();
  });
});

function closedRequest(requestId: string) {
  return {
    requestId,
    requestVersion: 1,
    publishedAt: '2027-01-08T01:00:00Z',
    state: 'CLOSED',
    actionable: false,
    category: 'COURT',
    timeZone: 'Asia/Manila',
    area: { code: 'BGC', radiusKm: 5 },
    requestedWindow: {
      startAt: '2027-01-09T01:00:00Z',
      endAt: '2027-01-09T03:00:00Z',
    },
    headcount: { minimum: 4, maximum: 10 },
    mustHaves: ['parking'],
    categoryAttributes: { hasParking: true },
    offerDeadline: '2027-01-08T02:00:00Z',
  };
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

function problemOptions(status: number, statusText: string) {
  return {
    status,
    statusText,
    headers: { 'Content-Type': 'application/problem+json' },
  };
}

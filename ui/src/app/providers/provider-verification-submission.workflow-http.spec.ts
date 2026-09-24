import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderVerificationSubmissionService } from './provider-verification-submission.service';

describe('provider verification submission HTTP workflow', () => {
  let submissions: ProviderVerificationSubmissionService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'verification-key' },
      ],
    });
    submissions = TestBed.inject(ProviderVerificationSubmissionService);
    http = TestBed.inject(HttpTestingController);
    submissions.useProvider('provider id');
  });

  afterEach(() => http.verify());

  it('submits the exact evidence references with one idempotency key and captures the provider ETag', async () => {
    const submission = submissions.submit('provider id', request());
    const httpRequest = http.expectOne('/api/v1/providers/provider%20id/verification-submissions');

    expect(httpRequest.request.method).toBe('POST');
    expect(httpRequest.request.headers.get('Idempotency-Key')).toBe('verification-key');
    expect(httpRequest.request.headers.has('If-Match')).toBe(false);
    expect(httpRequest.request.body).toEqual(request());
    httpRequest.flush(response(), { headers: { ETag: '"8"' } });

    await expect(submission).resolves.toEqual(expect.objectContaining({
      etag: '"8"', exactRetry: false, submission: expect.objectContaining({ providerVersion: 8 }),
    }));
  });

  it('retries a transport failure with the same key and unchanged evidence', async () => {
    const first = submissions.submit('provider id', request());
    const initial = http.expectOne('/api/v1/providers/provider%20id/verification-submissions');
    initial.error(new ProgressEvent('error'));
    await first;

    const retry = submissions.retry('provider id');
    const replay = http.expectOne('/api/v1/providers/provider%20id/verification-submissions');
    expect(replay.request.headers.get('Idempotency-Key')).toBe('verification-key');
    expect(replay.request.body).toEqual(request());
    replay.flush(response(), { headers: { ETag: '"8"' } });

    await expect(retry).resolves.toEqual(expect.objectContaining({ etag: '"8"', exactRetry: true }));
  });

  it('does not send a second command while the same intent is in flight', async () => {
    const first = submissions.submit('provider id', request());
    const pending = http.expectOne('/api/v1/providers/provider%20id/verification-submissions');

    await expect(submissions.submit('provider id', { evidenceReferences: ['different'] })).resolves.toBeNull();
    http.expectNone('/api/v1/providers/provider%20id/verification-submissions');
    pending.flush(response(), { headers: { ETag: '"8"' } });
    await first;
  });

  it('requires an authoritative refresh when a successful submission omits its provider ETag', async () => {
    const submission = submissions.submit('provider id', request());
    http.expectOne('/api/v1/providers/provider%20id/verification-submissions').flush(response());

    await expect(submission).resolves.toBeNull();
    expect(submissions.state()).toBe('refresh-required');
    expect(submissions.problemCode()).toBe('MISSING_PROVIDER_ETAG');
  });

  it('does not let an earlier provider refresh clear a later provider submission', async () => {
    const first = submissions.submit('provider id', request());
    http.expectOne('/api/v1/providers/provider%20id/verification-submissions').flush(response());
    await first;
    expect(submissions.state()).toBe('refresh-required');

    submissions.useProvider('provider two');
    const second = submissions.submit('provider two', { evidenceReferences: ['provider-two-reference'] });
    const pending = http.expectOne('/api/v1/providers/provider%20two/verification-submissions');
    submissions.completeRefresh('provider id');

    expect(submissions.state()).toBe('submitting');
    pending.flush({ providerId: 'provider two', submissionId: 'submission-2', providerVersion: 2, evidenceCount: 1 }, { headers: { ETag: '"2"' } });
    await expect(second).resolves.toEqual(expect.objectContaining({ etag: '"2"' }));
  });

  it('retains key reuse, forbidden role, and invalid source state as distinct server outcomes', async () => {
    for (const [status, code, state] of [
      [409, 'IDEMPOTENCY_KEY_REUSED', 'error'],
      [403, 'FORBIDDEN_ROLE', 'error'],
      [409, 'INVALID_PROVIDER_STATE', 'conflict'],
    ] as const) {
      const submission = submissions.submit('provider id', request());
      http.expectOne('/api/v1/providers/provider%20id/verification-submissions').flush(
        problem(code, status),
        problemOptions(status),
      );
      await submission;
      expect(submissions.state()).toBe(state);
      expect(submissions.problemCode()).toBe(code);
      submissions.dismiss();
    }
  });

  it('clears the volatile intent and ignores a late success when the actor changes', async () => {
    const submission = submissions.submit('provider id', request());
    const pending = http.expectOne('/api/v1/providers/provider%20id/verification-submissions');

    TestBed.inject(ActorScopeResetService).reset();
    pending.flush(response(), { headers: { ETag: '"8"' } });

    await expect(submission).resolves.toBeNull();
    expect(submissions.state()).toBe('idle');
    expect(submissions.result()).toBeNull();
  });
});

function request() {
  return { evidenceReferences: ['registration-123', 'permit-456'] };
}

function response() {
  return {
    providerId: 'provider id',
    submissionId: 'submission-1',
    providerVersion: 8,
    evidenceCount: 2,
  };
}

function problem(code: string, status: number) {
  return { code, status, title: 'Request failed', detail: 'Safe detail.', violations: [], correlationId: 'correlation-1' };
}

function problemOptions(status: number) {
  return { status, statusText: 'Request failed', headers: { 'Content-Type': 'application/problem+json' } };
}

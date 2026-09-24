import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { PendingProviderVerification } from './provider-operations-api.service';
import { ProviderVerificationReviewService } from './provider-verification-review.service';

describe('provider verification review HTTP workflow', () => {
  let service: ProviderVerificationReviewService;
  let http: HttpTestingController;
  let keyIndex: number;

  beforeEach(() => {
    keyIndex = 0;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => `decision-key-${++keyIndex}` },
      ],
    });
    service = TestBed.inject(ProviderVerificationReviewService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the opaque queue cursor and bounded page size', async () => {
    const first = service.refresh();
    const firstRequest = http.expectOne((request) => request.url === '/api/v1/operations/providers/pending-verifications');
    expect(firstRequest.request.params.get('limit')).toBe('20');
    expect(firstRequest.request.params.has('cursor')).toBe(false);
    firstRequest.flush(page('next cursor'));
    await first;

    const more = service.loadMore();
    const nextRequest = http.expectOne((request) => request.url === '/api/v1/operations/providers/pending-verifications');
    expect(nextRequest.request.params.get('cursor')).toBe('next cursor');
    nextRequest.flush({ items: [], nextCursor: null });
    await more;
  });

  it('sends the exact submission, accept intent, optional note, and one idempotency key', async () => {
    const command = service.decide(submission(), 'ACCEPT', 'Registration checked');
    const request = http.expectOne('/api/v1/operations/providers/provider%20id/verification-decisions');

    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('decision-key-1');
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.body).toEqual({
      submissionId: 'submission-1', decision: 'ACCEPT', note: 'Registration checked',
    });
    request.flush(decision('ACCEPT'));
    await Promise.resolve();
    http.expectOne('/api/v1/operations/providers/pending-verifications?limit=20')
      .flush({ items: [], nextCursor: null });

    await expect(command).resolves.toEqual(expect.objectContaining({
      decision: expect.objectContaining({ submissionId: 'submission-1', verificationStatus: 'VERIFIED' }),
    }));
  });

  it('creates a separate reject intent and omits an empty optional note', async () => {
    const command = service.decide(submission(), 'REJECT', undefined);
    const request = http.expectOne('/api/v1/operations/providers/provider%20id/verification-decisions');

    expect(request.request.headers.get('Idempotency-Key')).toBe('decision-key-1');
    expect(request.request.body).toEqual({ submissionId: 'submission-1', decision: 'REJECT' });
    request.flush(decision('REJECT'));
    await Promise.resolve();
    http.expectOne('/api/v1/operations/providers/pending-verifications?limit=20')
      .flush({ items: [], nextCursor: null });
    await command;
  });

  it('retries an ambiguous decision with the same key and unchanged command', async () => {
    const first = service.decide(submission(), 'ACCEPT', 'Checked');
    const initial = http.expectOne('/api/v1/operations/providers/provider%20id/verification-decisions');
    initial.error(new ProgressEvent('error'));
    await first;

    const retry = service.retryDecision();
    const replay = http.expectOne('/api/v1/operations/providers/provider%20id/verification-decisions');
    expect(replay.request.headers.get('Idempotency-Key')).toBe('decision-key-1');
    expect(replay.request.body).toEqual({ submissionId: 'submission-1', decision: 'ACCEPT', note: 'Checked' });
    replay.flush(decision('ACCEPT'));
    await Promise.resolve();
    http.expectOne('/api/v1/operations/providers/pending-verifications?limit=20')
      .flush({ items: [], nextCursor: null });

    await expect(retry).resolves.toEqual(expect.objectContaining({ exactRetry: true }));
  });
});

function submission(): PendingProviderVerification {
  return {
    submissionId: 'submission-1', providerId: 'provider id', providerVersion: 7,
    displayName: 'BGC Courts', verificationStatus: 'PENDING' as const,
    supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'],
    submittedAt: '2027-01-01T00:00:00Z', evidenceReferences: ['registration-123'],
  };
}

function page(nextCursor: string | null) {
  return { items: [submission()], nextCursor };
}

function decision(command: 'ACCEPT' | 'REJECT') {
  return {
    decisionId: 'decision-1', providerId: 'provider id', submissionId: 'submission-1',
    decision: command, providerVersion: 8, eligibilityVersion: 2,
    verificationStatus: command === 'ACCEPT' ? 'VERIFIED' : 'REJECTED',
  };
}

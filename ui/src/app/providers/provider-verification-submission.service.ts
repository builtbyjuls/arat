import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  ApiProblemViolation,
  IdempotentMutationIntent,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  ProviderApi,
  ProviderVerificationSubmission,
  SubmitProviderVerificationRequest,
} from './provider-api.service';

export type ProviderVerificationSubmissionState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'refresh-required'
  | 'submitting'
  | 'succeeded';

export interface ProviderVerificationSubmissionResult {
  readonly etag: string;
  readonly exactRetry: boolean;
  readonly submission: ProviderVerificationSubmission;
}

interface VerificationSubmissionAttempt {
  readonly intent: IdempotentMutationIntent<SubmitProviderVerificationRequest>;
  readonly providerId: string;
}

@Injectable({ providedIn: 'root' })
export class ProviderVerificationSubmissionService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<ProviderVerificationSubmissionState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #problemCode = signal<string | null>(null);
  readonly #result = signal<ProviderVerificationSubmissionResult | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #attempt: VerificationSubmissionAttempt | null = null;
  #attemptId = 0;
  #providerId: string | null = null;
  #wasRetried = false;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly result = this.#result.asReadonly();
  readonly violations = this.#violations.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  useProvider(providerId: string): void {
    if (this.#providerId !== providerId) {
      this.reset();
      this.#providerId = providerId;
    }
  }

  releaseProvider(providerId: string): void {
    if (this.#providerId === providerId) this.reset();
  }

  submit(
    providerId: string,
    request: SubmitProviderVerificationRequest,
  ): Promise<ProviderVerificationSubmissionResult | null> {
    if (this.#providerId !== providerId || this.#state() !== 'idle') {
      return Promise.resolve(null);
    }
    const attempt = Object.freeze({
      intent: this.#api.createVerificationSubmissionIntent(providerId, request),
      providerId,
    });
    this.#attempt = attempt;
    this.#wasRetried = false;
    return this.execute(attempt, ++this.#attemptId);
  }

  retry(providerId: string): Promise<ProviderVerificationSubmissionResult | null> {
    if (
      this.#providerId !== providerId
      || this.#state() !== 'network-error'
      || this.#attempt === null
    ) {
      return Promise.resolve(null);
    }
    this.#wasRetried = true;
    return this.execute(this.#attempt, ++this.#attemptId);
  }

  dismiss(): void { this.clearOutcome(); }

  completeRefresh(providerId: string): void {
    if (this.#providerId === providerId && this.#state() === 'refresh-required') {
      this.clearOutcome();
    }
  }

  violationFor(field: string): string | null {
    return this.#violations().find((violation) => violation.field === field)?.message ?? null;
  }

  private async execute(
    attempt: VerificationSubmissionAttempt,
    attemptId: number,
  ): Promise<ProviderVerificationSubmissionResult | null> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    try {
      const response = await firstValueFrom(this.#api.submitVerification(attempt.intent));
      if (!this.isCurrentAttempt(attempt, attemptId)) return null;
      if (response.body !== null && isVerificationResponseForProvider(response.body, attempt.providerId) && response.etag === null) {
        this.#attempt = null;
        this.#problemCode.set('MISSING_PROVIDER_ETAG');
        this.#state.set('refresh-required');
        return null;
      }
      if (!isCompleteVerificationResponse(response, attempt.providerId)) {
        this.#attempt = null;
        this.#problemCode.set('INVALID_VERIFICATION_SUBMISSION_RESPONSE');
        this.#state.set('error');
        return null;
      }
      const result = Object.freeze({
        etag: response.etag,
        exactRetry: this.#wasRetried,
        submission: response.body,
      });
      this.#attempt = null;
      this.#result.set(result);
      this.#state.set('succeeded');
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(attempt, attemptId)) return null;
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.status === 0) {
        this.#state.set('network-error');
      } else {
        this.#attempt = null;
        this.#state.set(apiError?.problem.code === 'INVALID_PROVIDER_STATE' ? 'conflict' : 'error');
      }
      return null;
    }
  }

  private clearOutcome(): void {
    this.#attemptId += 1;
    this.#attempt = null;
    this.#wasRetried = false;
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#result.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
  }

  private reset(): void {
    this.clearOutcome();
    this.#providerId = null;
  }

  private isCurrentAttempt(attempt: VerificationSubmissionAttempt, attemptId: number): boolean {
    return this.#attempt === attempt
      && this.#attemptId === attemptId
      && this.#providerId === attempt.providerId;
  }
}

function isCompleteVerificationResponse(
  response: ApiHttpResult<ProviderVerificationSubmission>,
  providerId: string,
): response is ApiHttpResult<ProviderVerificationSubmission> & {
  readonly body: ProviderVerificationSubmission;
  readonly etag: string;
} {
  return response.body !== null
    && isVerificationResponseForProvider(response.body, providerId)
    && response.etag !== null;
}

function isVerificationResponseForProvider(
  submission: ProviderVerificationSubmission,
  providerId: string,
): boolean {
  return submission.providerId === providerId
    && typeof submission.submissionId === 'string'
    && submission.submissionId.length > 0
    && typeof submission.providerVersion === 'number'
    && Number.isInteger(submission.providerVersion)
    && submission.providerVersion > 0
    && typeof submission.evidenceCount === 'number'
    && Number.isInteger(submission.evidenceCount)
    && submission.evidenceCount >= 1
    && submission.evidenceCount <= 10;
}

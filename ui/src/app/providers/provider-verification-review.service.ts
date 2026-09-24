import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiHttpResult, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  PendingProviderVerification,
  PendingProviderVerificationPage,
  ProviderOperationsApi,
  ProviderVerificationDecision,
  ProviderVerificationDecisionRequest,
} from './provider-operations-api.service';

export type VerificationQueueState =
  | 'empty'
  | 'error'
  | 'forbidden'
  | 'loading'
  | 'loading-more'
  | 'ready'
  | 'refreshing';

export type VerificationDecisionState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'submitting'
  | 'succeeded';

export interface VerificationDecisionResult {
  readonly decision: ProviderVerificationDecision;
  readonly exactRetry: boolean;
}

interface DecisionAttempt {
  readonly decision: 'ACCEPT' | 'REJECT';
  readonly intent: IdempotentMutationIntent<ProviderVerificationDecisionRequest>;
  readonly providerId: string;
  readonly submissionId: string;
}

@Injectable({ providedIn: 'root' })
export class ProviderVerificationReviewService {
  readonly #api = inject(ProviderOperationsApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #items = signal<readonly PendingProviderVerification[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #queueState = signal<VerificationQueueState>('loading');
  readonly #queueCorrelationId = signal<string | null>(null);
  readonly #decisionState = signal<VerificationDecisionState>('idle');
  readonly #decisionProblemCode = signal<string | null>(null);
  readonly #decisionCorrelationId = signal<string | null>(null);
  readonly #decisionResult = signal<VerificationDecisionResult | null>(null);
  #loadedPageCount = 0;
  #queuePending = false;
  #queueRequestId = 0;
  #decisionAttempt: DecisionAttempt | null = null;
  #decisionAttemptId = 0;
  #wasRetried = false;

  readonly items = this.#items.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly queueState = this.#queueState.asReadonly();
  readonly queueCorrelationId = this.#queueCorrelationId.asReadonly();
  readonly decisionState = this.#decisionState.asReadonly();
  readonly decisionProblemCode = this.#decisionProblemCode.asReadonly();
  readonly decisionCorrelationId = this.#decisionCorrelationId.asReadonly();
  readonly decisionResult = this.#decisionResult.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  async refresh(): Promise<void> {
    if (this.#queuePending) {
      return;
    }

    this.#queueCorrelationId.set(null);
    this.#queueState.set(this.#items().length === 0 ? 'loading' : 'refreshing');
    await this.loadInitialPages(Math.max(this.#loadedPageCount, 1));
  }

  async loadMore(): Promise<void> {
    const cursor = this.#nextCursor();
    if (cursor === null || this.#queuePending) {
      return;
    }

    this.#queueState.set('loading-more');
    const requestId = this.beginQueueRequest();
    try {
      const result = await firstValueFrom(this.#api.listPending(cursor));
      if (!this.isCurrentQueueRequest(requestId)) {
        return;
      }
      this.#items.set(appendDistinct(this.#items(), result.body?.items ?? []));
      this.#nextCursor.set(result.body?.nextCursor ?? null);
      this.#loadedPageCount += 1;
      this.#queueState.set('ready');
    } catch (error: unknown) {
      this.handleQueueError(error, requestId);
    } finally {
      this.finishQueueRequest(requestId);
    }
  }

  decide(
    item: PendingProviderVerification,
    decision: 'ACCEPT' | 'REJECT',
    note: string | undefined,
  ): Promise<VerificationDecisionResult | null> {
    if (this.#decisionState() !== 'idle') {
      return Promise.resolve(null);
    }

    const request: ProviderVerificationDecisionRequest = {
      submissionId: item.submissionId,
      decision,
      ...(note === undefined ? {} : { note }),
    };
    const attempt = Object.freeze({
      decision,
      intent: this.#api.createDecisionIntent(item.providerId, request),
      providerId: item.providerId,
      submissionId: item.submissionId,
    });
    this.#decisionAttempt = attempt;
    this.#wasRetried = false;
    return this.executeDecision(attempt, ++this.#decisionAttemptId);
  }

  retryDecision(): Promise<VerificationDecisionResult | null> {
    if (this.#decisionState() !== 'network-error' || this.#decisionAttempt === null) {
      return Promise.resolve(null);
    }
    this.#wasRetried = true;
    return this.executeDecision(this.#decisionAttempt, ++this.#decisionAttemptId);
  }

  dismissDecision(): void {
    this.clearDecision();
  }

  private async executeDecision(
    attempt: DecisionAttempt,
    attemptId: number,
  ): Promise<VerificationDecisionResult | null> {
    this.#decisionState.set('submitting');
    this.#decisionProblemCode.set(null);
    this.#decisionCorrelationId.set(null);
    try {
      const response = await firstValueFrom(this.#api.decide(attempt.intent));
      if (!this.isCurrentDecision(attempt, attemptId)) {
        return null;
      }
      if (!isCompleteDecisionResponse(response, attempt)) {
        this.#decisionAttempt = null;
        this.#decisionProblemCode.set('INVALID_VERIFICATION_DECISION_RESPONSE');
        this.#decisionState.set('error');
        return null;
      }
      const result = Object.freeze({
        decision: response.body,
        exactRetry: this.#wasRetried,
      });
      this.#decisionAttempt = null;
      this.#decisionResult.set(result);
      this.#decisionState.set('succeeded');
      this.#items.update((items) => items.filter((item) => item.submissionId !== attempt.submissionId));
      void this.refreshAfterDecision();
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentDecision(attempt, attemptId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#decisionProblemCode.set(apiError?.problem.code ?? null);
      this.#decisionCorrelationId.set(apiError?.correlationId ?? null);
      if (apiError?.status === 0) {
        this.#decisionState.set('network-error');
      } else {
        this.#decisionAttempt = null;
        this.#decisionState.set(apiError?.problem.code === 'INVALID_PROVIDER_STATE' ? 'conflict' : 'error');
      }
      return null;
    }
  }

  private async loadInitialPages(pageCount: number): Promise<void> {
    const requestId = this.beginQueueRequest();
    try {
      let items: readonly PendingProviderVerification[] = [];
      let cursor: string | null = null;
      let fetchedPageCount = 0;
      for (let page = 0; page < pageCount; page += 1) {
        const result: ApiHttpResult<PendingProviderVerificationPage> = await firstValueFrom(
          this.#api.listPending(cursor),
        );
        if (!this.isCurrentQueueRequest(requestId)) {
          return;
        }
        fetchedPageCount += 1;
        items = appendDistinct(items, result.body?.items ?? []);
        cursor = result.body?.nextCursor ?? null;
        if (cursor === null) {
          break;
        }
      }
      if (!this.isCurrentQueueRequest(requestId)) {
        return;
      }
      this.#items.set(items);
      this.#nextCursor.set(cursor);
      this.#loadedPageCount = fetchedPageCount;
      this.#queueState.set(items.length === 0 ? 'empty' : 'ready');
    } catch (error: unknown) {
      this.handleQueueError(error, requestId);
    } finally {
      this.finishQueueRequest(requestId);
    }
  }

  private async refreshAfterDecision(): Promise<void> {
    this.#queueRequestId += 1;
    this.#queuePending = false;
    this.#queueCorrelationId.set(null);
    this.#queueState.set(this.#items().length === 0 ? 'loading' : 'refreshing');
    await this.loadInitialPages(Math.max(this.#loadedPageCount, 1));
  }

  private handleQueueError(error: unknown, requestId: number): void {
    if (!this.isCurrentQueueRequest(requestId)) {
      return;
    }
    const apiError = error instanceof ApiHttpError ? error : null;
    this.#queueCorrelationId.set(apiError?.correlationId ?? null);
    this.#queueState.set(
      apiError?.problem.code === 'FORBIDDEN_PLATFORM_ROLE' ? 'forbidden' : 'error',
    );
    if (apiError?.problem.code === 'FORBIDDEN_PLATFORM_ROLE') {
      this.#items.set([]);
      this.#nextCursor.set(null);
      this.#loadedPageCount = 0;
    }
  }

  private clearDecision(): void {
    this.#decisionAttemptId += 1;
    this.#decisionAttempt = null;
    this.#wasRetried = false;
    this.#decisionProblemCode.set(null);
    this.#decisionCorrelationId.set(null);
    this.#decisionResult.set(null);
    this.#decisionState.set('idle');
  }

  private reset(): void {
    this.#queueRequestId += 1;
    this.#queuePending = false;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#queueCorrelationId.set(null);
    this.#loadedPageCount = 0;
    this.#queueState.set('loading');
    this.clearDecision();
  }

  private beginQueueRequest(): number {
    this.#queuePending = true;
    this.#queueRequestId += 1;
    return this.#queueRequestId;
  }

  private finishQueueRequest(requestId: number): void {
    if (this.isCurrentQueueRequest(requestId)) {
      this.#queuePending = false;
    }
  }

  private isCurrentQueueRequest(requestId: number): boolean {
    return requestId === this.#queueRequestId;
  }

  private isCurrentDecision(attempt: DecisionAttempt, attemptId: number): boolean {
    return this.#decisionAttempt === attempt && this.#decisionAttemptId === attemptId;
  }
}

function appendDistinct(
  current: readonly PendingProviderVerification[],
  next: readonly PendingProviderVerification[],
): readonly PendingProviderVerification[] {
  const knownIds = new Set(current.map((item) => item.submissionId));
  return [...current, ...next.filter((item) => {
    if (knownIds.has(item.submissionId)) {
      return false;
    }
    knownIds.add(item.submissionId);
    return true;
  })];
}

function isCompleteDecisionResponse(
  response: ApiHttpResult<ProviderVerificationDecision>,
  attempt: DecisionAttempt,
): response is ApiHttpResult<ProviderVerificationDecision> & {
  readonly body: ProviderVerificationDecision;
} {
  return response.body !== null
    && response.body.providerId === attempt.providerId
    && response.body.submissionId === attempt.submissionId
    && response.body.decision === attempt.decision
    && response.body.verificationStatus === (attempt.decision === 'ACCEPT' ? 'VERIFIED' : 'REJECTED')
    && typeof response.body.decisionId === 'string'
    && response.body.decisionId.length > 0
    && typeof response.body.providerVersion === 'number'
    && Number.isInteger(response.body.providerVersion)
    && typeof response.body.eligibilityVersion === 'number'
    && Number.isInteger(response.body.eligibilityVersion);
}

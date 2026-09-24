import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  IdempotentMutationIntent,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanApi, PublishedRequest } from './plan-api.service';

export type RequestClosureState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'submitting'
  | 'succeeded';

export interface RequestClosureResult {
  readonly etag: string;
  readonly exactRetry: boolean;
  readonly request: PublishedRequest;
}

interface RequestClosureAttempt {
  readonly intent: IdempotentMutationIntent<undefined>;
  readonly planId: string;
  readonly requestId: string;
}

@Injectable({ providedIn: 'root' })
export class PlanRequestClosureService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<RequestClosureState>('idle');
  readonly #problemCode = signal<string | null>(null);
  readonly #correlationId = signal<string | null>(null);
  readonly #result = signal<RequestClosureResult | null>(null);
  #attempt: RequestClosureAttempt | null = null;
  #attemptId = 0;
  #planId: string | null = null;
  #wasRetried = false;

  readonly state = this.#state.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly result = this.#result.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  usePlan(planId: string): void {
    if (this.#planId !== planId) {
      this.reset();
      this.#planId = planId;
    }
  }

  releasePlan(planId: string): void {
    if (this.#planId === planId) {
      this.reset();
    }
  }

  close(
    planId: string,
    requestId: string,
    etag: string,
  ): Promise<RequestClosureResult | null> {
    if (this.#planId !== planId || this.#state() !== 'idle') {
      return Promise.resolve(null);
    }
    const attempt = Object.freeze({
      intent: this.#api.createRequestClosureIntent(requestId, etag),
      planId,
      requestId,
    });
    this.#attempt = attempt;
    this.#wasRetried = false;
    return this.execute(attempt, ++this.#attemptId);
  }

  retry(planId: string): Promise<RequestClosureResult | null> {
    if (
      this.#planId !== planId
      || this.#state() !== 'network-error'
      || this.#attempt === null
    ) {
      return Promise.resolve(null);
    }
    this.#wasRetried = true;
    return this.execute(this.#attempt, ++this.#attemptId);
  }

  dismiss(): void {
    this.clearOutcome();
  }

  private async execute(
    attempt: RequestClosureAttempt,
    attemptId: number,
  ): Promise<RequestClosureResult | null> {
    this.#state.set('submitting');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    try {
      const response = await firstValueFrom(this.#api.closeRequest(attempt.intent));
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      if (!isCompleteClosureResponse(response, attempt.requestId)) {
        this.#attempt = null;
        this.#problemCode.set('INVALID_CLOSURE_RESPONSE');
        this.#state.set('error');
        return null;
      }
      const result = Object.freeze({
        etag: response.etag,
        exactRetry: this.#wasRetried,
        request: response.body,
      });
      this.#attempt = null;
      this.#result.set(result);
      this.#state.set('succeeded');
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#correlationId.set(apiError?.correlationId ?? null);
      if (apiError?.status === 0) {
        this.#state.set('network-error');
      } else {
        this.#attempt = null;
        this.#state.set(apiError?.status === 412 ? 'conflict' : 'error');
      }
      return null;
    }
  }

  private clearOutcome(): void {
    this.#attemptId += 1;
    this.#attempt = null;
    this.#wasRetried = false;
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    this.#result.set(null);
    this.#state.set('idle');
  }

  private reset(): void {
    this.clearOutcome();
    this.#planId = null;
  }

  private isCurrentAttempt(attempt: RequestClosureAttempt, attemptId: number): boolean {
    return this.#attempt === attempt
      && this.#attemptId === attemptId
      && this.#planId === attempt.planId;
  }
}

function isCompleteClosureResponse(
  response: ApiHttpResult<PublishedRequest>,
  requestId: string,
): response is ApiHttpResult<PublishedRequest> & {
  readonly body: PublishedRequest;
  readonly etag: string;
} {
  return response.body !== null
    && response.body.requestId === requestId
    && response.body.state === 'CLOSED'
    && response.etag !== null;
}

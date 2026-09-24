import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  IdempotentMutationIntent,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  PlanApi,
  PublishRequest,
  PublishedRequest,
} from './plan-api.service';

export type PublicationOutcome = 'initial' | 'replacement' | 'versioned';
export type PublicationState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'submitting'
  | 'succeeded';

export interface PublicationResult {
  readonly etag: string;
  readonly exactRetry: boolean;
  readonly location: string;
  readonly outcome: PublicationOutcome;
  readonly request: PublishedRequest;
}

interface PublicationAttempt {
  readonly intent: IdempotentMutationIntent<PublishRequest>;
  readonly planId: string;
  readonly replacesOpenRequest: boolean;
}

@Injectable({ providedIn: 'root' })
export class PlanPublicationService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<PublicationState>('idle');
  readonly #problemCode = signal<string | null>(null);
  readonly #correlationId = signal<string | null>(null);
  readonly #result = signal<PublicationResult | null>(null);
  #attempt: PublicationAttempt | null = null;
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

  publish(
    planId: string,
    finalizationId: string,
    etag: string,
    planState: string | undefined,
  ): Promise<PublicationResult | null> {
    if (this.#planId !== planId || this.#state() !== 'idle') {
      return Promise.resolve(null);
    }
    const intent = this.#api.createPublicationIntent(planId, { finalizationId }, etag);
    const attempt = Object.freeze({
      intent,
      planId,
      replacesOpenRequest: planState === 'OPEN_FOR_OFFERS',
    });
    this.#attempt = attempt;
    this.#wasRetried = false;
    return this.execute(attempt, ++this.#attemptId);
  }

  retry(planId: string): Promise<PublicationResult | null> {
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
    attempt: PublicationAttempt,
    attemptId: number,
  ): Promise<PublicationResult | null> {
    this.#state.set('submitting');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    try {
      const response = await firstValueFrom(this.#api.publishRequest(attempt.intent));
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      if (!isCompletePublicationResponse(response)) {
        this.#attempt = null;
        this.#problemCode.set('INVALID_PUBLICATION_RESPONSE');
        this.#state.set('error');
        return null;
      }
      const result = Object.freeze({
        etag: response.etag,
        exactRetry: this.#wasRetried,
        location: response.location,
        outcome: publicationOutcome(response.body, attempt.replacesOpenRequest),
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
        this.#state.set(
          apiError?.status === 412 || apiError?.problem.code === 'FINALIZATION_VERSION_CHANGED'
            ? 'conflict'
            : 'error',
        );
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

  private isCurrentAttempt(attempt: PublicationAttempt, attemptId: number): boolean {
    return this.#attempt === attempt
      && this.#attemptId === attemptId
      && this.#planId === attempt.planId;
  }
}

function isCompletePublicationResponse(
  response: ApiHttpResult<PublishedRequest>,
): response is ApiHttpResult<PublishedRequest> & {
  readonly body: PublishedRequest;
  readonly etag: string;
  readonly location: string;
} {
  return response.body !== null
    && typeof response.body.requestId === 'string'
    && response.body.requestId.length > 0
    && typeof response.body.requestVersion === 'number'
    && Number.isInteger(response.body.requestVersion)
    && response.body.requestVersion > 0
    && typeof response.body.state === 'string'
    && response.body.state.length > 0
    && typeof response.body.offerDeadline === 'string'
    && typeof response.body.publishedAt === 'string'
    && response.etag !== null
    && response.location !== null;
}

function publicationOutcome(
  request: PublishedRequest,
  replacesOpenRequest: boolean,
): PublicationOutcome {
  if (replacesOpenRequest) {
    return 'replacement';
  }
  return request.requestVersion === 1 ? 'initial' : 'versioned';
}

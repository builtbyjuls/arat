import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  ApiProblemViolation,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  PlanApi,
  PlanRequirements,
  RequirementReplacementRequest,
} from './plan-api.service';

export type RequirementEditState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'reapply-ready'
  | 'submitting';

interface ReplacementAttempt {
  readonly planId: string;
  readonly request: RequirementReplacementRequest;
  readonly etag: string;
}

@Injectable({ providedIn: 'root' })
export class PlanRequirementEditService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<RequirementEditState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #problemCode = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #attempt: ReplacementAttempt | null = null;
  #attemptId = 0;
  #planId: string | null = null;
  #reapplyEtag: string | null = null;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly violations = this.#violations.asReadonly();

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

  replace(
    planId: string,
    request: RequirementReplacementRequest,
    etag: string,
  ): Promise<ApiHttpResult<PlanRequirements> | null> {
    if (this.#state() !== 'idle') {
      return Promise.resolve(null);
    }
    this.usePlan(planId);
    return this.beginAttempt(planId, request, etag);
  }

  retry(): Promise<ApiHttpResult<PlanRequirements> | null> {
    if (this.#state() !== 'network-error' || this.#attempt === null) {
      return Promise.resolve(null);
    }
    return this.execute(this.#attempt, ++this.#attemptId);
  }

  markRefreshed(etag: string): void {
    if (this.#state() !== 'conflict') {
      return;
    }
    this.#reapplyEtag = etag;
    this.#state.set('reapply-ready');
  }

  reapply(
    planId: string,
    request: RequirementReplacementRequest,
  ): Promise<ApiHttpResult<PlanRequirements> | null> {
    if (
      this.#state() !== 'reapply-ready'
      || this.#reapplyEtag === null
      || this.#planId !== planId
    ) {
      return Promise.resolve(null);
    }
    return this.beginAttempt(planId, request, this.#reapplyEtag);
  }

  dismiss(): void {
    this.clearOutcome();
  }

  private beginAttempt(
    planId: string,
    request: RequirementReplacementRequest,
    etag: string,
  ): Promise<ApiHttpResult<PlanRequirements> | null> {
    const attempt = Object.freeze({
      planId,
      request: structuredClone(request),
      etag,
    });
    this.#attempt = attempt;
    this.#reapplyEtag = null;
    return this.execute(attempt, ++this.#attemptId);
  }

  private async execute(
    attempt: ReplacementAttempt,
    attemptId: number,
  ): Promise<ApiHttpResult<PlanRequirements> | null> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.replaceRequirements(
        attempt.planId,
        attempt.request,
        attempt.etag,
      ));
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      if (result.body === null || result.etag === null) {
        this.#attempt = null;
        this.#problemCode.set('INVALID_REPLACEMENT_RESPONSE');
        this.#state.set('error');
        return null;
      }
      this.clearOutcome();
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.status === 0) {
        this.#state.set('network-error');
      } else if (apiError?.status === 412) {
        this.#attempt = null;
        this.#state.set('conflict');
      } else {
        this.#attempt = null;
        this.#state.set('error');
      }
      return null;
    }
  }

  private clearOutcome(): void {
    this.#attemptId += 1;
    this.#attempt = null;
    this.#reapplyEtag = null;
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
  }

  private reset(): void {
    this.clearOutcome();
    this.#planId = null;
  }

  private isCurrentAttempt(attempt: ReplacementAttempt, attemptId: number): boolean {
    return this.#attempt === attempt
      && this.#attemptId === attemptId
      && this.#planId === attempt.planId;
  }
}

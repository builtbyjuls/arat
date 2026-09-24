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
  CreatePlanRequest,
  PlanApi,
  PlanRepresentation,
} from './plan-api.service';

export type PlanCreationState = 'error' | 'idle' | 'network-error' | 'submitting';

@Injectable({ providedIn: 'root' })
export class PlanCreationService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<PlanCreationState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #problemCode = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #intent: IdempotentMutationIntent<CreatePlanRequest> | null = null;
  #attemptId = 0;
  #groupId: string | null = null;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly violations = this.#violations.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  useGroup(groupId: string): void {
    if (this.#groupId !== groupId) {
      this.reset();
      this.#groupId = groupId;
    }
  }

  releaseGroup(groupId: string): void {
    if (this.#groupId === groupId) {
      this.reset();
    }
  }

  async create(
    groupId: string,
    request: CreatePlanRequest,
  ): Promise<ApiHttpResult<PlanRepresentation> | null> {
    if (this.#state() !== 'idle') {
      return null;
    }
    this.useGroup(groupId);
    const intent = this.#api.createIntent(groupId, request);
    this.#intent = intent;
    return this.execute(intent, ++this.#attemptId);
  }

  async retry(): Promise<ApiHttpResult<PlanRepresentation> | null> {
    if (this.#state() !== 'network-error' || this.#intent === null) {
      return null;
    }
    return this.execute(this.#intent, ++this.#attemptId);
  }

  dismissError(): void {
    this.reset();
  }

  reportInvalidResponse(): void {
    this.#intent = null;
    this.#correlationId.set(null);
    this.#problemCode.set('INVALID_CREATE_RESPONSE');
    this.#violations.set([]);
    this.#state.set('error');
  }

  private async execute(
    intent: IdempotentMutationIntent<CreatePlanRequest>,
    attemptId: number,
  ): Promise<ApiHttpResult<PlanRepresentation> | null> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.create(intent));
      if (!this.isCurrentAttempt(intent, attemptId)) {
        return null;
      }
      this.#intent = null;
      this.#state.set('idle');
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(intent, attemptId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.status === 0) {
        this.#state.set('network-error');
      } else {
        this.#intent = null;
        this.#state.set('error');
      }
      return null;
    }
  }

  private reset(): void {
    this.#attemptId += 1;
    this.#intent = null;
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
    this.#groupId = null;
  }

  private isCurrentAttempt(
    intent: IdempotentMutationIntent<CreatePlanRequest>,
    attemptId: number,
  ): boolean {
    return this.#intent === intent && this.#attemptId === attemptId;
  }
}

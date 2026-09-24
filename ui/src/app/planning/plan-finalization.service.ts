import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiProblemViolation,
  IdempotentMutationIntent,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  FinalizeRequirementsRequest,
  PlanApi,
  RequirementFinalization,
} from './plan-api.service';

export type FinalizationHistoryState = 'error' | 'loading' | 'ready';
export type FinalizationSaveState = 'conflict' | 'error' | 'idle' | 'network-error' | 'submitting';

@Injectable({ providedIn: 'root' })
export class PlanFinalizationService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #items = signal<readonly RequirementFinalization[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #historyState = signal<FinalizationHistoryState>('loading');
  readonly #historyProblemCode = signal<string | null>(null);
  readonly #saveState = signal<FinalizationSaveState>('idle');
  readonly #problemCode = signal<string | null>(null);
  readonly #correlationId = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  readonly #selected = signal<RequirementFinalization | null>(null);
  readonly #commandResult = signal<RequirementFinalization | null>(null);
  #intent: IdempotentMutationIntent<FinalizeRequirementsRequest> | null = null;
  #planId: string | null = null;
  #readRequestId = 0;
  #writeRequestId = 0;

  readonly items = this.#items.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly historyState = this.#historyState.asReadonly();
  readonly historyProblemCode = this.#historyProblemCode.asReadonly();
  readonly saveState = this.#saveState.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly violations = this.#violations.asReadonly();
  readonly selected = this.#selected.asReadonly();
  readonly commandResult = this.#commandResult.asReadonly();
  readonly recoverable = computed(() => this.#items().find((item) => item.currentBasis === true) ?? null);

  constructor() { this.#scopeReset.register(() => this.reset()); }

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

  async load(planId: string): Promise<void> {
    this.usePlan(planId);
    this.#commandResult.set(null);
    await this.refreshHistory(planId);
  }

  async refreshHistory(planId: string): Promise<void> {
    this.usePlan(planId);
    const requestId = ++this.#readRequestId;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#selected.set(null);
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    await this.loadPage(planId, null, requestId, false);
  }

  async loadMore(planId: string): Promise<void> {
    const cursor = this.#nextCursor();
    if (this.#planId !== planId || cursor === null || this.#historyState() === 'loading') {
      return;
    }
    const requestId = ++this.#readRequestId;
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    await this.loadPage(planId, cursor, requestId, true);
  }

  async finalize(
    planId: string,
    request: FinalizeRequirementsRequest,
    etag: string,
  ): Promise<RequirementFinalization | null> {
    if (this.#planId !== planId || this.#saveState() !== 'idle') {
      return null;
    }
    this.#commandResult.set(null);
    this.#intent = this.#api.createFinalizationIntent(planId, request, etag);
    return this.execute(planId, ++this.#writeRequestId);
  }

  retry(planId: string): Promise<RequirementFinalization | null> {
    if (
      this.#planId !== planId
      || this.#saveState() !== 'network-error'
      || this.#intent === null
    ) {
      return Promise.resolve(null);
    }
    return this.execute(planId, ++this.#writeRequestId);
  }

  dismiss(): void {
    this.#writeRequestId += 1;
    this.#intent = null;
    this.#saveState.set('idle');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    this.#violations.set([]);
  }

  private async execute(
    planId: string,
    requestId: number,
  ): Promise<RequirementFinalization | null> {
    const intent = this.#intent;
    if (intent === null) {
      return null;
    }
    this.#saveState.set('submitting');
    this.#problemCode.set(null);
    this.#correlationId.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.finalizeRequirements(intent));
      if (!this.isCurrentWrite(requestId, planId, intent)) {
        return null;
      }
      if (result.body === null) {
        this.#intent = null;
        this.#problemCode.set('INVALID_FINALIZATION_RESPONSE');
        this.#saveState.set('error');
        return null;
      }
      this.#readRequestId += 1;
      this.#commandResult.set(result.body);
      this.#intent = null;
      this.#saveState.set('idle');
      return result.body;
    } catch (error: unknown) {
      if (!this.isCurrentWrite(requestId, planId, intent)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND') {
        this.clearPrivateContent();
      }
      if (apiError?.status === 0) {
        this.#saveState.set('network-error');
      } else {
        this.#intent = null;
        this.#saveState.set(apiError?.status === 412 ? 'conflict' : 'error');
      }
      return null;
    }
  }

  private async loadPage(
    planId: string,
    cursor: string | null,
    requestId: number,
    append: boolean,
  ): Promise<void> {
    try {
      const result = await firstValueFrom(this.#api.listFinalizations(planId, cursor));
      if (!this.isCurrentRead(requestId, planId)) {
        return;
      }
      if (result.body === null || !Array.isArray(result.body.items)) {
        this.#historyState.set('error');
        return;
      }
      const items = append ? [...this.#items(), ...result.body.items] : result.body.items;
      this.#items.set(items);
      this.#nextCursor.set(result.body.nextCursor ?? null);
      this.#selected.set(items.find((item) => item.currentBasis === true) ?? null);
      this.#historyState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentRead(requestId, planId)) {
        return;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#historyProblemCode.set(apiError?.problem.code ?? null);
      if (apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND') {
        this.clearPrivateContent();
      }
      this.#historyState.set('error');
    }
  }

  private reset(): void {
    this.#readRequestId += 1;
    this.#writeRequestId += 1;
    this.#planId = null;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    this.#selected.set(null);
    this.#commandResult.set(null);
    this.dismiss();
  }

  private clearPrivateContent(): void {
    this.#readRequestId += 1;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#selected.set(null);
    this.#commandResult.set(null);
  }

  private isCurrentRead(requestId: number, planId: string): boolean {
    return requestId === this.#readRequestId && planId === this.#planId;
  }

  private isCurrentWrite(
    requestId: number,
    planId: string,
    intent: IdempotentMutationIntent<FinalizeRequirementsRequest>,
  ): boolean {
    return requestId === this.#writeRequestId
      && planId === this.#planId
      && intent === this.#intent;
  }
}

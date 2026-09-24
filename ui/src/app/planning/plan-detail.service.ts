import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanApi, PlanDetail } from './plan-api.service';

export type PlanDetailState = 'error' | 'loading' | 'not-found' | 'ready';

@Injectable({ providedIn: 'root' })
export class PlanDetailService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #plan = signal<PlanDetail | null>(null);
  readonly #etag = signal<string | null>(null);
  readonly #state = signal<PlanDetailState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #resourceId: string | null = null;
  #requestId = 0;

  readonly plan = this.#plan.asReadonly();
  readonly etag = this.#etag.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  async load(planId: string): Promise<void> {
    if (this.#resourceId !== planId) {
      this.clearResource(planId);
    }
    const requestId = ++this.#requestId;
    this.#state.set('loading');
    this.#correlationId.set(null);
    try {
      const result = await firstValueFrom(this.#api.read(planId));
      if (!this.isCurrent(requestId, planId)) { return; }
      if (result.body === null) {
        this.#plan.set(null);
        this.#etag.set(null);
        this.#state.set('error');
        return;
      }
      this.#plan.set(result.body);
      this.#etag.set(result.etag);
      this.#state.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrent(requestId, planId)) { return; }
      this.#plan.set(null);
      this.#etag.set(null);
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#state.set(apiError?.status === 404 ? 'not-found' : 'error');
    }
  }

  private reset(): void { this.#requestId += 1; this.clearResource(null); }
  private clearResource(planId: string | null): void {
    this.#resourceId = planId;
    this.#plan.set(null);
    this.#etag.set(null);
    this.#correlationId.set(null);
    this.#state.set('loading');
  }
  private isCurrent(requestId: number, planId: string): boolean {
    return requestId === this.#requestId && planId === this.#resourceId;
  }
}

import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupPublishedRequest, PlanApi } from './plan-api.service';

export type RequestReadState = 'absent' | 'error' | 'loading' | 'ready';

@Injectable({ providedIn: 'root' })
export class PlanRequestHistoryService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #current = signal<GroupPublishedRequest | null>(null);
  readonly #currentState = signal<RequestReadState>('loading');
  readonly #items = signal<readonly GroupPublishedRequest[]>([]);
  readonly #historyState = signal<RequestReadState>('loading');
  readonly #historyProblemCode = signal<string | null>(null);
  readonly #nextCursor = signal<string | null>(null);
  readonly #detail = signal<GroupPublishedRequest | null>(null);
  readonly #detailRequestId = signal<string | null>(null);
  readonly #detailState = signal<RequestReadState>('absent');
  readonly #detailProblemCode = signal<string | null>(null);
  #planId: string | null = null;
  #currentReadId = 0;
  #historyReadId = 0;
  #detailReadId = 0;

  readonly current = this.#current.asReadonly();
  readonly currentState = this.#currentState.asReadonly();
  readonly items = this.#items.asReadonly();
  readonly historyState = this.#historyState.asReadonly();
  readonly historyProblemCode = this.#historyProblemCode.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly detail = this.#detail.asReadonly();
  readonly detailRequestId = this.#detailRequestId.asReadonly();
  readonly detailState = this.#detailState.asReadonly();
  readonly detailProblemCode = this.#detailProblemCode.asReadonly();

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
    const detailRequestId = this.#detailRequestId();
    await Promise.all([this.refreshCurrent(planId), this.refreshHistory(planId)]);
    if (this.#planId === planId && detailRequestId === this.#detailRequestId()) {
      await this.readDetail(planId, detailRequestId);
    }
  }

  async refreshCurrent(planId: string): Promise<void> {
    this.usePlan(planId);
    const readId = ++this.#currentReadId;
    this.#current.set(null);
    this.#currentState.set('loading');
    try {
      const result = await firstValueFrom(this.#api.readCurrentPublishedRequest(planId));
      if (!this.isCurrentCurrentRead(readId, planId)) {
        return;
      }
      if (result.body === null) {
        this.#currentState.set('error');
        return;
      }
      this.#current.set(result.body);
      this.#currentState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentCurrentRead(readId, planId)) {
        return;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#currentState.set(apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND' ? 'absent' : 'error');
    }
  }

  async refreshHistory(planId: string): Promise<void> {
    this.usePlan(planId);
    const readId = ++this.#historyReadId;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    await this.loadHistoryPage(planId, null, readId, false);
  }

  async loadMore(planId: string): Promise<void> {
    const cursor = this.#nextCursor();
    if (this.#planId !== planId || cursor === null || this.#historyState() === 'loading') {
      return;
    }
    const readId = ++this.#historyReadId;
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    await this.loadHistoryPage(planId, cursor, readId, true);
  }

  async readDetail(planId: string, requestId: string | null): Promise<void> {
    this.usePlan(planId);
    const readId = ++this.#detailReadId;
    this.#detail.set(null);
    this.#detailRequestId.set(requestId);
    this.#detailProblemCode.set(null);
    if (requestId === null) {
      this.#detailState.set('absent');
      return;
    }
    this.#detailState.set('loading');
    try {
      const result = await firstValueFrom(this.#api.readPublishedRequest(planId, requestId));
      if (!this.isCurrentDetailRead(readId, planId, requestId)) {
        return;
      }
      if (result.body === null) {
        this.#detailState.set('error');
        return;
      }
      this.#detail.set(result.body);
      this.#detailState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentDetailRead(readId, planId, requestId)) {
        return;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#detailProblemCode.set(apiError?.problem.code ?? null);
      this.#detailState.set('error');
    }
  }

  private async loadHistoryPage(
    planId: string,
    cursor: string | null,
    readId: number,
    append: boolean,
  ): Promise<void> {
    try {
      const result = await firstValueFrom(this.#api.listPublishedRequestHistory(planId, cursor));
      if (!this.isCurrentHistoryRead(readId, planId)) {
        return;
      }
      if (result.body === null || !Array.isArray(result.body.items)) {
        this.#historyState.set('error');
        return;
      }
      const existing = append ? this.#items() : [];
      const itemIds = new Set(existing.map((item) => item.requestId));
      this.#items.set([...existing, ...result.body.items.filter((item) => !itemIds.has(item.requestId))]);
      this.#nextCursor.set(result.body.nextCursor ?? null);
      this.#historyState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentHistoryRead(readId, planId)) {
        return;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#historyProblemCode.set(apiError?.problem.code ?? null);
      this.#historyState.set('error');
      if (apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND') {
        this.clearPrivateContent();
      }
    }
  }

  private clearPrivateContent(): void {
    this.#currentReadId += 1;
    this.#historyReadId += 1;
    this.#detailReadId += 1;
    this.#current.set(null);
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#detail.set(null);
  }

  private reset(): void {
    this.#currentReadId += 1;
    this.#historyReadId += 1;
    this.#detailReadId += 1;
    this.#planId = null;
    this.#current.set(null);
    this.#currentState.set('loading');
    this.#items.set([]);
    this.#historyState.set('loading');
    this.#historyProblemCode.set(null);
    this.#nextCursor.set(null);
    this.#detail.set(null);
    this.#detailRequestId.set(null);
    this.#detailState.set('absent');
    this.#detailProblemCode.set(null);
  }

  private isCurrentCurrentRead(readId: number, planId: string): boolean {
    return readId === this.#currentReadId && planId === this.#planId;
  }

  private isCurrentHistoryRead(readId: number, planId: string): boolean {
    return readId === this.#historyReadId && planId === this.#planId;
  }

  private isCurrentDetailRead(readId: number, planId: string, requestId: string): boolean {
    return readId === this.#detailReadId
      && planId === this.#planId
      && requestId === this.#detailRequestId();
  }
}

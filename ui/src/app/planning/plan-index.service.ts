import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PlanApi, PlanPage, PlanSummary } from './plan-api.service';

export type PlanIndexState = 'empty' | 'error' | 'loading' | 'loading-more' | 'not-found' | 'ready' | 'refreshing';

@Injectable({ providedIn: 'root' })
export class PlanIndexService {
  readonly #api = inject(PlanApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #plans = signal<readonly PlanSummary[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #state = signal<PlanIndexState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #groupId: string | null = null;
  #loadedPageCount = 0;
  #pending = false;
  #requestId = 0;

  readonly plans = this.#plans.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  async refresh(groupId: string): Promise<void> {
    if (this.#groupId !== groupId) {
      this.#requestId += 1;
      this.#pending = false;
      this.clearGroup(groupId);
    }
    if (this.#pending) { return; }
    this.#correlationId.set(null);
    this.#state.set(this.#plans().length === 0 ? 'loading' : 'refreshing');
    await this.loadInitialPages(Math.max(this.#loadedPageCount, 1));
  }

  async loadMore(): Promise<void> {
    const cursor = this.#nextCursor();
    const groupId = this.#groupId;
    if (cursor === null || groupId === null || this.#pending) { return; }
    this.#state.set('loading-more');
    const requestId = this.beginRequest();
    try {
      const result = await firstValueFrom(this.#api.list(groupId, cursor));
      if (!this.isCurrentRequest(requestId, groupId)) { return; }
      this.#plans.set(appendDistinct(this.#plans(), result.body?.items ?? []));
      this.#nextCursor.set(result.body?.nextCursor ?? null);
      this.#loadedPageCount += 1;
      this.#state.set('ready');
    } catch (error: unknown) { this.handleError(error, requestId, groupId); }
    finally { this.finishRequest(requestId, groupId); }
  }

  discardInaccessibleGroup(groupId: string): void {
    if (this.#groupId !== groupId) {
      return;
    }
    this.#requestId += 1;
    this.#pending = false;
    this.#plans.set([]);
    this.#nextCursor.set(null);
    this.#loadedPageCount = 0;
    this.#correlationId.set(null);
    this.#state.set('not-found');
  }

  private async loadInitialPages(pageCount: number): Promise<void> {
    const groupId = this.#groupId;
    if (groupId === null) { return; }
    const requestId = this.beginRequest();
    try {
      let items: readonly PlanSummary[] = [];
      let cursor: string | null = null;
      let fetchedPageCount = 0;
      for (let page = 0; page < pageCount; page += 1) {
        const result: ApiHttpResult<PlanPage> = await firstValueFrom(this.#api.list(groupId, cursor));
        if (!this.isCurrentRequest(requestId, groupId)) { return; }
        fetchedPageCount += 1;
        items = appendDistinct(items, result.body?.items ?? []);
        cursor = result.body?.nextCursor ?? null;
        if (cursor === null) { break; }
      }
      if (!this.isCurrentRequest(requestId, groupId)) { return; }
      this.#plans.set(items);
      this.#nextCursor.set(cursor);
      this.#loadedPageCount = fetchedPageCount;
      this.#state.set(items.length === 0 ? 'empty' : 'ready');
    } catch (error: unknown) { this.handleError(error, requestId, groupId); }
    finally { this.finishRequest(requestId, groupId); }
  }

  private handleError(error: unknown, requestId: number, groupId: string): void {
    if (!this.isCurrentRequest(requestId, groupId)) { return; }
    const apiError = error instanceof ApiHttpError ? error : null;
    this.#correlationId.set(apiError?.correlationId ?? null);
    if (apiError?.status === 403 || apiError?.status === 404) {
      this.#plans.set([]);
      this.#nextCursor.set(null);
      this.#loadedPageCount = 0;
      this.#state.set(apiError.status === 404 ? 'not-found' : 'error');
      return;
    }
    this.#state.set('error');
  }

  private reset(): void { this.#requestId += 1; this.#pending = false; this.clearGroup(null); }
  private clearGroup(groupId: string | null): void {
    this.#groupId = groupId; this.#plans.set([]); this.#nextCursor.set(null); this.#correlationId.set(null);
    this.#loadedPageCount = 0; this.#state.set('loading');
  }
  private beginRequest(): number { this.#pending = true; return ++this.#requestId; }
  private finishRequest(requestId: number, groupId: string): void { if (this.isCurrentRequest(requestId, groupId)) { this.#pending = false; } }
  private isCurrentRequest(requestId: number, groupId: string): boolean { return requestId === this.#requestId && groupId === this.#groupId; }
}

function appendDistinct(current: readonly PlanSummary[], next: readonly PlanSummary[]): readonly PlanSummary[] {
  const knownIds = new Set(current.map((plan) => plan.planId));
  return [...current, ...next.filter((plan) => {
    if (knownIds.has(plan.planId)) { return false; }
    knownIds.add(plan.planId);
    return true;
  })];
}

import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderPublishedRequest } from './provider-api.service';

export type ProviderRequestFeedState = 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing';
export type ProviderRequestDetailState = 'absent' | 'error' | 'loading' | 'ready';

@Injectable({ providedIn: 'root' })
export class ProviderRequestFeedService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #items = signal<readonly ProviderPublishedRequest[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #state = signal<ProviderRequestFeedState>('loading');
  readonly #correlationId = signal<string | null>(null);
  readonly #detail = signal<ProviderPublishedRequest | null>(null);
  readonly #detailRequestId = signal<string | null>(null);
  readonly #detailState = signal<ProviderRequestDetailState>('absent');
  readonly #detailCorrelationId = signal<string | null>(null);
  #providerId: string | null = null;
  #feedRequestId = 0;
  #detailReadId = 0;
  #pending = false;

  readonly items = this.#items.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly detail = this.#detail.asReadonly();
  readonly detailRequestId = this.#detailRequestId.asReadonly();
  readonly detailState = this.#detailState.asReadonly();
  readonly detailCorrelationId = this.#detailCorrelationId.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  async refresh(providerId: string): Promise<void> {
    this.useProvider(providerId);
    if (this.#pending) return;
    const requestId = ++this.#feedRequestId;
    const selectedRequestId = this.#detailRequestId();
    let selectedDetailReadId: number | null = null;
    this.#pending = true;
    this.#correlationId.set(null);
    this.#state.set(this.#items().length === 0 ? 'loading' : 'refreshing');
    if (selectedRequestId !== null) {
      selectedDetailReadId = ++this.#detailReadId;
      this.#detail.set(null);
      this.#detailState.set('loading');
      this.#detailCorrelationId.set(null);
    }
    try {
      const result = await firstValueFrom(this.#api.listRequestFeed(providerId));
      if (!this.isCurrentFeed(requestId, providerId)) return;
      const items = result.body?.items ?? [];
      this.#items.set(items);
      this.#nextCursor.set(result.body?.nextCursor ?? null);
      this.#state.set(items.length === 0 ? 'empty' : 'ready');
      if (this.isCurrentSelectedDetail(selectedRequestId, selectedDetailReadId)) {
        await this.readDetail(providerId, selectedRequestId);
      }
    } catch (error: unknown) {
      if (!this.isCurrentFeed(requestId, providerId)) return;
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      if (apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND') this.clearPrivateContent();
      else if (this.isCurrentSelectedDetail(selectedRequestId, selectedDetailReadId)) {
        this.#detailState.set('error');
        this.#detailCorrelationId.set(apiError?.correlationId ?? null);
      }
      this.#state.set('error');
    } finally {
      if (this.isCurrentFeed(requestId, providerId)) this.#pending = false;
    }
  }

  async loadMore(providerId: string): Promise<void> {
    this.useProvider(providerId);
    const cursor = this.#nextCursor();
    if (cursor === null || this.#pending) return;
    const requestId = ++this.#feedRequestId;
    this.#pending = true;
    this.#state.set('loading-more');
    try {
      const result = await firstValueFrom(this.#api.listRequestFeed(providerId, cursor));
      if (!this.isCurrentFeed(requestId, providerId)) return;
      this.#items.set(appendDistinct(this.#items(), result.body?.items ?? []));
      this.#nextCursor.set(result.body?.nextCursor ?? null);
      this.#state.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentFeed(requestId, providerId)) return;
      this.#correlationId.set(error instanceof ApiHttpError ? error.correlationId : null);
      const apiError = error instanceof ApiHttpError ? error : null;
      if (apiError?.problem.code === 'PRIVATE_RESOURCE_NOT_FOUND') this.clearPrivateContent();
      this.#state.set('error');
    } finally {
      if (this.isCurrentFeed(requestId, providerId)) this.#pending = false;
    }
  }

  async readDetail(providerId: string, requestId: string | null): Promise<void> {
    this.useProvider(providerId);
    const readId = ++this.#detailReadId;
    this.#detail.set(null);
    this.#detailRequestId.set(requestId);
    this.#detailCorrelationId.set(null);
    if (requestId === null) {
      this.#detailState.set('absent');
      return;
    }
    this.#detailState.set('loading');
    try {
      const result = await firstValueFrom(this.#api.readPublishedRequest(providerId, requestId));
      if (!this.isCurrentDetail(readId, providerId, requestId)) return;
      if (result.body === null) {
        this.#detailState.set('error');
        return;
      }
      this.#detail.set(result.body);
      this.#detailState.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrentDetail(readId, providerId, requestId)) return;
      this.#detailCorrelationId.set(error instanceof ApiHttpError ? error.correlationId : null);
      this.#detailState.set('error');
    }
  }

  private useProvider(providerId: string): void {
    if (this.#providerId !== providerId) {
      this.reset();
      this.#providerId = providerId;
    }
  }

  private clearPrivateContent(): void {
    this.#detailReadId += 1;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#detail.set(null);
    this.#detailRequestId.set(null);
    this.#detailState.set('absent');
    this.#detailCorrelationId.set(null);
  }

  private reset(): void {
    this.#feedRequestId += 1;
    this.#detailReadId += 1;
    this.#pending = false;
    this.#providerId = null;
    this.#items.set([]);
    this.#nextCursor.set(null);
    this.#state.set('loading');
    this.#correlationId.set(null);
    this.#detail.set(null);
    this.#detailRequestId.set(null);
    this.#detailState.set('absent');
    this.#detailCorrelationId.set(null);
  }

  private isCurrentFeed(requestId: number, providerId: string): boolean {
    return requestId === this.#feedRequestId && providerId === this.#providerId;
  }

  private isCurrentDetail(readId: number, providerId: string, requestId: string): boolean {
    return readId === this.#detailReadId && providerId === this.#providerId && requestId === this.#detailRequestId();
  }

  private isCurrentSelectedDetail(requestId: string | null, detailReadId: number | null): requestId is string {
    return requestId !== null && detailReadId !== null
      && detailReadId === this.#detailReadId
      && requestId === this.#detailRequestId();
  }
}

function appendDistinct(
  current: readonly ProviderPublishedRequest[],
  next: readonly ProviderPublishedRequest[],
): readonly ProviderPublishedRequest[] {
  const knownIds = new Set(current.map((request) => request.requestId));
  return [...current, ...next.filter((request) => {
    if (knownIds.has(request.requestId)) return false;
    knownIds.add(request.requestId);
    return true;
  })];
}

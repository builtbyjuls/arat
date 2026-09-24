import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderPage, ProviderSummary } from './provider-api.service';

export type ProviderIndexState = 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing';

@Injectable({ providedIn: 'root' })
export class ProviderIndexService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #providers = signal<readonly ProviderSummary[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #state = signal<ProviderIndexState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #loadedPageCount = 0;
  #pending = false;
  #requestId = 0;

  readonly providers = this.#providers.asReadonly();
  readonly nextCursor = this.#nextCursor.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  async refresh(): Promise<void> {
    if (this.#pending) {
      return;
    }

    this.#correlationId.set(null);
    this.#state.set(this.#providers().length === 0 ? 'loading' : 'refreshing');
    await this.loadInitialPages(Math.max(this.#loadedPageCount, 1));
  }

  async loadMore(): Promise<void> {
    const cursor = this.#nextCursor();
    if (cursor === null || this.#pending) {
      return;
    }

    this.#state.set('loading-more');
    const requestId = this.beginRequest();
    try {
      const result = await firstValueFrom(this.#api.list(cursor));
      if (!this.isCurrentRequest(requestId)) {
        return;
      }
      this.#providers.set(appendDistinct(this.#providers(), result.body?.items ?? []));
      this.#nextCursor.set(result.body?.nextCursor ?? null);
      this.#loadedPageCount += 1;
      this.#state.set('ready');
    } catch (error: unknown) {
      if (this.isCurrentRequest(requestId)) {
        this.#correlationId.set(error instanceof ApiHttpError ? error.correlationId : null);
        this.#state.set('error');
      }
    } finally {
      this.finishRequest(requestId);
    }
  }

  private async loadInitialPages(pageCount: number): Promise<void> {
    const requestId = this.beginRequest();
    try {
      let items: readonly ProviderSummary[] = [];
      let cursor: string | null = null;
      let fetchedPageCount = 0;
      for (let page = 0; page < pageCount; page += 1) {
        const result: ApiHttpResult<ProviderPage> = await firstValueFrom(this.#api.list(cursor));
        if (!this.isCurrentRequest(requestId)) {
          return;
        }
        fetchedPageCount += 1;
        items = appendDistinct(items, result.body?.items ?? []);
        cursor = result.body?.nextCursor ?? null;
        if (cursor === null) {
          break;
        }
      }
      if (!this.isCurrentRequest(requestId)) {
        return;
      }
      this.#providers.set(items);
      this.#nextCursor.set(cursor);
      this.#loadedPageCount = fetchedPageCount;
      this.#state.set(items.length === 0 ? 'empty' : 'ready');
    } catch (error: unknown) {
      if (this.isCurrentRequest(requestId)) {
        this.#correlationId.set(error instanceof ApiHttpError ? error.correlationId : null);
        this.#state.set('error');
      }
    } finally {
      this.finishRequest(requestId);
    }
  }

  private reset(): void {
    this.#requestId += 1;
    this.#pending = false;
    this.#providers.set([]);
    this.#nextCursor.set(null);
    this.#correlationId.set(null);
    this.#loadedPageCount = 0;
    this.#state.set('loading');
  }

  private beginRequest(): number {
    this.#pending = true;
    this.#requestId += 1;
    return this.#requestId;
  }

  private finishRequest(requestId: number): void {
    if (this.isCurrentRequest(requestId)) {
      this.#pending = false;
    }
  }

  private isCurrentRequest(requestId: number): boolean {
    return requestId === this.#requestId;
  }
}

function appendDistinct(
  current: readonly ProviderSummary[],
  next: readonly ProviderSummary[],
): readonly ProviderSummary[] {
  const knownIds = new Set(current.map((provider) => provider.providerId));
  return [...current, ...next.filter((provider) => {
    if (knownIds.has(provider.providerId)) {
      return false;
    }
    knownIds.add(provider.providerId);
    return true;
  })];
}

import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiHttpResult } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi, GroupPage, GroupSummary } from './group-api.service';

export type GroupIndexState = 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing';

@Injectable({ providedIn: 'root' })
export class GroupIndexService {
  readonly #api = inject(GroupApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #groups = signal<readonly GroupSummary[]>([]);
  readonly #nextCursor = signal<string | null>(null);
  readonly #state = signal<GroupIndexState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #loadedPageCount = 0;
  #pending = false;
  #requestId = 0;

  readonly groups = this.#groups.asReadonly();
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
    this.#state.set(this.#groups().length === 0 ? 'loading' : 'refreshing');
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

      const pageItems = result.body?.items ?? [];
      this.#groups.set(appendDistinct(this.#groups(), pageItems));
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
      let items: readonly GroupSummary[] = [];
      let cursor: string | null = null;
      let fetchedPageCount = 0;
      for (let page = 0; page < pageCount; page += 1) {
        const result: ApiHttpResult<GroupPage> = await firstValueFrom(this.#api.list(cursor));
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
      this.#groups.set(items);
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
    this.#groups.set([]);
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
  current: readonly GroupSummary[],
  next: readonly GroupSummary[],
): readonly GroupSummary[] {
  const knownIds = new Set(current.map((group) => group.groupId));
  return [...current, ...next.filter((group) => {
    if (knownIds.has(group.groupId)) {
      return false;
    }
    knownIds.add(group.groupId);
    return true;
  })];
}

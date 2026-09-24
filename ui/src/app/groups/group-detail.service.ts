import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { LocalActorSession } from '../identity/local-actor-session.service';
import { GroupApi, GroupDetail } from './group-api.service';

export type GroupDetailState = 'error' | 'loading' | 'not-found' | 'ready';

@Injectable({ providedIn: 'root' })
export class GroupDetailService {
  readonly #api = inject(GroupApi);
  readonly #session = inject(LocalActorSession);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #group = signal<GroupDetail | null>(null);
  readonly #etag = signal<string | null>(null);
  readonly #state = signal<GroupDetailState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #resourceId: string | null = null;
  #requestId = 0;

  readonly group = this.#group.asReadonly();
  readonly etag = this.#etag.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  async load(groupId: string): Promise<void> {
    if (this.#resourceId !== groupId) {
      this.clearResource(groupId);
    }
    const requestId = ++this.#requestId;
    this.#state.set('loading');
    this.#correlationId.set(null);
    try {
      const result = await firstValueFrom(this.#api.read(groupId));
      if (!this.isCurrent(requestId, groupId)) {
        return;
      }
      if (result.body === null) {
        this.#group.set(null);
        this.#etag.set(null);
        this.#state.set('error');
        return;
      }
      this.#group.set(result.body);
      this.#etag.set(result.etag);
      this.#state.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrent(requestId, groupId)) {
        return;
      }
      this.#group.set(null);
      this.#etag.set(null);
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#state.set(apiError?.status === 404 ? 'not-found' : 'error');
    }
  }

  callerRole(): string | null {
    const actorId = this.#session.actor()?.actorId;
    return this.#group()?.members?.find((member) => member.accountId === actorId)?.role ?? null;
  }

  private reset(): void {
    this.#requestId += 1;
    this.clearResource(null);
  }

  private clearResource(groupId: string | null): void {
    this.#resourceId = groupId;
    this.#group.set(null);
    this.#etag.set(null);
    this.#correlationId.set(null);
    this.#state.set('loading');
  }

  private isCurrent(requestId: number, groupId: string): boolean {
    return requestId === this.#requestId && groupId === this.#resourceId;
  }
}

import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  InvitationAcceptanceIntent,
  InvitationAcceptanceTokenMismatchError,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi, GroupMembership } from './group-api.service';

export type InvitationAcceptanceState = 'conflict' | 'idle' | 'retryable' | 'submitting' | 'unavailable';

@Injectable({ providedIn: 'root' })
export class GroupInvitationAcceptanceService {
  readonly #api = inject(GroupApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<InvitationAcceptanceState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #retryTokenMismatch = signal(false);
  #intent: InvitationAcceptanceIntent | null = null;
  #requestId = 0;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly retryTokenMismatch = this.#retryTokenMismatch.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.clear());
  }

  async accept(token: string): Promise<GroupMembership | null> {
    if (this.#state() === 'submitting' || token.length === 0) {
      return null;
    }

    const requestId = ++this.#requestId;
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#retryTokenMismatch.set(false);

    try {
      const intent = this.#intent ?? await this.#api.beginInvitationAcceptance(token);
      if (!this.isCurrent(requestId)) {
        return null;
      }

      this.#intent = intent;
      const result = await firstValueFrom(this.#api.acceptInvitation(intent, token));
      if (!this.isCurrent(requestId)) {
        return null;
      }

      this.#intent = null;
      this.#state.set('idle');
      return result.body;
    } catch (error: unknown) {
      if (!this.isCurrent(requestId)) {
        return null;
      }

      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      if (error instanceof InvitationAcceptanceTokenMismatchError) {
        this.#state.set('retryable');
        this.#retryTokenMismatch.set(true);
      } else if (apiError?.status === 0) {
        this.#state.set('retryable');
      } else if (apiError?.problem.code === 'IDEMPOTENCY_KEY_REUSED') {
        this.#intent = null;
        this.#state.set('conflict');
      } else {
        this.#intent = null;
        this.#state.set('unavailable');
      }
      return null;
    }
  }

  clear(): void {
    this.#requestId += 1;
    this.#intent = null;
    this.#correlationId.set(null);
    this.#retryTokenMismatch.set(false);
    this.#state.set('idle');
  }

  private isCurrent(requestId: number): boolean {
    return requestId === this.#requestId;
  }
}

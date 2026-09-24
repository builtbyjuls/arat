import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiProblemViolation, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateInvitationRequest, GroupApi } from './group-api.service';
import { GroupDetailService } from './group-detail.service';

export type GroupInvitationState = 'error' | 'idle' | 'retryable' | 'submitting' | 'success';

@Injectable({ providedIn: 'root' })
export class GroupInvitationService {
  readonly #api = inject(GroupApi);
  readonly #groupDetail = inject(GroupDetailService);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<GroupInvitationState>('idle');
  readonly #token = signal<string | null>(null);
  readonly #correlationId = signal<string | null>(null);
  readonly #errorCode = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #intent: IdempotentMutationIntent<CreateInvitationRequest> | null = null;
  #groupId: string | null = null;
  #requestId = 0;

  readonly state = this.#state.asReadonly();
  readonly token = this.#token.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly errorCode = this.#errorCode.asReadonly();
  readonly violations = this.#violations.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.clear());
  }

  async create(groupId: string, request: CreateInvitationRequest): Promise<void> {
    this.clear();
    this.#groupId = groupId;
    this.#intent = this.#api.createInvitationIntent(groupId, request);
    await this.send(this.#intent, groupId, ++this.#requestId);
  }

  async retry(): Promise<void> {
    if (this.#intent === null || this.#groupId === null || this.#state() !== 'retryable') {
      return;
    }

    await this.send(this.#intent, this.#groupId, ++this.#requestId);
  }

  clear(): void {
    this.#requestId += 1;
    this.#intent = null;
    this.#groupId = null;
    this.#token.set(null);
    this.#correlationId.set(null);
    this.#errorCode.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
  }

  private async send(
    intent: IdempotentMutationIntent<CreateInvitationRequest>,
    groupId: string,
    requestId: number,
  ): Promise<void> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#errorCode.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.createInvitation(intent));
      if (!this.isCurrent(requestId)) {
        return;
      }
      if (result.body === null || typeof result.body.token !== 'string' || result.body.token.length === 0) {
        this.#state.set('error');
        return;
      }

      this.#token.set(result.body.token);
      this.#state.set('success');
    } catch (error: unknown) {
      if (!this.isCurrent(requestId)) {
        return;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#errorCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      this.#state.set(apiError?.status === 0 ? 'retryable' : 'error');
      if (apiError?.status === 403 || apiError?.status === 404) {
        await this.#groupDetail.load(groupId);
      }
    }
  }

  private isCurrent(requestId: number): boolean {
    return requestId === this.#requestId;
  }

  violationFor(field: string): ApiProblemViolation | null {
    return this.#violations().find((violation) => violation.field === field) ?? null;
  }
}

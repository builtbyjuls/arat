import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError, ApiHttpResult, ApiProblemViolation, IdempotentMutationIntent } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateProviderRequest, ProviderApi, ProviderRepresentation } from './provider-api.service';

export type ProviderCreationState = 'error' | 'idle' | 'network-error' | 'submitting';

@Injectable({ providedIn: 'root' })
export class ProviderCreationService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<ProviderCreationState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #problemCode = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #intent: IdempotentMutationIntent<CreateProviderRequest> | null = null;
  #attemptId = 0;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  async create(request: CreateProviderRequest): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    if (this.#state() === 'submitting') return null;
    const intent = this.#api.createIntent(request);
    this.#intent = intent;
    return this.execute(intent, ++this.#attemptId);
  }

  async retry(): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    return this.#state() === 'network-error' && this.#intent !== null
      ? this.execute(this.#intent, ++this.#attemptId)
      : null;
  }

  dismissError(): void { this.reset(); }

  reportMissingLocation(): void {
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    this.#state.set('error');
  }

  violationFor(field: string): string | null {
    return this.#violations().find((violation) => violation.field === field)?.message ?? null;
  }

  private async execute(intent: IdempotentMutationIntent<CreateProviderRequest>, attemptId: number): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.create(intent));
      if (!this.isCurrentAttempt(intent, attemptId)) return null;
      this.#intent = null;
      this.#state.set('idle');
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(intent, attemptId)) return null;
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.status === 0) this.#state.set('network-error');
      else {
        this.#intent = null;
        this.#state.set('error');
      }
      return null;
    }
  }

  private reset(): void {
    this.#attemptId += 1;
    this.#intent = null;
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
  }

  private isCurrentAttempt(intent: IdempotentMutationIntent<CreateProviderRequest>, attemptId: number): boolean {
    return this.#intent === intent && this.#attemptId === attemptId;
  }
}

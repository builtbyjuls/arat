import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApiHttpError,
  ApiHttpResult,
  ApiProblemViolation,
} from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  CreateProviderRequest,
  ProviderApi,
  ProviderRepresentation,
} from './provider-api.service';

export type ProviderProfileEditState =
  | 'conflict'
  | 'error'
  | 'idle'
  | 'network-error'
  | 'reapply-ready'
  | 'submitting';

interface ProfileReplacementAttempt {
  readonly providerId: string;
  readonly request: CreateProviderRequest;
  readonly etag: string;
}

@Injectable({ providedIn: 'root' })
export class ProviderProfileEditService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #state = signal<ProviderProfileEditState>('idle');
  readonly #correlationId = signal<string | null>(null);
  readonly #problemCode = signal<string | null>(null);
  readonly #violations = signal<readonly ApiProblemViolation[]>([]);
  #attempt: ProfileReplacementAttempt | null = null;
  #attemptId = 0;
  #providerId: string | null = null;
  #reapplyEtag: string | null = null;

  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();
  readonly problemCode = this.#problemCode.asReadonly();
  readonly violations = this.#violations.asReadonly();

  constructor() {
    this.#scopeReset.register(() => this.reset());
  }

  useProvider(providerId: string): void {
    if (this.#providerId !== providerId) {
      this.reset();
      this.#providerId = providerId;
    }
  }

  releaseProvider(providerId: string): void {
    if (this.#providerId === providerId) {
      this.reset();
    }
  }

  replace(
    providerId: string,
    request: CreateProviderRequest,
    etag: string,
  ): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    if (this.#state() !== 'idle') {
      return Promise.resolve(null);
    }
    this.useProvider(providerId);
    return this.beginAttempt(providerId, request, etag);
  }

  retry(): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    return this.#state() === 'network-error' && this.#attempt !== null
      ? this.execute(this.#attempt, ++this.#attemptId)
      : Promise.resolve(null);
  }

  markRefreshed(etag: string): void {
    if (this.#state() === 'conflict') {
      this.#reapplyEtag = etag;
      this.#state.set('reapply-ready');
    }
  }

  reapply(
    providerId: string,
    request: CreateProviderRequest,
  ): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    if (
      this.#state() !== 'reapply-ready'
      || this.#reapplyEtag === null
      || this.#providerId !== providerId
    ) {
      return Promise.resolve(null);
    }
    return this.beginAttempt(providerId, request, this.#reapplyEtag);
  }

  dismiss(): void {
    this.clearOutcome();
  }

  violationFor(field: string): string | null {
    return this.#violations().find((violation) => violation.field === field)?.message ?? null;
  }

  private beginAttempt(
    providerId: string,
    request: CreateProviderRequest,
    etag: string,
  ): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    const attempt = Object.freeze({ providerId, request: structuredClone(request), etag });
    this.#attempt = attempt;
    this.#reapplyEtag = null;
    return this.execute(attempt, ++this.#attemptId);
  }

  private async execute(
    attempt: ProfileReplacementAttempt,
    attemptId: number,
  ): Promise<ApiHttpResult<ProviderRepresentation> | null> {
    this.#state.set('submitting');
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    try {
      const result = await firstValueFrom(this.#api.replaceProfile(
        attempt.providerId,
        attempt.request,
        attempt.etag,
      ));
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      if (result.body === null || result.etag === null) {
        this.#attempt = null;
        this.#problemCode.set('INVALID_REPLACEMENT_RESPONSE');
        this.#state.set('error');
        return null;
      }
      this.clearOutcome();
      return result;
    } catch (error: unknown) {
      if (!this.isCurrentAttempt(attempt, attemptId)) {
        return null;
      }
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#problemCode.set(apiError?.problem.code ?? null);
      this.#violations.set(apiError?.problem.violations ?? []);
      if (apiError?.status === 0) {
        this.#state.set('network-error');
      } else if (apiError?.status === 412) {
        this.#attempt = null;
        this.#state.set('conflict');
      } else {
        this.#attempt = null;
        this.#state.set('error');
      }
      return null;
    }
  }

  private clearOutcome(): void {
    this.#attemptId += 1;
    this.#attempt = null;
    this.#reapplyEtag = null;
    this.#correlationId.set(null);
    this.#problemCode.set(null);
    this.#violations.set([]);
    this.#state.set('idle');
  }

  private reset(): void {
    this.clearOutcome();
    this.#providerId = null;
  }

  private isCurrentAttempt(attempt: ProfileReplacementAttempt, attemptId: number): boolean {
    return this.#attempt === attempt
      && this.#attemptId === attemptId
      && this.#providerId === attempt.providerId;
  }
}

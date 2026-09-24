import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiHttpError } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { ProviderApi, ProviderDetail, ProviderRepresentation } from './provider-api.service';

export type ProviderDetailState = 'error' | 'loading' | 'not-found' | 'ready';

@Injectable({ providedIn: 'root' })
export class ProviderDetailService {
  readonly #api = inject(ProviderApi);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #provider = signal<ProviderDetail | null>(null);
  readonly #etag = signal<string | null>(null);
  readonly #state = signal<ProviderDetailState>('loading');
  readonly #correlationId = signal<string | null>(null);
  #resourceId: string | null = null;
  #requestId = 0;

  readonly provider = this.#provider.asReadonly();
  readonly etag = this.#etag.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly correlationId = this.#correlationId.asReadonly();

  constructor() { this.#scopeReset.register(() => this.reset()); }

  async load(providerId: string): Promise<void> {
    if (this.#resourceId !== providerId) this.clearResource(providerId);
    const requestId = ++this.#requestId;
    this.#state.set('loading');
    this.#correlationId.set(null);
    try {
      const result = await firstValueFrom(this.#api.read(providerId));
      if (!this.isCurrent(requestId, providerId)) return;
      if (result.body === null) {
        this.#provider.set(null);
        this.#etag.set(null);
        this.#state.set('error');
        return;
      }
      this.#provider.set(result.body);
      this.#etag.set(result.etag);
      this.#state.set('ready');
    } catch (error: unknown) {
      if (!this.isCurrent(requestId, providerId)) return;
      this.#provider.set(null);
      this.#etag.set(null);
      const apiError = error instanceof ApiHttpError ? error : null;
      this.#correlationId.set(apiError?.correlationId ?? null);
      this.#state.set(apiError?.status === 404 ? 'not-found' : 'error');
    }
  }

  applyProfile(provider: ProviderRepresentation, etag: string): void {
    const current = this.#provider();
    if (current === null || current.providerId !== provider.providerId || etag.length === 0) {
      return;
    }
    this.#requestId += 1;
    this.#provider.set({ ...provider, callerStaffRole: current.callerStaffRole });
    this.#etag.set(etag);
    this.#state.set('ready');
  }

  markUnavailable(): void {
    this.#requestId += 1;
    this.#provider.set(null);
    this.#etag.set(null);
    this.#correlationId.set(null);
    this.#state.set('not-found');
  }

  private reset(): void { this.#requestId += 1; this.clearResource(null); }

  private clearResource(providerId: string | null): void {
    this.#resourceId = providerId;
    this.#provider.set(null);
    this.#etag.set(null);
    this.#correlationId.set(null);
    this.#state.set('loading');
  }

  private isCurrent(requestId: number, providerId: string): boolean {
    return requestId === this.#requestId && providerId === this.#resourceId;
  }
}

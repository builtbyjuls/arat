import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ActorScopeResetService {
  readonly generation = signal(0);
  readonly #resetters = new Set<() => void>();
  readonly #authenticationFailureHandlers = new Set<() => void>();

  register(reset: () => void): () => void {
    this.#resetters.add(reset);
    return () => this.#resetters.delete(reset);
  }

  reset(): void {
    this.generation.update((generation) => generation + 1);
    this.#resetters.forEach((reset) => reset());
  }

  registerAuthenticationFailureHandler(handler: () => void): () => void {
    this.#authenticationFailureHandlers.add(handler);
    return () => this.#authenticationFailureHandlers.delete(handler);
  }

  invalidateAuthentication(generation: number | null): void {
    if (generation !== null && generation === this.generation()) {
      this.#authenticationFailureHandlers.forEach((handler) => handler());
    }
  }

  requireCurrent(generation: number): void {
    if (generation !== this.generation()) {
      throw new Error('The local actor changed before this request completed.');
    }
  }
}

import { InjectionToken, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiHttpClient } from '../api/api-http-client';
import { ActorScopeResetService } from './actor-scope-reset.service';
import { environment } from '../../environments/environment';
import { LocalDemoActor } from '../../environments/environment.model';

const SELECTED_PERSONA_STORAGE_KEY = 'arat.local-demo.selected-persona';
const UNAVAILABLE_MESSAGE = 'Local development identity is unavailable.';

export const LOCAL_DEMO_ACTORS = new InjectionToken<readonly LocalDemoActor[] | null>(
  'LOCAL_DEMO_ACTORS',
  { factory: () => environment.localDemo },
);

export const SESSION_STORAGE = new InjectionToken<Storage>('SESSION_STORAGE', {
  factory: () => globalThis.sessionStorage,
});

export type LocalActorIdentityState = 'idle' | 'validating' | 'verified' | 'unavailable';

interface WhoAmIResponse {
  readonly actorId: string;
  readonly platformRoles: readonly string[];
}

@Injectable({ providedIn: 'root' })
export class LocalActorSession {
  readonly actors = inject(LOCAL_DEMO_ACTORS);
  readonly #api = inject(ApiHttpClient);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #router = inject(Router);
  readonly #storage = inject(SESSION_STORAGE);
  readonly #storedPersona = this.restoreStoredPersona();
  readonly #selectedPersonaId = signal<string | null>(this.#storedPersona.personaId);
  readonly #state = signal<LocalActorIdentityState>(
    this.#storedPersona.invalid ? 'unavailable' : 'idle',
  );
  readonly #requestGeneration = signal(0);
  #restoration: { actorId: string; promise: Promise<void> } | null = null;

  readonly selectedPersonaId = this.#selectedPersonaId.asReadonly();
  readonly state = this.#state.asReadonly();
  readonly actor = computed(() => this.actorFor(this.#selectedPersonaId()));
  readonly isVerified = computed(() => this.#state() === 'verified' && this.actor() !== null);

  constructor() {
    this.#scopeReset.registerAuthenticationFailureHandler(() => this.invalidate());
  }

  async restore(): Promise<void> {
    const actor = this.actor();
    if (actor === null) {
      return;
    }

    if (this.#restoration === null || this.#restoration.actorId !== actor.id) {
      const restoration = {
        actorId: actor.id,
        promise: this.validateCurrentActor().finally(() => {
          if (this.#restoration === restoration) {
            this.#restoration = null;
          }
        }),
      };
      this.#restoration = restoration;
    }

    await this.#restoration.promise;
  }

  async select(personaId: string): Promise<void> {
    const nextActor = this.actorFor(personaId);
    if (nextActor === null) {
      this.clearSelection();
      return;
    }

    if (nextActor.id !== this.#selectedPersonaId()) {
      this.#requestGeneration.update((value) => value + 1);
      this.#restoration = null;
      this.#scopeReset.reset();
      this.#selectedPersonaId.set(nextActor.id);
      this.#state.set('idle');
      this.#storage.setItem(SELECTED_PERSONA_STORAGE_KEY, nextActor.id);
      await this.#router.navigateByUrl('/');
    }

    await this.restore();
  }

  bearerToken(): string | null {
    return this.actor()?.token ?? null;
  }

  private invalidate(): void {
    this.#requestGeneration.update((value) => value + 1);
    this.#restoration = null;
    this.#scopeReset.reset();
    this.#state.set('unavailable');
  }

  private async validateCurrentActor(): Promise<void> {
    const actor = this.actor();
    if (actor === null) {
      this.#state.set('idle');
      return;
    }

    this.#requestGeneration.update((value) => value + 1);
    const generation = this.#requestGeneration();
    this.#state.set('validating');

    try {
      const result = await firstValueFrom(this.#api.read<WhoAmIResponse>('/dev/whoami'));
      if (generation !== this.#requestGeneration() || actor.id !== this.actor()?.id) {
        return;
      }

      if (!matchesActor(result.body, actor)) {
        this.failClosed();
        return;
      }

      this.#state.set('verified');
    } catch {
      if (generation === this.#requestGeneration()) {
        this.failClosed();
      }
    }
  }

  private failClosed(): void {
    this.#scopeReset.reset();
    this.#state.set('unavailable');
  }

  private clearSelection(): void {
    this.#requestGeneration.update((value) => value + 1);
    this.#restoration = null;
    this.#scopeReset.reset();
    this.#selectedPersonaId.set(null);
    this.#state.set('unavailable');
    this.#storage.removeItem(SELECTED_PERSONA_STORAGE_KEY);
    void this.#router.navigateByUrl('/');
  }

  private restoreStoredPersona(): { personaId: string | null; invalid: boolean } {
    const personaId = this.#storage.getItem(SELECTED_PERSONA_STORAGE_KEY);
    if (personaId === null) {
      return { personaId: null, invalid: false };
    }

    const actor = this.actorFor(personaId);
    if (actor === null) {
      this.#storage.removeItem(SELECTED_PERSONA_STORAGE_KEY);
      return { personaId: null, invalid: true };
    }

    return { personaId: actor.id, invalid: false };
  }

  private actorFor(personaId: string | null): LocalDemoActor | null {
    return this.actors?.find((actor) => actor.id === personaId) ?? null;
  }
}

export function localActorTokenReader(session: LocalActorSession): () => string | null {
  return () => session.bearerToken();
}

export function unavailableMessage(state: LocalActorIdentityState): string | null {
  return state === 'unavailable' ? UNAVAILABLE_MESSAGE : null;
}

function matchesActor(response: WhoAmIResponse | null, actor: LocalDemoActor): boolean {
  if (response === null || response.actorId !== actor.actorId) {
    return false;
  }

  return sameRoles(response.platformRoles, actor.platformRoles);
}

function sameRoles(actual: readonly string[], expected: readonly string[]): boolean {
  return actual.length === expected.length
    && [...actual].sort().every((role, index) => role === [...expected].sort()[index]);
}

import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ApiHttpClient } from '../api/api-http-client';
import { BEARER_TOKEN_READER, bearerTokenInterceptor } from '../api/bearer-token.interceptor';
import { environment as localDemoEnvironment } from '../../environments/environment.local-demo';
import { environment } from '../../environments/environment';
import { ActorScopeResetService } from './actor-scope-reset.service';
import { identityInvalidationInterceptor } from './identity-invalidation.interceptor';
import {
  LOCAL_DEMO_ACTORS,
  LocalActorSession,
  SESSION_STORAGE,
  localActorTokenReader,
  unavailableMessage,
} from './local-actor-session.service';

describe('LocalActorSession', () => {
  let session: LocalActorSession;
  let http: HttpTestingController;
  let storage: MemoryStorage;
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    storage = new MemoryStorage();
    router = { navigateByUrl: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      providers: [
        ApiHttpClient,
        ActorScopeResetService,
        provideHttpClient(withInterceptors([
          bearerTokenInterceptor,
          identityInvalidationInterceptor,
        ])),
        provideHttpClientTesting(),
        { provide: LOCAL_DEMO_ACTORS, useValue: localDemoEnvironment.localDemo },
        { provide: SESSION_STORAGE, useValue: storage },
        { provide: Router, useValue: router },
        {
          provide: BEARER_TOKEN_READER,
          useFactory: localActorTokenReader,
          deps: [LocalActorSession],
        },
      ],
    });

    session = TestBed.inject(LocalActorSession);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('uses every documented local token and validates its expected account and role', async () => {
    const expectedActors = [
      ['ari', 'Ari Organizer', 'arat-local-owner-token', '10000000-0000-4000-8000-000000000001', []],
      ['bea', 'Bea Member', 'arat-local-member-token', '10000000-0000-4000-8000-000000000002', []],
      ['cruz', 'Cruz Outsider', 'arat-local-outsider-token', '10000000-0000-4000-8000-000000000003', []],
      ['dani', 'Dani Provider', 'arat-local-provider-token', '10000000-0000-4000-8000-000000000004', []],
      ['owen', 'Owen Operator', 'arat-local-operator-token', '10000000-0000-4000-8000-000000000005', ['PLATFORM_OPERATOR']],
    ] as const;
    expect(localDemoEnvironment.localDemo).toEqual(expectedActors.map(
      ([id, label, token, actorId, platformRoles]) => ({ id, label, token, actorId, platformRoles }),
    ));

    for (const [id, label, token, actorId, platformRoles] of expectedActors) {
      const selection = session.select(id);
      await Promise.resolve();
      const request = http.expectOne('/api/v1/dev/whoami');
      expect(request.request.headers.get('Authorization')).toBe(`Bearer ${token}`);
      request.flush({ actorId, platformRoles });

      await selection;
      expect(session.isVerified()).toBe(true);
      expect(session.actor()?.label).toBe(label);
    }

    expect(storage.entries()).toEqual([['arat.local-demo.selected-persona', 'owen']]);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('fails closed and clears scoped state when whoami does not match the selected actor', async () => {
    const reset = TestBed.inject(ActorScopeResetService);
    const resetter = vi.fn();
    reset.register(resetter);

    const selection = session.select('ari');
    await Promise.resolve();
    http.expectOne('/api/v1/dev/whoami').flush({
      actorId: '10000000-0000-4000-8000-000000000002',
      platformRoles: [],
    });

    await selection;
    expect(session.isVerified()).toBe(false);
    expect(session.state()).toBe('unavailable');
    expect(resetter).toHaveBeenCalledTimes(2);
  });

  it('fails closed with bounded text when the local probe is unavailable', async () => {
    const selection = session.select('ari');
    await Promise.resolve();
    http.expectOne('/api/v1/dev/whoami').flush(
      { status: 404, title: 'Not found', detail: 'Not found' },
      { status: 404, statusText: 'Not Found' },
    );

    await selection;
    expect(session.state()).toBe('unavailable');
    expect(unavailableMessage(session.state())).toBe('Local development identity is unavailable.');
  });

  it('discards a late verification response when changing actors', async () => {
    const ari = session.select('ari');
    await Promise.resolve();
    const ariRequest = http.expectOne('/api/v1/dev/whoami');

    const bea = session.select('bea');
    await Promise.resolve();
    const beaRequest = http.expectOne('/api/v1/dev/whoami');
    expect(beaRequest.request.headers.get('Authorization')).toBe('Bearer arat-local-member-token');

    ariRequest.flush({
      actorId: '10000000-0000-4000-8000-000000000001',
      platformRoles: [],
    });
    beaRequest.flush({
      actorId: '10000000-0000-4000-8000-000000000002',
      platformRoles: [],
    });

    await Promise.all([ari, bea]);
    expect(session.actor()?.id).toBe('bea');
    expect(session.isVerified()).toBe(true);
  });

  it('invalidates the current actor after an ordinary API 401 response', async () => {
    await verifyActor(session, http, 'ari');

    const read = TestBed.inject(ApiHttpClient).read('/groups').subscribe({ error: () => undefined });
    expect(read.closed).toBe(false);
    http.expectOne('/api/v1/groups').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
    });

    expect(session.isVerified()).toBe(false);
    expect(session.state()).toBe('unavailable');
  });

  it('does not let an old actor 401 invalidate the replacement actor', async () => {
    await verifyActor(session, http, 'ari');

    TestBed.inject(ApiHttpClient).read('/groups').subscribe({ error: () => undefined });
    const ariRead = http.expectOne('/api/v1/groups');
    const bea = session.select('bea');
    await Promise.resolve();
    http.expectOne('/api/v1/dev/whoami').flush({
      actorId: '10000000-0000-4000-8000-000000000002',
      platformRoles: [],
    });
    await bea;

    ariRead.flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(session.actor()?.id).toBe('bea');
    expect(session.isVerified()).toBe(true);
  });

  it('does not accept an unknown local persona and keeps the normal configuration token-free', async () => {
    await session.select('unknown');
    expect(session.selectedPersonaId()).toBeNull();
    expect(session.bearerToken()).toBeNull();
    expect(environment.localDemo).toBeNull();
  });

  it('removes an invalid persisted persona and presents the unavailable state', async () => {
    const invalidStorage = new MemoryStorage();
    invalidStorage.setItem('arat.local-demo.selected-persona', 'unknown');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        ApiHttpClient,
        ActorScopeResetService,
        provideHttpClient(withInterceptors([
          bearerTokenInterceptor,
          identityInvalidationInterceptor,
        ])),
        provideHttpClientTesting(),
        { provide: LOCAL_DEMO_ACTORS, useValue: localDemoEnvironment.localDemo },
        { provide: SESSION_STORAGE, useValue: invalidStorage },
        { provide: Router, useValue: router },
        {
          provide: BEARER_TOKEN_READER,
          useFactory: localActorTokenReader,
          deps: [LocalActorSession],
        },
      ],
    });

    const invalidSession = TestBed.inject(LocalActorSession);
    expect(invalidSession.selectedPersonaId()).toBeNull();
    expect(invalidSession.state()).toBe('unavailable');
    expect(invalidStorage.getItem('arat.local-demo.selected-persona')).toBeNull();
  });
});

async function verifyActor(
  session: LocalActorSession,
  http: HttpTestingController,
  actorId: 'ari' | 'bea',
): Promise<void> {
  const selection = session.select(actorId);
  await Promise.resolve();
  http.expectOne('/api/v1/dev/whoami').flush({
    actorId: actorId === 'ari'
      ? '10000000-0000-4000-8000-000000000001'
      : '10000000-0000-4000-8000-000000000002',
    platformRoles: [],
  });
  await selection;
}

class MemoryStorage implements Storage {
  readonly #values = new Map<string, string>();

  get length(): number {
    return this.#values.size;
  }

  clear(): void {
    this.#values.clear();
  }

  getItem(key: string): string | null {
    return this.#values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.#values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.#values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.#values.set(key, value);
  }

  entries(): readonly (readonly [string, string])[] {
    return [...this.#values.entries()];
  }
}

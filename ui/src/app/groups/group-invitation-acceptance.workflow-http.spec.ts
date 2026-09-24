import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiHttpClient, IDEMPOTENCY_KEY_GENERATOR } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupApi } from './group-api.service';
import { GroupInvitationAcceptanceService } from './group-invitation-acceptance.service';

const TOKEN = 'raw-invitation-token';
const PATH = `/api/v1/group-invites/${TOKEN}/accept`;

describe('group invitation acceptance HTTP workflow', () => {
  let service: GroupInvitationAcceptanceService;
  let http: HttpTestingController;
  let historyPush: ReturnType<typeof vi.spyOn>;
  let historyReplace: ReturnType<typeof vi.spyOn>;
  let consoleError: ReturnType<typeof vi.spyOn>;
  let consoleLog: ReturnType<typeof vi.spyOn>;
  let consoleWarn: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.sessionStorage.clear();
    historyPush = vi.spyOn(globalThis.history, 'pushState');
    historyReplace = vi.spyOn(globalThis.history, 'replaceState');
    consoleError = vi.spyOn(console, 'error');
    consoleLog = vi.spyOn(console, 'log');
    consoleWarn = vi.spyOn(console, 'warn');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiHttpClient,
        GroupApi,
        GroupInvitationAcceptanceService,
        { provide: IDEMPOTENCY_KEY_GENERATOR, useValue: () => 'accept-intent-key' },
      ],
    });
    service = TestBed.inject(GroupInvitationAcceptanceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
  });

  it('accepts an invitation without writing its token to browser state or diagnostics', async () => {
    const accepting = service.accept(TOKEN);
    const request = await waitForRequest(http, PATH);
    expect(request.request.headers.get('Idempotency-Key')).toBe('accept-intent-key');
    request.flush({ groupId: 'group-1', accountId: 'account-1', groupVersion: 2, role: 'MEMBER' });

    await expect(accepting).resolves.toMatchObject({ groupId: 'group-1' });
    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });

  it.each([
    ['already consumed', 404, 'INVITATION_UNAVAILABLE'],
    ['expired', 404, 'INVITATION_UNAVAILABLE'],
    ['revoked', 404, 'INVITATION_UNAVAILABLE'],
    ['wrong account', 404, 'INVITATION_UNAVAILABLE'],
    ['malformed', 400, 'MALFORMED_REQUEST'],
  ])('uses the safe unavailable state for a %s token without leaking it', async (_label, status, code) => {
    const accepting = service.accept(TOKEN);
    const request = await waitForRequest(http, PATH);
    request.flush(problem(code, status), {
      status,
      statusText: 'Request failed',
      headers: { 'Content-Type': 'application/problem+json', 'X-Correlation-Id': 'correlation-1' },
    });

    await expect(accepting).resolves.toBeNull();
    expect(service.state()).toBe('unavailable');
    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });

  it('reuses the exact key after a network retry without retaining the token', async () => {
    const firstAttempt = service.accept(TOKEN);
    const firstRequest = await waitForRequest(http, PATH);
    firstRequest.error(new ProgressEvent('network error'));
    await expect(firstAttempt).resolves.toBeNull();

    const retry = service.accept(TOKEN);
    const retryRequest = await waitForRequest(http, PATH);
    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe('accept-intent-key');
    retryRequest.flush({ groupId: 'group-1', accountId: 'account-1', groupVersion: 2, role: 'MEMBER' });
    await expect(retry).resolves.toMatchObject({ groupId: 'group-1' });

    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });

  it('preserves the retry key after a mistyped repaste so the original token can replay', async () => {
    const firstAttempt = service.accept(TOKEN);
    const firstRequest = await waitForRequest(http, PATH);
    firstRequest.error(new ProgressEvent('network error'));
    await firstAttempt;

    await expect(service.accept('mistyped-token')).resolves.toBeNull();
    http.expectNone('/api/v1/group-invites/mistyped-token/accept');
    expect(service.state()).toBe('retryable');
    expect(service.retryTokenMismatch()).toBe(true);

    const replay = service.accept(TOKEN);
    const replayRequest = await waitForRequest(http, PATH);
    expect(replayRequest.request.headers.get('Idempotency-Key')).toBe('accept-intent-key');
    replayRequest.flush({ groupId: 'group-1', accountId: 'account-1', groupVersion: 2, role: 'MEMBER' });
    await expect(replay).resolves.toMatchObject({ groupId: 'group-1' });
    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });

  it('shows key misuse as a conflict without leaking the token', async () => {
    const accepting = service.accept(TOKEN);
    const request = await waitForRequest(http, PATH);
    request.flush(problem('IDEMPOTENCY_KEY_REUSED', 409), {
      status: 409,
      statusText: 'Conflict',
      headers: { 'Content-Type': 'application/problem+json' },
    });

    await expect(accepting).resolves.toBeNull();
    expect(service.state()).toBe('conflict');
    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });

  it('clears a pending attempt when the actor changes without leaking the token', async () => {
    const accepting = service.accept(TOKEN);
    const request = await waitForRequest(http, PATH);
    TestBed.inject(ActorScopeResetService).reset();
    request.flush({ groupId: 'group-1', accountId: 'account-1', groupVersion: 2, role: 'MEMBER' });

    await expect(accepting).resolves.toBeNull();
    expect(service.state()).toBe('idle');
    expectNoTokenLeak(TOKEN, historyPush, historyReplace, consoleError, consoleLog, consoleWarn);
  });
});

function problem(code: string, status: number) {
  return {
    code,
    status,
    title: 'Request failed',
    detail: 'Safe error message.',
    violations: [],
  };
}

function expectNoTokenLeak(
  token: string,
  historyPush: ReturnType<typeof vi.spyOn>,
  historyReplace: ReturnType<typeof vi.spyOn>,
  consoleError: ReturnType<typeof vi.spyOn>,
  consoleLog: ReturnType<typeof vi.spyOn>,
  consoleWarn: ReturnType<typeof vi.spyOn>,
): void {
  expect(historyPush).not.toHaveBeenCalled();
  expect(historyReplace).not.toHaveBeenCalled();
  expect(JSON.stringify(globalThis.history.state)).not.toContain(token);
  expect(storageContents(globalThis.localStorage)).not.toContain(token);
  expect(storageContents(globalThis.sessionStorage)).not.toContain(token);
  expect(JSON.stringify(consoleError.mock.calls)).not.toContain(token);
  expect(JSON.stringify(consoleLog.mock.calls)).not.toContain(token);
  expect(JSON.stringify(consoleWarn.mock.calls)).not.toContain(token);
}

function storageContents(storage: Storage): string {
  return JSON.stringify(Array.from({ length: storage.length }, (_value, index) => {
    const key = storage.key(index);
    return key === null ? null : [key, storage.getItem(key)];
  }));
}

async function waitForRequest(http: HttpTestingController, url: string): Promise<TestRequest> {
  let request: TestRequest | undefined;
  await vi.waitFor(() => {
    const matches = http.match(url);
    if (matches.length === 1) {
      [request] = matches;
    }
    expect(request).toBeDefined();
  });

  if (request === undefined) {
    throw new Error('Expected invitation acceptance request.');
  }
  return request;
}

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupInvitationAcceptanceComponent } from './group-invitation-acceptance.component';
import { GroupInvitationAcceptanceService } from './group-invitation-acceptance.service';

describe('GroupInvitationAcceptanceComponent', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('uses a labeled secret field and navigates with the returned group representation', async () => {
    const browser = monitorBrowser(TOKEN);
    const acceptance = fakeAcceptance(async () => ({ groupId: 'group-1' }));
    const { fixture, router } = await createComponent(acceptance);
    const root = fixture.nativeElement as HTMLElement;
    const token = root.querySelector<HTMLInputElement>('#invitation-token');
    const form = root.querySelector<HTMLFormElement>('form');
    if (token === null || form === null) {
      throw new Error('Expected invitation acceptance form.');
    }

    expect(root.querySelector<HTMLLabelElement>('label[for="invitation-token"]')?.textContent)
      .toContain('Invitation token');
    expect(token.type).toBe('password');
    token.value = 'raw-invitation-token';
    token.dispatchEvent(new Event('input'));
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(acceptance.accept).toHaveBeenCalledWith('raw-invitation-token');
    expect(router.navigate).toHaveBeenCalledWith(['/groups', 'group-1']);
    expect(fixture.componentInstance.acceptanceForm.controls.token.value).toBe('');
    browser.expectNoLeak();
  });

  it('clears the token after unavailable and retryable outcomes without exposing it in the message', async () => {
    for (const state of ['retryable', 'unavailable'] as const) {
      const browser = monitorBrowser(TOKEN);
      const acceptance = fakeAcceptance(async () => null, state);
      const { fixture, router } = await createComponent(acceptance);
      const root = fixture.nativeElement as HTMLElement;
      const token = root.querySelector<HTMLInputElement>('#invitation-token');
      const form = root.querySelector<HTMLFormElement>('form');
      if (token === null || form === null) {
        throw new Error('Expected invitation acceptance form.');
      }

      token.value = 'raw-invitation-token';
      token.dispatchEvent(new Event('input'));
      form.dispatchEvent(new Event('submit'));
      await fixture.whenStable();

      expect(fixture.componentInstance.acceptanceForm.controls.token.value).toBe('');
      expect(root.textContent).not.toContain('raw-invitation-token');
      expect(router.navigate).not.toHaveBeenCalled();
      browser.expectNoLeak();
      fixture.destroy();
      TestBed.resetTestingModule();
      vi.restoreAllMocks();
    }
  });

  it('presents idempotency key reuse as a conflict with a safe restart action', async () => {
    const browser = monitorBrowser(TOKEN);
    const acceptance = fakeAcceptance(async () => null, 'conflict');
    const { fixture, router } = await createComponent(acceptance);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('conflicts with an earlier request');
    expect(root.textContent).not.toContain('unavailable');
    root.querySelector<HTMLButtonElement>('button[type="button"]')?.click();

    expect(acceptance.clear).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    browser.expectNoLeak();
  });

  it('clears the token when the route exits or the actor changes', async () => {
    const browser = monitorBrowser(TOKEN);
    const acceptance = fakeAcceptance(async () => null);
    const { fixture, router } = await createComponent(acceptance);
    fixture.componentInstance.acceptanceForm.controls.token.setValue('raw-invitation-token');

    TestBed.inject(ActorScopeResetService).reset();
    expect(fixture.componentInstance.acceptanceForm.controls.token.value).toBe('');

    fixture.componentInstance.acceptanceForm.controls.token.setValue('raw-invitation-token');
    fixture.destroy();
    expect(acceptance.clear).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
    browser.expectNoLeak();
  });
});

async function createComponent(acceptance: ReturnType<typeof fakeAcceptance>) {
  const router = { navigate: vi.fn().mockResolvedValue(true) };
  await TestBed.configureTestingModule({
    imports: [GroupInvitationAcceptanceComponent],
    providers: [
      { provide: Router, useValue: router },
      { provide: GroupInvitationAcceptanceService, useValue: acceptance },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(GroupInvitationAcceptanceComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, router };
}

function fakeAcceptance(
  accept: (token: string) => Promise<{ groupId: string } | null>,
  state: 'conflict' | 'idle' | 'retryable' | 'submitting' | 'unavailable' = 'idle',
) {
  return {
    state: signal(state),
    correlationId: signal<string | null>(null),
    retryTokenMismatch: signal(false),
    accept: vi.fn(accept),
    clear: vi.fn(),
  };
}

const TOKEN = 'raw-invitation-token';

function monitorBrowser(token: string) {
  globalThis.localStorage.clear();
  globalThis.sessionStorage.clear();
  const historyPush = vi.spyOn(globalThis.history, 'pushState');
  const historyReplace = vi.spyOn(globalThis.history, 'replaceState');
  const localStorageSet = vi.spyOn(globalThis.localStorage, 'setItem');
  const sessionStorageSet = vi.spyOn(globalThis.sessionStorage, 'setItem');
  const consoleError = vi.spyOn(console, 'error');
  const consoleLog = vi.spyOn(console, 'log');
  const consoleWarn = vi.spyOn(console, 'warn');

  return {
    expectNoLeak: () => {
      expect(historyPush).not.toHaveBeenCalled();
      expect(historyReplace).not.toHaveBeenCalled();
      expect(JSON.stringify(globalThis.history.state)).not.toContain(token);
      expect(localStorageSet).not.toHaveBeenCalled();
      expect(sessionStorageSet).not.toHaveBeenCalled();
      expect(storageContents(globalThis.localStorage)).not.toContain(token);
      expect(storageContents(globalThis.sessionStorage)).not.toContain(token);
      expect(JSON.stringify(consoleError.mock.calls)).not.toContain(token);
      expect(JSON.stringify(consoleLog.mock.calls)).not.toContain(token);
      expect(JSON.stringify(consoleWarn.mock.calls)).not.toContain(token);
    },
  };
}

function storageContents(storage: Storage): string {
  return JSON.stringify(Array.from({ length: storage.length }, (_value, index) => {
    const key = storage.key(index);
    return key === null ? null : [key, storage.getItem(key)];
  }));
}

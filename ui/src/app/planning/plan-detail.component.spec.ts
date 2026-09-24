import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { PlanDetailComponent } from './plan-detail.component';
import { PlanDetailService } from './plan-detail.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';
import { PlanPreferenceService } from './plan-preference.service';
import { PlanFinalizationService } from './plan-finalization.service';
import { PlanPublicationService } from './plan-publication.service';
import { PlanRequestHistoryService } from './plan-request-history.service';
import { PlanRequestClosureService } from './plan-request-closure.service';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';

describe('PlanDetailComponent', () => {
  it.each(['OPEN', 'SUPERSEDED', 'CLOSED', 'CANCELLED'])('labels %s request versions and preserves server current status', async (state) => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    requests.items.set([request(`request-${state}`, 1, state)]);
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, fakeFinalizations(), fakePublications(), requests);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(state === 'OPEN' ? 'Open' : `${state.slice(0, 1)}${state.slice(1).toLowerCase()}`);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Historical version');
  });

  it('shows the server-empty current state and a paged history retry without inferring current from history', async () => {
    const requests = fakeRequests();
    requests.currentState.set('absent');
    requests.items.set([request('request-history', 3, 'CLOSED')]);
    requests.nextCursor.set('older-page');
    requests.historyState.set('error');
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, fakeFinalizations(), fakePublications(), requests);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('No current provider request is available for this plan.');
    expect(root.textContent).toContain('Older provider request history could not be loaded.');
    Array.from(root.querySelectorAll('button')).find((button) => button.textContent?.includes('Retry provider request history'))?.click();
    await fixture.whenStable();

    expect(requests.loadMore).toHaveBeenCalledWith('plan-1');
  });

  it('renders an exact request version loaded from a copied query deep link', async () => {
    const requests = fakeRequests();
    requests.detailRequestId.set('request-1');
    requests.detailState.set('ready');
    requests.detail.set(request('request-1', 1, 'CANCELLED'));
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, fakeFinalizations(), fakePublications(), requests);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Requested version detail');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Version 1 - Historical version');
  });

  it('discards the plan when request history proves access was revoked', async () => {
    const requests = fakeRequests();
    requests.nextCursor.set('older-page');
    requests.loadMore.mockImplementation(async () => { requests.historyProblemCode.set('PRIVATE_RESOURCE_NOT_FOUND'); });
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail, fakeEdit(), fakePreferences(), true, fakeFinalizations(), fakePublications(), requests);
    fixture.componentInstance.loadMoreRequestHistory();
    await fixture.whenStable();

    expect(detail.discardInaccessiblePlan).toHaveBeenCalledWith('plan-1');
  });

  it('does not start an obsolete request read after actor change during finalization loading', async () => {
    let completeFinalizations!: () => void;
    const finalizations = fakeFinalizations();
    finalizations.load.mockImplementation(() => new Promise<void>((resolve) => { completeFinalizations = resolve; }));
    const requests = fakeRequests();
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), false, finalizations, fakePublications(), requests,
    );
    await Promise.resolve();

    TestBed.inject(ActorScopeResetService).reset();
    completeFinalizations();
    await fixture.whenStable();

    expect(requests.load).not.toHaveBeenCalled();
  });

  it('offers closure only for the server-read current OPEN request', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    requests.items.set([request('request-history', 1, 'OPEN')]);
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), requests,
    );
    const root = fixture.nativeElement as HTMLElement;

    expect(Array.from(root.querySelectorAll('.close-request'))).toHaveLength(1);
    expect(root.querySelector('.close-request')?.closest('article')?.textContent)
      .toContain('Current version 2');

    requests.current.set(request('request-current', 2, 'CLOSED'));
    fixture.detectChanges();
    expect(root.querySelector('.close-request')).toBeNull();
  });

  it('focuses explicit close confirmation and restores the trigger on cancel', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), requests,
    );
    const root = fixture.nativeElement as HTMLElement;
    const trigger = root.querySelector<HTMLButtonElement>('.close-request');

    trigger?.focus();
    trigger?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    const confirmation = root.querySelector('[role="alertdialog"]');
    const buttons = confirmation?.querySelectorAll('button');
    expect(confirmation?.textContent).toContain('It does not cancel the plan');
    expect(document.activeElement).toBe(buttons?.[0]);

    buttons?.[1]?.click();
    fixture.detectChanges();
    expect(document.activeElement).toBe(trigger);
    expect(root.querySelector('[role="alertdialog"]')).toBeNull();
  });

  it('submits the reviewed current request once and re-reads authoritative state', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const closures = fakeClosures();
    const closed = closureResult(false);
    closures.close.mockImplementation(async () => {
      closures.result.set(closed);
      closures.state.set('succeeded');
      return closed;
    });
    const detail = fakeDetail('ready', 'OPEN_FOR_OFFERS');
    const fixture = await createComponent(
      detail, fakeEdit(), fakePreferences(), true, fakeFinalizations(),
      fakePublications(), requests, closures,
    );
    const root = fixture.nativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('[role="alertdialog"] .primary-action')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(closures.close).toHaveBeenCalledTimes(1);
    expect(closures.close).toHaveBeenCalledWith('plan-1', 'request-current', '"7"');
    expect(detail.load).toHaveBeenCalledWith('plan-1');
    expect(requests.load).toHaveBeenCalledWith('plan-1');
    expect(root.textContent).toContain('The plan was returned to collaboration; it was not cancelled.');
    expect(document.activeElement).toBe(root.querySelector('.closure-result h3'));
  });

  it('moves focus from confirmation to the in-progress close status', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const closures = fakeClosures();
    closures.close.mockImplementation(async () => {
      closures.state.set('submitting');
      return null;
    });
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), requests, closures,
    );
    const root = fixture.nativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('[role="alertdialog"] .primary-action')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(document.activeElement).toBe(root.querySelector('.closure-progress h3'));
  });

  it('waits for the authoritative success refresh before focusing the result', async () => {
    let finishPlanRefresh!: () => void;
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const closures = fakeClosures();
    const closed = closureResult(false);
    closures.close.mockImplementation(async () => {
      closures.result.set(closed);
      closures.state.set('succeeded');
      return closed;
    });
    const detail = fakeDetail('ready', 'OPEN_FOR_OFFERS');
    const currentPlan = detail.plan();
    const fixture = await createComponent(
      detail, fakeEdit(), fakePreferences(), true, fakeFinalizations(),
      fakePublications(), requests, closures,
    );
    const root = fixture.nativeElement as HTMLElement;
    detail.load.mockClear();
    detail.load.mockImplementation(() => {
      detail.state.set('loading');
      detail.plan.set(null);
      return new Promise<void>((resolve) => {
        finishPlanRefresh = () => {
          detail.plan.set(currentPlan);
          detail.state.set('ready');
          resolve();
        };
      });
    });

    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('[role="alertdialog"] .primary-action')?.click();
    await Promise.resolve();
    fixture.detectChanges();

    expect(detail.state()).toBe('loading');
    expect(root.querySelector('.closure-result')).toBeNull();

    finishPlanRefresh();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(detail.state()).toBe('ready');
    expect(document.activeElement).toBe(root.querySelector('.closure-result h3'));
  });

  it.each([
    ['INVALID_REQUEST_STATE', 'no longer open'],
    ['PRIVATE_RESOURCE_NOT_FOUND', 'plan or provider request is unavailable'],
    ['FORBIDDEN_ROLE', 'current role cannot close'],
    ['IDEMPOTENCY_KEY_REUSED', 'intent key was already used'],
  ])('renders distinct close failure %s without raw server detail', async (problemCode, message) => {
    const closures = fakeClosures();
    closures.state.set('error');
    closures.problemCode.set(problemCode);
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), fakeRequests(), closures,
    );
    const text = (fixture.nativeElement as HTMLElement).textContent;

    expect(text).toContain(message);
    expect(text).not.toContain('Do not render this raw detail');
  });

  it('distinguishes bounded transport retry, stale conflict, and exact replay', async () => {
    const closures = fakeClosures();
    closures.state.set('network-error');
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), fakeRequests(), closures,
    );
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('Retry sends the exact same close command');
    expect(document.activeElement?.textContent).toContain('Retry exact close');

    closures.state.set('conflict');
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('The stale close was not resubmitted.');
    expect(document.activeElement?.textContent).toContain('Refresh closure state');

    closures.state.set('error');
    closures.problemCode.set('INVALID_REQUEST_STATE');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement?.textContent).toContain('Refresh plan and request state');

    closures.result.set(closureResult(true));
    closures.state.set('succeeded');
    fixture.detectChanges();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('exact replay result');
  });

  it('moves focus to the private unavailable heading after a close 404', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const closures = fakeClosures();
    closures.close.mockImplementation(async () => {
      closures.state.set('error');
      closures.problemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
      return null;
    });
    const detail = fakeDetail('ready', 'OPEN_FOR_OFFERS');
    const fixture = await createComponent(
      detail, fakeEdit(), fakePreferences(), true, fakeFinalizations(),
      fakePublications(), requests, closures,
    );
    const root = fixture.nativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('[role="alertdialog"] .primary-action')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(detail.discardInaccessiblePlan).toHaveBeenCalledWith('plan-1');
    expect(document.activeElement).toBe(root.querySelector('#plan-heading'));
  });

  it('clears an open close confirmation when the actor changes', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-current', 2, 'OPEN'));
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), requests,
    );
    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.closureReview()).not.toBeNull();

    TestBed.inject(ActorScopeResetService).reset();
    fixture.detectChanges();

    expect(fixture.componentInstance.closureReview()).toBeNull();
    expect(root.querySelector('[role="alertdialog"]')).toBeNull();
  });

  it('allows a deliberate close intent for a newly published current request', async () => {
    const requests = fakeRequests();
    requests.current.set(request('request-new', 3, 'OPEN'));
    const closures = fakeClosures();
    closures.state.set('succeeded');
    closures.result.set(closureResult(false));
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), fakePublications(), requests, closures,
    );
    const root = fixture.nativeElement as HTMLElement;

    root.querySelector<HTMLButtonElement>('.close-request')?.click();
    fixture.detectChanges();

    expect(closures.dismiss).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.closureReview()?.requestId).toBe('request-new');
  });

  it('renders a private unavailable state without raw problem details', async () => {
    const fixture = await createComponent(fakeDetail('not-found'));

    expect(fixture.nativeElement.textContent).toContain('This plan is unavailable.');
    expect(fixture.nativeElement.textContent).not.toContain('Do not render');
  });

  it.each([
    ['COLLABORATING', 'Collaborating', 'The group is collaborating on this plan.'],
    ['OPEN_FOR_OFFERS', 'Open for provider offers', 'A provider request is open.'],
    ['CANCELLED', 'Cancelled', 'This plan is cancelled.'],
  ])('renders stable safe guidance for %s', async (state, label, guidance) => {
    const fixture = await createComponent(fakeDetail('ready', state));
    const text = (fixture.nativeElement as HTMLElement).textContent;

    expect(text).toContain(`Collaboration state: ${label}`);
    expect(text).toContain(guidance);
    expect(text).toContain('Member action: sharing availability and preferences');
    expect(text).toContain('Organizer-only actions are requirement changes');
  });

  it('renders requirements and a refreshable stale-data indicator without horizontal controls', async () => {
    const fixture = await createComponent(fakeDetail('ready'));
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('BGC');
    expect(root.textContent).toContain('PHP 0.00 to 2500.00');
    expect(root.textContent).toContain('Showing the last server response. Refresh before taking a later action.');
    expect(root.querySelector<HTMLButtonElement>('button')?.textContent).toContain('Refresh plan');
    expect(root.querySelector('.detail-card')).not.toBeNull();
  });

  it('renders a recovered immutable candidate with counts, warnings, and stale history labels', async () => {
    const finalizations = fakeFinalizations();
    const current = finalization('current-1', true);
    finalizations.items.set([current, finalization('stale-1', false)]);
    finalizations.selected.set(current);
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations);
    const finalizationSection = (fixture.nativeElement as HTMLElement).querySelector('.finalization');

    expect(finalizationSection?.textContent).toContain('Immutable recovered finalization');
    expect(finalizationSection?.textContent).toContain('Current preferences2');
    expect(finalizationSection?.textContent).toContain('Stale preferences1');
    expect(finalizationSection?.textContent).toContain('Some preference input was based on an earlier plan version.');
    expect(finalizationSection?.textContent).toContain('Current basis - newest recoverable candidate.');
    expect(finalizationSection?.textContent).toContain('Stale basis - plan requirements changed after this finalization.');
    expect(finalizationSection?.textContent).toContain('this browser does not assume it is publishable');
  });

  it('associates invalid finalization times with the phone-width controls', async () => {
    const fixture = await createComponent(fakeDetail('ready'));
    fixture.componentInstance.finalizationForm.setValue({
      candidateWindowId: 'window-1',
      offerDeadline: '2027-01-09T09:00',
    });
    fixture.componentInstance.submitFinalization();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('#offer-deadline')?.getAttribute('aria-errormessage')).toBe('offer-deadline-error');
    expect(root.querySelector('#offer-deadline-error')?.textContent).toContain('strictly before the selected start');
  });

  it('clears the finalization form and recovered history when the actor changes', async () => {
    const finalizations = fakeFinalizations();
    finalizations.items.set([finalization('current-1', true)]);
    finalizations.selected.set(finalization('current-1', true));
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations);
    fixture.componentInstance.finalizationForm.controls.offerDeadline.setValue('2027-01-08T10:00');

    TestBed.inject(ActorScopeResetService).reset();

    expect(fixture.componentInstance.finalizationForm.controls.offerDeadline.value).toBe('');
  });

  it('does not start a stale finalization read after the actor changes during preference loading', async () => {
    let completeLoad!: () => void;
    const preferences = fakePreferences();
    preferences.load.mockImplementation(() => new Promise<void>((resolve) => { completeLoad = resolve; }));
    const finalizations = fakeFinalizations();
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), preferences, false, finalizations);
    await Promise.resolve();

    TestBed.inject(ActorScopeResetService).reset();
    completeLoad();
    await fixture.whenStable();

    expect(finalizations.load).not.toHaveBeenCalled();
  });

  it('discards the plan when a finalization write proves access was revoked', async () => {
    const finalizations = fakeFinalizations();
    finalizations.finalize.mockImplementation(async () => {
      finalizations.problemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
      finalizations.saveState.set('error');
      return null;
    });
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail, fakeEdit(), fakePreferences(), true, finalizations);
    fixture.componentInstance.finalizationForm.setValue({
      candidateWindowId: 'window-1',
      offerDeadline: '2027-01-08T10:00',
    });

    fixture.componentInstance.submitFinalization();
    await fixture.whenStable();

    expect(detail.discardInaccessiblePlan).toHaveBeenCalledWith('plan-1');
  });

  it('discards the plan when an older-history page proves access was revoked', async () => {
    const finalizations = fakeFinalizations();
    finalizations.nextCursor.set('older-page');
    finalizations.loadMore.mockImplementation(async () => {
      finalizations.historyProblemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
    });
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail, fakeEdit(), fakePreferences(), true, finalizations);

    fixture.componentInstance.loadMoreFinalizations();
    await fixture.whenStable();

    expect(detail.discardInaccessiblePlan).toHaveBeenCalledWith('plan-1');
  });

  it('keeps an exact command result visible while exposing a failed fresh history read', async () => {
    const finalizations = fakeFinalizations();
    finalizations.commandResult.set(finalization('replayed-old', true));
    finalizations.historyState.set('error');
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations);
    const section = (fixture.nativeElement as HTMLElement).querySelector('.finalization');

    expect(section?.textContent).toContain('Immutable finalization command result');
    expect(section?.textContent).toContain('Finalization history could not be loaded');
    const retry = Array.from(section?.querySelectorAll('button') ?? [])
      .find((button) => button.textContent?.includes('Retry finalization history'));
    retry?.click();
    await fixture.whenStable();

    expect(finalizations.refreshHistory).toHaveBeenCalledWith('plan-1');
  });

  it('exposes a failed older page without hiding retained history and retries that page', async () => {
    const finalizations = fakeFinalizations();
    const current = finalization('current-1', true);
    finalizations.items.set([current]);
    finalizations.selected.set(current);
    finalizations.nextCursor.set('older-page');
    finalizations.historyState.set('error');
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations);
    const section = (fixture.nativeElement as HTMLElement).querySelector('.finalization');

    expect(section?.textContent).toContain('Current basis - newest recoverable candidate.');
    expect(section?.textContent).toContain('Older finalization history could not be loaded.');
    const retry = Array.from(section?.querySelectorAll('button') ?? [])
      .find((button) => button.textContent?.includes('Retry finalization history'));
    retry?.click();
    await fixture.whenStable();

    expect(finalizations.loadMore).toHaveBeenCalledWith('plan-1');
  });

  it('renders the private group preference summary as advisory and marks stale input', async () => {
    const preferences = fakePreferences();
    preferences.summary.set({
      items: [{
        current: false,
        preference: {
          accountId: 'member-2', attendance: 'JOINING', guestCount: 1,
          selectedWindowIds: ['window-1'], basisPlanVersion: 6, version: 2,
          personalBudget: { currency: 'PHP', amount: '500.00' },
          rankedPreferences: ['parking'], privateNote: 'Do not show this in the summary.',
        },
      }],
    });
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), preferences);
    const text = (fixture.nativeElement as HTMLElement).textContent;
    const summaryText = (fixture.nativeElement as HTMLElement).querySelector('.preference-summary')?.textContent;

    expect(text).toContain('These are advisory member inputs for this private group.');
    expect(text).toContain('Member member-2');
    expect(text).toContain('Stale preference: based on an earlier plan version.');
    expect(summaryText).not.toContain('Do not show this in the summary.');
    expect(summaryText).not.toContain('500.00');
  });

  it('clears an in-memory preference form when the local actor changes', async () => {
    const fixture = await createComponent(fakeDetail('ready'));
    fixture.componentInstance.preferenceForm.patchValue({ privateNote: 'Ari private note' });

    TestBed.inject(ActorScopeResetService).reset();

    expect(fixture.componentInstance.preferenceForm.controls.privateNote.value).toBe('');
  });

  it('hydrates an existing preference after its initial read recovers', async () => {
    const preferences = fakePreferences();
    preferences.ownState.set('error');
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), preferences);
    preferences.ownState.set('ready');
    preferences.ownPreference.set({
      attendance: 'JOINING', guestCount: 1, selectedWindowIds: ['window-1'],
      personalBudget: { currency: 'PHP', amount: '500.00' }, rankedPreferences: ['parking'], privateNote: 'Recovered note',
    });

    fixture.componentInstance.refreshPreferenceConflict();
    await fixture.whenStable();

    expect(fixture.componentInstance.preferenceForm.controls.privateNote.value).toBe('Recovered note');
    expect(fixture.componentInstance.preferenceForm.controls.personalBudgetAmount.value).toBe('500.00');
  });

  it('shows a retired selected window so the member can deliberately remove it', async () => {
    const fixture = await createComponent(fakeDetail('ready'));
    fixture.componentInstance.preferenceForm.controls.selectedWindowIds.setValue(['retired-window']);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('A previously selected window is no longer active.');
    fixture.componentInstance.togglePreferenceWindow('retired-window', false);
    expect(fixture.componentInstance.preferenceForm.controls.selectedWindowIds.value).toEqual([]);
  });

  it('renders safe server validation errors next to fields and in the fallback summary', async () => {
    const preferences = fakePreferences();
    preferences.saveState.set('error');
    preferences.problemCode.set('VALIDATION_FAILED');
    preferences.violations.set([
      { field: 'guestCount', message: 'Guest count is invalid.' },
      { field: 'unexpected', message: 'Review this server-only field.' },
    ]);
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), preferences);
    fixture.componentInstance.preferenceFormReady.set(true);
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Your preference needs the highlighted corrections.');
    expect(root.querySelector('#preference-guests-server-errors')?.textContent).toContain('Guest count is invalid.');
    expect(root.querySelector('#preference-guests')?.getAttribute('aria-errormessage')).toBe('preference-guests-server-errors');
    expect(root.querySelector('.validation-summary')?.textContent).toContain('Review this server-only field.');
  });

  it('keeps the form unavailable until both preference reads finish and fields hydrate', async () => {
    let completeLoad!: () => void;
    const preferences = fakePreferences();
    preferences.ownState.set('ready');
    preferences.ownPreference.set({
      attendance: 'JOINING', guestCount: 2, selectedWindowIds: ['window-1'],
      rankedPreferences: ['parking'], privateNote: 'Stored note',
    });
    preferences.summaryState.set('loading');
    preferences.load.mockImplementation(() => new Promise<void>((resolve) => { completeLoad = resolve; }));
    const fixture = await createComponent(fakeDetail('ready'), fakeEdit(), preferences, false);
    await Promise.resolve();
    fixture.detectChanges();

    expect(fixture.componentInstance.preferenceFormReady()).toBe(false);
    expect((fixture.nativeElement as HTMLElement).querySelector('.preference-editor form')).toBeNull();

    completeLoad();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.preferenceFormReady()).toBe(true);
    expect(fixture.componentInstance.preferenceForm.controls.attendance.value).toBe('JOINING');
    expect(fixture.componentInstance.preferenceForm.controls.privateNote.value).toBe('Stored note');
  });

  it('loads the plan on deep-link initialization and refreshes the same canonical resource', async () => {
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail);

    expect(detail.load).toHaveBeenCalledWith('plan-1');
    fixture.componentInstance.refresh();
    expect(detail.load).toHaveBeenLastCalledWith('plan-1');
  });

  it('hydrates the labeled editor in plan-zone wall time and submits stable window IDs', async () => {
    const edit = fakeEdit();
    const fixture = await createComponent(fakeDetail('ready'), edit);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('label[for="plan-title"]')?.textContent).toContain('Plan title');
    expect(root.querySelector<HTMLInputElement>('#plan-title')?.value).toBe('Friday badminton');
    expect(root.querySelector<HTMLInputElement>('#window-start-0')?.value).toBe('2027-01-09T09:00');
    expect(root.querySelector<HTMLInputElement>('#plan-budget-minimum')?.value).toBe('0.00');

    root.querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();

    expect(edit.replace).toHaveBeenCalledWith('plan-1', expect.objectContaining({
      title: 'Friday badminton',
      budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
      candidateWindows: [{
        id: 'window-1',
        startAt: '2027-01-09T01:00:00Z',
        endAt: '2027-01-09T03:00:00Z',
      }],
    }), '"7"');
  });

  it('submits an untouched valid window whose local times reverse across a DST fold', async () => {
    const edit = fakeEdit();
    const detail = fakeDetail('ready');
    detail.plan.update((plan) => plan === null ? null : ({
      ...plan,
      requirements: {
        ...plan.requirements,
        timeZone: 'America/New_York',
        candidateWindows: [{
          id: 'window-fold',
          startAt: '2027-11-07T05:45:12.123456Z',
          endAt: '2027-11-07T06:15:34.654321Z',
        }],
      },
    }));
    const fixture = await createComponent(detail, edit);

    expect(fixture.componentInstance.editForm.valid).toBe(true);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();

    expect(edit.replace).toHaveBeenCalledWith('plan-1', expect.objectContaining({
      candidateWindows: [{
        id: 'window-fold',
        startAt: '2027-11-07T05:45:12.123456Z',
        endAt: '2027-11-07T06:15:34.654321Z',
      }],
    }), '"7"');
  });

  it('refreshes a stale version without resubmitting and requires deliberate reapply', async () => {
    const edit = fakeEdit('conflict');
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail, edit);
    const root = fixture.nativeElement as HTMLElement;

    Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Refresh current plan version'))?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(detail.load).toHaveBeenCalledTimes(2);
    expect(edit.markRefreshed).toHaveBeenCalledWith('"7"');
    expect(edit.reapply).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Your unsaved fields have not been submitted or merged.');

    Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Reapply my changes'))?.click();
    await fixture.whenStable();
    expect(edit.reapply).toHaveBeenCalledOnce();
  });

  it('preserves a conflict draft when the authoritative refresh needs a retry', async () => {
    const edit = fakeEdit('conflict');
    const detail = fakeDetail('ready');
    const serverPlan = detail.plan();
    const fixture = await createComponent(detail, edit);
    fixture.componentInstance.editForm.controls.title.setValue('My unsaved edit');
    detail.load.mockImplementationOnce(async () => {
      detail.state.set('error');
      detail.plan.set(null);
      detail.etag.set(null);
    });

    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Refresh current plan version'))?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.editForm.controls.title.value).toBe('My unsaved edit');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Your unsaved requirement fields are still preserved.');
    expect(edit.markRefreshed).not.toHaveBeenCalled();

    detail.load.mockImplementationOnce(async () => {
      detail.state.set('ready');
      detail.plan.set(serverPlan);
      detail.etag.set('"8"');
    });
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Retry current plan refresh'))?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.componentInstance.editForm.controls.title.value).toBe('My unsaved edit');
    expect(edit.markRefreshed).toHaveBeenCalledWith('"8"');
  });

  it('clears private plan and form data when replacement proves access was revoked', async () => {
    const edit = fakeEdit();
    edit.replace.mockImplementation(async () => {
      edit.problemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
      edit.state.set('error');
      return null;
    });
    const detail = fakeDetail('ready');
    const fixture = await createComponent(detail, edit);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(detail.discardInaccessiblePlan).toHaveBeenCalledWith('plan-1');
    expect(detail.plan()).toBeNull();
    expect(detail.etag()).toBeNull();
    expect(fixture.componentInstance.editForm.controls.title.value).toBe('');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Friday badminton');
  });

  it.each([
    ['FORBIDDEN_ROLE', 'Your current role cannot change these requirements.'],
    ['INVALID_PLAN_STATE', 'The plan no longer allows requirement changes.'],
    ['VALIDATION_FAILED', 'The requirements need the highlighted corrections.'],
  ])('presents safe requirement outcome %s', async (problemCode, message) => {
    const edit = fakeEdit('error');
    edit.problemCode.set(problemCode);
    edit.violations.set(problemCode === 'VALIDATION_FAILED'
      ? [{ field: 'title', message: 'Title is invalid.' }]
      : []);

    const fixture = await createComponent(fakeDetail('ready'), edit);

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(message);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Do not render');
  });

  it('requires an explicit review naming the current finalization before publication', async () => {
    const finalizations = fakeFinalizations();
    const current = finalization('finalization-1', true);
    finalizations.items.set([current]);
    finalizations.selected.set(current);
    finalizations.recoverable.set(current);
    const publications = fakePublications();
    publications.publish.mockResolvedValue(publicationResult('initial'));
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations, publications,
    );
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Review publication of finalization finalization-1');
    root.querySelector<HTMLButtonElement>('.review-publication')?.click();
    fixture.detectChanges();

    expect(root.textContent).toContain('Review finalization finalization-1 before publication');
    expect(root.textContent).toContain('This will publish an immutable request');
    Array.from(root.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Confirm and publish request'))?.click();
    await fixture.whenStable();

    expect(publications.publish).toHaveBeenCalledWith(
      'plan-1', 'finalization-1', '"7"', 'COLLABORATING',
    );
  });

  it('renders a replacement result and response metadata without recipient identity data', async () => {
    const publications = fakePublications();
    publications.state.set('succeeded');
    const responseWithForbiddenFields = publicationResult('replacement');
    const unsafeResponse = responseWithForbiddenFields.request as Record<string, unknown>;
    unsafeResponse['providerIds'] = ['provider-secret'];
    unsafeResponse['providerName'] = 'Secret Provider Name';
    publications.result.set(responseWithForbiddenFields);
    const fixture = await createComponent(
      fakeDetail('ready', 'OPEN_FOR_OFFERS'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), publications,
    );
    const publication = (fixture.nativeElement as HTMLElement).querySelector('.publication');

    expect(publication?.textContent).toContain('Replacement provider request published');
    expect(publication?.textContent).toContain('previously open request was superseded atomically');
    expect(publication?.textContent).toContain('Request version2');
    expect(publication?.textContent).toContain('Original command lifecycle stateOpen');
    expect(publication?.textContent).toContain('Original response Location/api/v1/plans/plan-1/published-requests/request-2');
    expect(publication?.textContent).toContain('Recipient identities are not included');
    expect(publication?.textContent).not.toContain('provider-secret');
    expect(publication?.textContent).not.toContain('Secret Provider Name');
  });

  it('labels an exact replay OPEN result as historical beside a terminal current plan', async () => {
    const publications = fakePublications();
    const replay = publicationResult('initial');
    replay.exactRetry = true;
    publications.state.set('succeeded');
    publications.result.set(replay);
    const fixture = await createComponent(
      fakeDetail('ready', 'CANCELLED'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), publications,
    );
    const text = (fixture.nativeElement as HTMLElement).textContent;

    expect(text).toContain('Collaboration state: Cancelled');
    expect(text).toContain('Original command lifecycle stateOpen');
    expect(text).toContain('Original command actionableYes');
    expect(text).toContain('does not establish the request\'s current lifecycle or actionability');
    expect(text).toContain('retrying the exact same publication intent');
  });

  it('prevents finalization and publication commands from overlapping', async () => {
    const publications = fakePublications();
    publications.state.set('submitting');
    const finalizations = fakeFinalizations();
    const current = finalization('finalization-1', true);
    finalizations.recoverable.set(current);
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations, publications,
    );
    fixture.componentInstance.finalizationForm.setValue({
      candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00',
    });

    fixture.componentInstance.submitFinalization();
    fixture.componentInstance.reviewPublication();

    expect(finalizations.finalize).not.toHaveBeenCalled();
    expect(fixture.componentInstance.publicationReview()).toBeNull();
    expect(publications.dismiss).not.toHaveBeenCalled();

    publications.state.set('idle');
    finalizations.saveState.set('submitting');
    fixture.componentInstance.reviewPublication();

    expect(fixture.componentInstance.publicationReview()).toBeNull();
  });

  it('keeps an ambiguous publication retry key until stop-and-refresh is chosen', async () => {
    const finalizations = fakeFinalizations();
    const current = finalization('finalization-1', true);
    finalizations.recoverable.set(current);
    const publications = fakePublications();
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations, publications,
    );
    fixture.componentInstance.reviewPublication();
    publications.dismiss.mockClear();
    publications.state.set('network-error');
    fixture.detectChanges();

    const cancel = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Cancel publication'));
    expect(cancel?.disabled).toBe(true);

    fixture.componentInstance.cancelPublicationReview();

    expect(fixture.componentInstance.publicationReview()?.finalizationId).toBe('finalization-1');
    expect(publications.dismiss).not.toHaveBeenCalled();
  });

  it.each([
    ['NO_ELIGIBLE_PROVIDERS', 'No eligible providers matched'],
    ['RECIPIENT_LIMIT_EXCEEDED', 'exceeds the server recipient cap'],
    ['REQUEST_DEADLINE_EXPIRED', 'offer deadline has elapsed'],
    ['INVALID_PLAN_STATE', 'plan state no longer allows publication'],
  ])('renders distinct publication failure %s', async (problemCode, message) => {
    const publications = fakePublications();
    publications.state.set('error');
    publications.problemCode.set(problemCode);
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), true,
      fakeFinalizations(), publications,
    );

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(message);
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Do not render');
  });

  it('refreshes plan and finalization state after a stale publication conflict', async () => {
    const publications = fakePublications();
    publications.state.set('conflict');
    publications.problemCode.set('FINALIZATION_VERSION_CHANGED');
    const detail = fakeDetail('ready');
    const finalizations = fakeFinalizations();
    const fixture = await createComponent(
      detail, fakeEdit(), fakePreferences(), true, finalizations, publications,
    );

    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Refresh publication state'))?.click();
    await fixture.whenStable();

    expect(detail.load).toHaveBeenCalledTimes(2);
    expect(finalizations.load).toHaveBeenCalledTimes(2);
    expect(publications.dismiss).toHaveBeenCalledOnce();
  });

  it('clears an open publication review when the actor changes', async () => {
    const finalizations = fakeFinalizations();
    const current = finalization('finalization-1', true);
    finalizations.recoverable.set(current);
    const fixture = await createComponent(
      fakeDetail('ready'), fakeEdit(), fakePreferences(), true, finalizations,
    );
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('.review-publication')?.click();

    expect(fixture.componentInstance.publicationReview()?.finalizationId).toBe('finalization-1');
    TestBed.inject(ActorScopeResetService).reset();

    expect(fixture.componentInstance.publicationReview()).toBeNull();
  });
});

async function createComponent(
  detail: ReturnType<typeof fakeDetail>,
  edit = fakeEdit(),
  preferences = fakePreferences(),
  awaitStable = true,
  finalizations = fakeFinalizations(),
  publications = fakePublications(),
  requests = fakeRequests(),
  closures = fakeClosures(),
) {
  await TestBed.configureTestingModule({
    imports: [PlanDetailComponent],
    providers: [
      provideRouter([{ path: 'plans/:planId', component: PlanDetailComponent }]),
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ planId: 'plan-1' })), queryParamMap: of(convertToParamMap({})), snapshot: { paramMap: convertToParamMap({ planId: 'plan-1' }) } } },
      { provide: PlanDetailService, useValue: detail },
      { provide: PlanRequirementEditService, useValue: edit },
      { provide: PlanPreferenceService, useValue: preferences },
      { provide: PlanFinalizationService, useValue: finalizations },
      { provide: PlanPublicationService, useValue: publications },
      { provide: PlanRequestHistoryService, useValue: requests },
      { provide: PlanRequestClosureService, useValue: closures },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(PlanDetailComponent);
  fixture.detectChanges();
  if (awaitStable) {
    await fixture.whenStable();
  }
  fixture.detectChanges();
  return fixture;
}

function fakePublications() {
  const state = signal<'conflict' | 'error' | 'idle' | 'network-error' | 'submitting' | 'succeeded'>('idle');
  const problemCode = signal<string | null>(null);
  const result = signal<ReturnType<typeof publicationResult> | null>(null);
  return {
    state,
    problemCode,
    correlationId: signal<string | null>(null),
    result,
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    publish: vi.fn(async (): Promise<ReturnType<typeof publicationResult> | null> => null),
    retry: vi.fn(async (): Promise<ReturnType<typeof publicationResult> | null> => null),
    dismiss: vi.fn(() => {
      state.set('idle');
      problemCode.set(null);
      result.set(null);
    }),
  };
}

function fakeRequests() {
  return {
    current: signal<Record<string, unknown> | null>(null),
    currentState: signal<'absent' | 'error' | 'loading' | 'ready'>('ready'),
    items: signal<readonly Record<string, unknown>[]>([]),
    historyState: signal<'absent' | 'error' | 'loading' | 'ready'>('ready'),
    historyProblemCode: signal<string | null>(null),
    nextCursor: signal<string | null>(null),
    detail: signal<Record<string, unknown> | null>(null),
    detailRequestId: signal<string | null>(null),
    detailState: signal<'absent' | 'error' | 'loading' | 'ready'>('absent'),
    detailProblemCode: signal<string | null>(null),
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    load: vi.fn().mockResolvedValue(undefined),
    refreshHistory: vi.fn().mockResolvedValue(undefined),
    loadMore: vi.fn().mockResolvedValue(undefined),
    readDetail: vi.fn().mockResolvedValue(undefined),
  };
}

function fakeClosures() {
  const state = signal<'conflict' | 'error' | 'idle' | 'network-error' | 'submitting' | 'succeeded'>('idle');
  const problemCode = signal<string | null>(null);
  const result = signal<ReturnType<typeof closureResult> | null>(null);
  return {
    state,
    problemCode,
    correlationId: signal<string | null>(null),
    result,
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    close: vi.fn(async (): Promise<ReturnType<typeof closureResult> | null> => null),
    retry: vi.fn(async (): Promise<ReturnType<typeof closureResult> | null> => null),
    dismiss: vi.fn(() => {
      state.set('idle');
      problemCode.set(null);
      result.set(null);
    }),
  };
}

function request(requestId: string, requestVersion: number, state: string) {
  return {
    requestId, requestVersion, state, actionable: state === 'OPEN', timeZone: 'Asia/Manila',
    publishedAt: '2027-01-08T01:00:00Z', offerDeadline: '2027-01-08T02:00:00Z',
    requestedWindow: { startAt: '2027-01-09T01:00:00Z', endAt: '2027-01-09T03:00:00Z' },
    area: { code: 'BGC', radiusKm: 5 }, headcount: { minimum: 4, maximum: 10 },
    mustHaves: ['parking'], categoryAttributes: {},
  };
}

function fakeFinalizations() {
  const saveState = signal<'conflict' | 'error' | 'idle' | 'network-error' | 'submitting'>('idle');
  return {
    items: signal<readonly Record<string, unknown>[]>([]),
    nextCursor: signal<string | null>(null),
    historyState: signal<'error' | 'loading' | 'ready'>('ready'),
    historyProblemCode: signal<string | null>(null),
    saveState,
    problemCode: signal<string | null>(null),
    correlationId: signal<string | null>(null),
    violations: signal<readonly { field: string; message: string }[]>([]),
    selected: signal<Record<string, unknown> | null>(null),
    commandResult: signal<Record<string, unknown> | null>(null),
    recoverable: signal<Record<string, unknown> | null>(null),
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    load: vi.fn().mockResolvedValue(undefined),
    refreshHistory: vi.fn().mockResolvedValue(undefined),
    loadMore: vi.fn().mockResolvedValue(undefined),
    finalize: vi.fn().mockResolvedValue(null),
    retry: vi.fn().mockResolvedValue(null),
    dismiss: vi.fn(() => saveState.set('idle')),
  };
}

function fakePreferences() {
  return {
    ownState: signal<'absent' | 'error' | 'loading' | 'ready'>('absent'),
    ownPreference: signal<object | null>(null),
    preferenceEtag: signal<string | null>(null),
    summaryState: signal<'absent' | 'error' | 'loading' | 'ready'>('ready'),
    summary: signal<{ items: object[] }>({ items: [] }),
    summaryProblemCode: signal<string | null>(null),
    saveState: signal<'conflict' | 'error' | 'idle' | 'submitting'>('idle'),
    problemCode: signal<string | null>(null),
    correlationId: signal<string | null>(null),
    violations: signal<readonly { field: string; message: string }[]>([]),
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    load: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue(null),
    dismiss: vi.fn(),
  };
}

function fakeDetail(state: 'error' | 'loading' | 'not-found' | 'ready', planState = 'COLLABORATING') {
  const stateSignal = signal(state);
  const etag = signal<string | null>(state === 'ready' ? '"7"' : null);
  const plan = signal(state === 'ready' ? {
    planId: 'plan-1', title: 'Friday badminton', state: planState, version: 7,
    requirements: {
      category: 'COURT', timeZone: 'Asia/Manila', area: { code: 'BGC', radiusKm: 5 },
      headcount: { minimum: 4, maximum: 10 }, budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
      candidateWindows: [{ id: 'window-1', startAt: '2027-01-09T01:00:00Z', endAt: '2027-01-09T03:00:00Z' }],
      mustHaves: ['parking'], categoryAttributes: { hasParking: true },
    },
  } : null);
  return {
    state: stateSignal,
    etag,
    correlationId: signal<string | null>(null),
    load: vi.fn().mockResolvedValue(undefined),
    plan,
    discardInaccessiblePlan: vi.fn(() => {
      stateSignal.set('not-found');
      plan.set(null);
      etag.set(null);
    }),
  };
}

function fakeEdit(initialState: 'conflict' | 'error' | 'idle' | 'network-error' | 'reapply-ready' | 'submitting' = 'idle') {
  const state = signal(initialState);
  return {
    state,
    problemCode: signal<string | null>(null),
    correlationId: signal<string | null>(null),
    violations: signal<readonly { field: string; message: string }[]>([]),
    usePlan: vi.fn(),
    releasePlan: vi.fn(),
    replace: vi.fn().mockResolvedValue(null),
    retry: vi.fn().mockResolvedValue(null),
    markRefreshed: vi.fn(() => state.set('reapply-ready')),
    reapply: vi.fn().mockResolvedValue(null),
    dismiss: vi.fn(() => state.set('idle')),
  };
}

function finalization(finalizationId: string, currentBasis: boolean) {
  return {
    finalizationId, currentBasis, basisPlanVersion: currentBasis ? 7 : 6,
    selectedStartAt: '2027-01-09T01:00:00Z', selectedEndAt: '2027-01-09T03:00:00Z',
    offerDeadline: '2027-01-08T02:00:00Z', timeZone: 'Asia/Manila', category: 'COURT',
    area: { code: 'BGC', radiusKm: 5 }, headcount: { minimum: 4, maximum: 10 },
    currentPreferenceCount: 2, stalePreferenceCount: 1, warnings: ['STALE_PREFERENCE_INPUT_PRESENT'],
  };
}

function publicationResult(outcome: 'initial' | 'replacement' | 'versioned') {
  const requestVersion = outcome === 'initial' ? 1 : 2;
  return {
    etag: '"8"',
    exactRetry: false,
    location: `/api/v1/plans/plan-1/published-requests/request-${requestVersion}`,
    outcome,
    request: {
      requestId: `request-${requestVersion}`,
      requestVersion,
      publishedAt: '2027-01-08T01:00:00Z',
      state: 'OPEN',
      actionable: true,
      category: 'COURT',
      timeZone: 'Asia/Manila',
      area: { code: 'BGC', radiusKm: 5 },
      requestedWindow: {
        startAt: '2027-01-09T01:00:00Z',
        endAt: '2027-01-09T03:00:00Z',
      },
      headcount: { minimum: 4, maximum: 10 },
      mustHaves: ['parking'],
      categoryAttributes: { hasParking: true },
      offerDeadline: '2027-01-08T02:00:00Z',
    },
  };
}

function closureResult(exactRetry: boolean) {
  return {
    etag: '"8"',
    exactRetry,
    request: {
      ...request('request-current', 2, 'CLOSED'),
      category: 'COURT',
    },
  };
}

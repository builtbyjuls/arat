import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { firstValueFrom } from 'rxjs';
import { ApiHttpClient } from '../api/api-http-client';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreatePreferenceRequest, PlanApi } from './plan-api.service';
import { PlanPreferenceService } from './plan-preference.service';

describe('plan preference HTTP workflow', () => {
  let api: PlanApi;
  let preferences: PlanPreferenceService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), ApiHttpClient, PlanApi, PlanPreferenceService,
    ] });
    api = TestBed.inject(PlanApi);
    preferences = TestBed.inject(PlanPreferenceService);
    preferences.usePlan('plan id');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lets one simultaneous first write win and leaves the stale writer visible', async () => {
    const first = firstValueFrom(api.putPreference('plan id', request(), null));
    const second = firstValueFrom(api.putPreference('plan id', request(), null));
    const writes = http.match('/api/v1/plans/plan%20id/members/me/preference');
    expect(writes).toHaveLength(2);
    writes.forEach((write) => {
      expect(write.request.headers.get('If-None-Match')).toBe('*');
      expect(write.request.headers.has('If-Match')).toBe(false);
    });
    writes[0].flush(preference(), { status: 201, statusText: 'Created', headers: { ETag: '"1"' } });
    writes[1].flush(problem('PRECONDITION_FAILED', 412), {
      status: 412, statusText: 'Precondition Failed', headers: { 'Content-Type': 'application/problem+json' },
    });

    await expect(first).resolves.toMatchObject({ status: 201, etag: '"1"' });
    await expect(second).rejects.toMatchObject({ status: 412 });
  });

  it('treats a missing own preference as an editable absence without failing the plan summary', async () => {
    const loading = preferences.load('plan id');
    http.expectOne('/api/v1/plans/plan%20id/members/me/preference').flush(
      problem('PREFERENCE_NOT_FOUND', 404), {
        status: 404, statusText: 'Not Found', headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    http.expectOne('/api/v1/plans/plan%20id/preferences').flush({ items: [] });
    await loading;

    expect(preferences.ownState()).toBe('absent');
    expect(preferences.summaryState()).toBe('ready');
    expect(preferences.preferenceEtag()).toBeNull();
  });

  it('uses only the loaded preference ETag for a replacement and retains it after a stale response', async () => {
    await loadExisting('"3"');
    const save = preferences.save('plan id', request());
    const write = http.expectOne('/api/v1/plans/plan%20id/members/me/preference');
    expect(write.request.headers.get('If-Match')).toBe('"3"');
    expect(write.request.headers.has('If-None-Match')).toBe(false);
    write.flush(problem('PRECONDITION_FAILED', 412), {
      status: 412, statusText: 'Precondition Failed', headers: { 'Content-Type': 'application/problem+json' },
    });
    await save;

    expect(preferences.saveState()).toBe('conflict');
    expect(preferences.preferenceEtag()).toBe('"3"');
  });

  it('shows a stale plan basis without a hidden retry', async () => {
    await loadExisting('"3"');
    const save = preferences.save('plan id', request());
    http.expectOne('/api/v1/plans/plan%20id/members/me/preference').flush(
      problem('REQUIREMENT_VERSION_CHANGED', 409), {
        status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json' },
      },
    );
    await save;

    expect(preferences.saveState()).toBe('conflict');
    expect(preferences.problemCode()).toBe('REQUIREMENT_VERSION_CHANGED');
    http.expectNone('/api/v1/plans/plan%20id/members/me/preference');
  });

  it('clears an old ETag before refresh and adopts only the refreshed server ETag', async () => {
    await loadExisting('"3"');
    const refresh = preferences.load('plan id');
    expect(preferences.preferenceEtag()).toBeNull();
    const own = http.expectOne('/api/v1/plans/plan%20id/members/me/preference');
    const summary = http.expectOne('/api/v1/plans/plan%20id/preferences');
    own.flush(preference({ version: 4 }), { headers: { ETag: '"4"' } });
    summary.flush({ items: [] });
    await refresh;

    expect(preferences.preferenceEtag()).toBe('"4"');
  });

  it('completes a write when a refresh overlaps it and refreshes the summary afterward', async () => {
    await loadExisting('"3"');
    const save = preferences.save('plan id', request());
    const write = http.expectOne('/api/v1/plans/plan%20id/members/me/preference');

    const refresh = preferences.load('plan id');
    http.expectOne('/api/v1/plans/plan%20id/members/me/preference').flush(preference({ version: 4 }), { headers: { ETag: '"4"' } });
    http.expectOne('/api/v1/plans/plan%20id/preferences').flush({ items: [] });
    await refresh;

    write.flush(preference({ version: 5 }), { headers: { ETag: '"5"' } });
    await Promise.resolve();
    http.expectOne('/api/v1/plans/plan%20id/preferences').flush({ items: [{ current: true, preference: preference({ version: 5 }) }] });
    await save;

    expect(preferences.saveState()).toBe('idle');
    expect(preferences.preferenceEtag()).toBe('"5"');
  });

  it('clears preference state when the actor changes during a refresh', async () => {
    const refresh = preferences.load('plan id');
    const own = http.expectOne('/api/v1/plans/plan%20id/members/me/preference');
    const summary = http.expectOne('/api/v1/plans/plan%20id/preferences');
    TestBed.inject(ActorScopeResetService).reset();
    own.flush(preference(), { headers: { ETag: '"1"' } });
    summary.flush({ items: [ { current: true, preference: preference() } ] });
    await refresh;

    expect(preferences.ownPreference()).toBeNull();
    expect(preferences.preferenceEtag()).toBeNull();
    expect(preferences.summary()).toBeNull();
  });

  async function loadExisting(etag: string): Promise<void> {
    const loading = preferences.load('plan id');
    http.expectOne('/api/v1/plans/plan%20id/members/me/preference').flush(preference(), { headers: { ETag: etag } });
    http.expectOne('/api/v1/plans/plan%20id/preferences').flush({ items: [{ current: true, preference: preference() }] });
    await loading;
  }
});

function request(): CreatePreferenceRequest {
  return {
    basisPlanVersion: 7, attendance: 'JOINING', guestCount: 1, selectedWindowIds: ['window-1'],
    personalBudget: { currency: 'PHP', amount: '500.00' }, rankedPreferences: ['parking'], privateNote: 'Near the station.',
  };
}

function preference(overrides: Record<string, unknown> = {}) {
  return {
    accountId: 'member-1', attendance: 'JOINING', guestCount: 1, selectedWindowIds: ['window-1'],
    basisPlanVersion: 7, version: 1, personalBudget: { currency: 'PHP', amount: '500.00' },
    rankedPreferences: ['parking'], privateNote: 'Near the station.', ...overrides,
  };
}

function problem(code: string, status: number) {
  return { code, status, title: 'Request failed', detail: 'The preference changed.', violations: [], correlationId: 'correlation-1' };
}

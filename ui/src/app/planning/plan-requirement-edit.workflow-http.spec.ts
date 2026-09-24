import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ApiHttpClient } from '../api/api-http-client';
import { PlanApi, RequirementReplacementRequest } from './plan-api.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';

describe('plan requirement edit HTTP workflow', () => {
  let edit: PlanRequirementEditService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ApiHttpClient,
        PlanApi,
        PlanRequirementEditService,
      ],
    });
    edit = TestBed.inject(PlanRequirementEditService);
    edit.usePlan('plan id');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('sends stable window IDs, exact money strings, and only If-Match', async () => {
    const body = request();
    const pending = edit.replace('plan id', body, '"7"');
    const replacement = http.expectOne('/api/v1/plans/plan%20id/requirements');

    expect(replacement.request.method).toBe('PUT');
    expect(replacement.request.headers.get('If-Match')).toBe('"7"');
    expect(replacement.request.headers.has('Idempotency-Key')).toBe(false);
    expect(replacement.request.body).toEqual(body);
    replacement.flush(body, { headers: { ETag: '"8"' } });

    await expect(pending).resolves.toMatchObject({ etag: '"8"' });
  });

  it('does not resubmit a stale form during refresh preparation', async () => {
    const first = edit.replace('plan id', request(), '"7"');
    http.expectOne('/api/v1/plans/plan%20id/requirements').flush({
      code: 'PRECONDITION_FAILED',
      status: 412,
      title: 'Precondition failed',
      detail: 'The plan changed.',
      violations: [],
      correlationId: 'correlation-1',
    }, {
      status: 412,
      statusText: 'Precondition Failed',
      headers: { 'Content-Type': 'application/problem+json' },
    });
    await first;

    edit.markRefreshed('"8"');
    http.expectNone('/api/v1/plans/plan%20id/requirements');

    const reapplied = edit.reapply('plan id', { ...request(), title: 'Reviewed title' });
    const replacement = http.expectOne('/api/v1/plans/plan%20id/requirements');
    expect(replacement.request.headers.get('If-Match')).toBe('"8"');
    expect(replacement.request.body).toEqual(expect.objectContaining({ title: 'Reviewed title' }));
    replacement.flush({ ...request(), title: 'Reviewed title' }, { headers: { ETag: '"9"' } });
    await reapplied;
  });
});

function request(): RequirementReplacementRequest {
  return {
    title: 'Friday badminton',
    category: 'COURT',
    timeZone: 'Asia/Manila',
    candidateWindows: [
      {
        id: 'window-1',
        startAt: '2027-01-09T09:00:00+08:00',
        endAt: '2027-01-09T11:00:00+08:00',
      },
      {
        startAt: '2027-01-10T09:00:00+08:00',
        endAt: '2027-01-10T11:00:00+08:00',
      },
    ],
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
    budget: { currency: 'PHP', minimumAmount: '123.40', maximumAmount: '2500.00' },
    mustHaves: ['parking', 'shower'],
    providerSafeNotes: 'Indoor court preferred.',
    categoryAttributes: { hasParking: true, courtCount: 2 },
  };
}

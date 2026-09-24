import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { PlanDetailComponent } from './plan-detail.component';
import { PlanDetailService } from './plan-detail.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';

describe('PlanDetailComponent', () => {
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
});

async function createComponent(detail: ReturnType<typeof fakeDetail>, edit = fakeEdit()) {
  await TestBed.configureTestingModule({
    imports: [PlanDetailComponent],
    providers: [
      provideRouter([{ path: 'plans/:planId', component: PlanDetailComponent }]),
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ planId: 'plan-1' })), snapshot: { paramMap: convertToParamMap({ planId: 'plan-1' }) } } },
      { provide: PlanDetailService, useValue: detail },
      { provide: PlanRequirementEditService, useValue: edit },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(PlanDetailComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
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

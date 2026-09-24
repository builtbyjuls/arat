import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { PlanCreationService } from './plan-creation.service';
import { PlanIndexComponent } from './plan-index.component';
import { PlanIndexService } from './plan-index.service';

describe('PlanIndexComponent', () => {
  it('loads the deep-linked group and renders plan summaries', async () => {
    const plans = fakePlans({ state: 'ready', plans: [{ planId: 'plan-1', title: 'Friday badminton', state: 'COLLABORATING' }] });
    const fixture = await createComponent(plans);
    expect(plans.refresh).toHaveBeenCalledWith('group-1');
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.plan-card')?.getAttribute('href')).toBe('/plans/plan-1');
  });

  it('renders semantic loading, empty, and private unavailable states', async () => {
    const plans = fakePlans({ state: 'loading' }); const fixture = await createComponent(plans);
    expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain('Loading');
    plans.state.set('empty'); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('No collaborative plans yet');
    plans.state.set('not-found'); fixture.detectChanges(); expect(fixture.nativeElement.textContent).toContain('These plans are unavailable.');
  });

  it('offers a touch-sized next-page control', async () => {
    const plans = fakePlans({ state: 'ready', nextCursor: 'next', plans: [{ planId: 'plan-1', title: 'First', state: 'COLLABORATING' }] }); const fixture = await createComponent(plans);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.load-more')?.click();
    expect(plans.loadMore).toHaveBeenCalledOnce(); expect(getComputedStyle(fixture.nativeElement.querySelector('button')).minHeight).toBe('44px');
  });

  it('renders every requirement section and keeps the primary action reachable', async () => {
    const fixture = await createComponent(fakePlans({ state: 'empty' }));
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('#plan-title')).not.toBeNull();
    expect(element.querySelector('#plan-category')).not.toBeNull();
    expect(element.querySelector('#plan-time-zone')).not.toBeNull();
    expect(element.querySelector('#window-start-0')).not.toBeNull();
    expect(element.querySelector('#plan-area-code')).not.toBeNull();
    expect(element.querySelector('#plan-headcount-minimum')).not.toBeNull();
    expect(element.querySelector('#plan-budget-enabled')).not.toBeNull();
    expect(element.querySelector('#plan-provider-notes')).not.toBeNull();
    expect(element.querySelector<HTMLButtonElement>('.primary-action')?.textContent).toContain('Create plan');
    expect(getComputedStyle(element.querySelector('.primary-action') as Element).minHeight).toBe('44px');
  });

  it('navigates to the canonical plan link with the response ETag', async () => {
    const creation = fakeCreation();
    creation.create.mockResolvedValue({
      body: { planId: 'plan-1', createdByAccountId: 'actor-1', state: 'COLLABORATING', version: 1 },
      status: 201, etag: '"1"', location: '/api/v1/plans/plan-1', correlationId: null,
    });
    const fixture = await createComponent(fakePlans({ state: 'empty' }), creation);
    const component = fixture.componentInstance;
    component.createForm.patchValue({
      title: 'Friday badminton', area: { code: 'BGC' }, headcount: { minimum: 4, maximum: 10 },
    });
    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-01-09T09:00', endAt: '2027-01-09T11:00' });
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();

    expect(creation.create).toHaveBeenCalledWith('group-1', expect.objectContaining({ title: 'Friday badminton' }));
    expect(navigate).toHaveBeenCalledWith(['/plans', 'plan-1'], { state: { etag: '"1"' } });
  });

  it('submits through the rendered Angular form', async () => {
    const creation = fakeCreation();
    const fixture = await createComponent(fakePlans({ state: 'empty' }), creation);
    const component = fixture.componentInstance;
    component.createForm.patchValue({
      title: 'Rendered submit', area: { code: 'BGC' }, headcount: { minimum: 2, maximum: 4 },
    });
    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-01-09T09:00', endAt: '2027-01-09T11:00' });

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();

    expect(creation.create).toHaveBeenCalledOnce();
  });

  it('shows and associates empty repeated-field and nested server errors', async () => {
    const creation = fakeCreation();
    creation.violations.set([{ field: 'headcount.minimum', message: 'Minimum is invalid.' }]);
    creation.state.set('error');
    const fixture = await createComponent(fakePlans({ state: 'empty' }), creation);
    const addMustHave = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Add must-have'));
    addMustHave?.click();
    fixture.componentInstance.createForm.markAllAsTouched();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Enter 1 to 120 characters.');
    expect((fixture.nativeElement as HTMLElement).querySelector('#plan-headcount-minimum-error')?.textContent)
      .toContain('Minimum is invalid.');
    expect((fixture.nativeElement as HTMLElement).querySelector('#plan-headcount-minimum')?.getAttribute('aria-describedby'))
      .toContain('plan-headcount-minimum-error');
  });

  it('does not navigate when creation completes after the screen is destroyed', async () => {
    const creation = fakeCreation();
    const completion = deferred<Awaited<ReturnType<PlanCreationService['create']>>>();
    creation.create.mockReturnValue(completion.promise);
    const fixture = await createComponent(fakePlans({ state: 'empty' }), creation);
    const component = fixture.componentInstance;
    component.createForm.patchValue({ title: 'Leaving', area: { code: 'BGC' } });
    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-01-09T09:00', endAt: '2027-01-09T11:00' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();

    fixture.destroy();
    completion.resolve({
      body: { planId: 'plan-1', createdByAccountId: 'actor-1', state: 'COLLABORATING', version: 1 },
      status: 201, etag: '"1"', location: '/api/v1/plans/plan-1', correlationId: null,
    });
    await completion.promise;
    await Promise.resolve();

    expect(creation.releaseGroup).toHaveBeenCalledWith('group-1');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('discards loaded plans when creation proves the group is inaccessible', async () => {
    const plans = fakePlans({
      state: 'ready',
      plans: [{ planId: 'private-plan', title: 'Private plan', state: 'COLLABORATING' }],
    });
    const creation = fakeCreation();
    creation.problemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
    creation.create.mockResolvedValue(null);
    const fixture = await createComponent(plans, creation);
    const component = fixture.componentInstance;
    component.createForm.patchValue({ title: 'Lost access', area: { code: 'BGC' } });
    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-01-09T09:00', endAt: '2027-01-09T11:00' });

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.primary-action')?.click();
    await fixture.whenStable();

    expect(plans.discardInaccessibleGroup).toHaveBeenCalledWith('group-1');
  });

  it('allows a corrected local window after a time-zone conversion error', async () => {
    const creation = fakeCreation();
    const fixture = await createComponent(fakePlans({ state: 'empty' }), creation);
    const component = fixture.componentInstance;
    component.createForm.patchValue({
      title: 'DST plan', timeZone: 'America/New_York', area: { code: 'NYC' },
    });
    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-03-14T02:30', endAt: '2027-03-14T03:30' });
    component.submitCreate();
    expect(component.createForm.controls.candidateWindows.hasError('invalidLocalDateTime')).toBe(true);

    component.createForm.controls.candidateWindows.at(0).setValue({ id: null, startAt: '2027-03-14T03:30', endAt: '2027-03-14T04:30' });
    component.submitCreate();
    await fixture.whenStable();

    expect(creation.create).toHaveBeenCalledOnce();
  });
});

async function createComponent(plans: ReturnType<typeof fakePlans>, creation = fakeCreation()) { await TestBed.configureTestingModule({ imports: [PlanIndexComponent], providers: [provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ groupId: 'group-1' })), snapshot: { paramMap: convertToParamMap({ groupId: 'group-1' }) } } }, { provide: PlanIndexService, useValue: plans }, { provide: PlanCreationService, useValue: creation }] }).compileComponents(); const fixture = TestBed.createComponent(PlanIndexComponent); fixture.detectChanges(); await fixture.whenStable(); return fixture; }
function fakePlans(initial: { readonly state: 'empty' | 'error' | 'loading' | 'loading-more' | 'not-found' | 'ready' | 'refreshing'; readonly nextCursor?: string | null; readonly plans?: readonly { readonly planId: string; readonly title: string; readonly state: string }[]; }) { return { state: signal(initial.state), nextCursor: signal(initial.nextCursor ?? null), plans: signal(initial.plans ?? []), correlationId: signal<string | null>(null), refresh: vi.fn().mockResolvedValue(undefined), loadMore: vi.fn().mockResolvedValue(undefined), discardInaccessibleGroup: vi.fn() }; }
function fakeCreation() { return { state: signal<'error' | 'idle' | 'network-error' | 'submitting'>('idle'), problemCode: signal<string | null>(null), correlationId: signal<string | null>(null), violations: signal<readonly { field: string; message: string }[]>([]), useGroup: vi.fn(), releaseGroup: vi.fn(), create: vi.fn().mockResolvedValue(null), retry: vi.fn().mockResolvedValue(null), dismissError: vi.fn(), reportInvalidResponse: vi.fn() }; }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((resolver) => { resolve = resolver; }); return { promise, resolve }; }

import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { PlanDetailComponent } from './plan-detail.component';
import { PlanDetailService } from './plan-detail.service';

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
});

async function createComponent(detail: ReturnType<typeof fakeDetail>) {
  await TestBed.configureTestingModule({
    imports: [PlanDetailComponent],
    providers: [
      provideRouter([{ path: 'plans/:planId', component: PlanDetailComponent }]),
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ planId: 'plan-1' })), snapshot: { paramMap: convertToParamMap({ planId: 'plan-1' }) } } },
      { provide: PlanDetailService, useValue: detail },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(PlanDetailComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeDetail(state: 'error' | 'loading' | 'not-found' | 'ready', planState = 'COLLABORATING') {
  return {
    state: signal(state),
    correlationId: signal<string | null>(null),
    load: vi.fn().mockResolvedValue(undefined),
    plan: signal(state === 'ready' ? {
      planId: 'plan-1', title: 'Friday badminton', state: planState, version: 7,
      requirements: {
        category: 'COURT', timeZone: 'Asia/Manila', area: { code: 'BGC', radiusKm: 5 },
        headcount: { minimum: 4, maximum: 10 }, budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
        candidateWindows: [], mustHaves: ['parking'], categoryAttributes: { hasParking: true },
      },
    } : null),
  };
}

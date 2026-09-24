import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
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
});

async function createComponent(plans: ReturnType<typeof fakePlans>) { await TestBed.configureTestingModule({ imports: [PlanIndexComponent], providers: [provideRouter([]), { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ groupId: 'group-1' })), snapshot: { paramMap: convertToParamMap({ groupId: 'group-1' }) } } }, { provide: PlanIndexService, useValue: plans }] }).compileComponents(); const fixture = TestBed.createComponent(PlanIndexComponent); fixture.detectChanges(); await fixture.whenStable(); return fixture; }
function fakePlans(initial: { readonly state: 'empty' | 'error' | 'loading' | 'loading-more' | 'not-found' | 'ready' | 'refreshing'; readonly nextCursor?: string | null; readonly plans?: readonly { readonly planId: string; readonly title: string; readonly state: string }[]; }) { return { state: signal(initial.state), nextCursor: signal(initial.nextCursor ?? null), plans: signal(initial.plans ?? []), correlationId: signal<string | null>(null), refresh: vi.fn().mockResolvedValue(undefined), loadMore: vi.fn().mockResolvedValue(undefined) }; }

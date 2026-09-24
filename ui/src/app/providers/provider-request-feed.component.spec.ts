import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { ProviderPublishedRequest } from './provider-api.service';
import { ProviderRequestFeedComponent } from './provider-request-feed.component';
import { ProviderRequestFeedService } from './provider-request-feed.service';

describe('ProviderRequestFeedComponent', () => {
  it('renders only provider-safe request fields and a deep-link detail', async () => {
    const feed = fakeFeed({ items: [request('request-1', 'OPEN', true)], state: 'ready', detail: request('request-1', 'OPEN', true), detailState: 'ready' });
    const fixture = await createComponent(feed, 'request-1');
    const content = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(content).toContain('Deadline:');
    expect(content).toContain('Headcount: 4 to 10');
    expect(content).toContain('Budget: 100.00 to 200.00 PHP');
    expect(content).toContain('Requirements');
    expect(content).toContain('at the last read');
    expect(content).toContain('UI1 does not include an offer action.');
    expect(content).not.toContain('groupId');
    expect(content).not.toContain('member');
    expect(content).not.toContain('recipient');
    expect(content).not.toContain('preference');
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.request-card')?.getAttribute('href'))
      .toContain('requestId=request-1');
  });

  it('explains terminal, expired-by-time, and eligibility-invalid actionability without an offer control', async () => {
    const feed = fakeFeed({ items: [request('open-expired', 'OPEN', false), request('old', 'SUPERSEDED', false), request('closed', 'CLOSED', false), request('cancelled', 'CANCELLED', false)], state: 'ready' });
    const fixture = await createComponent(feed);
    const content = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(content).toContain('expired by time');
    expect(content).toContain('Superseded by a newer request version');
    expect(content).toContain('Closed by the group');
    expect(content).toContain('Cancelled with its plan');
    expect(content).not.toContain('Submit offer');
  });

  it('renders safe unavailable and paging states with practical touch targets', async () => {
    const feed = fakeFeed({ state: 'error', correlationId: 'correlation-1' });
    const fixture = await createComponent(feed);
    expect(fixture.nativeElement.textContent).toContain('provider context or its request access is unavailable');
    expect(fixture.nativeElement.textContent).toContain('restoration does not restore an old request grant');
    expect(fixture.nativeElement.textContent).toContain('correlation-1');

    feed.state.set('ready');
    feed.nextCursor.set('next');
    fixture.detectChanges();
    const loadMore = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.load-more');
    loadMore?.click();
    expect(feed.loadMore).toHaveBeenCalledWith('provider-1');
    expect(getComputedStyle(fixture.nativeElement.querySelector('button')).minHeight).toBe('44px');
  });

  it('formats request times in the request IANA zone instead of the browser zone', async () => {
    const fixture = await createComponent(fakeFeed({ state: 'empty' }));

    const formatted = fixture.componentInstance.formatDate('2027-01-09T09:00:00Z', 'Asia/Manila');

    expect(formatted).toContain('5:00');
    expect(formatted).not.toContain('9:00');
    fixture.destroy();
  });

  it('does not retain a current-actionability claim at or after the deadline', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2027-01-09T08:59:00Z'));
    const requestAtDeadline = request('request-1', 'OPEN', true);
    const fixture = await createComponent(fakeFeed({ items: [requestAtDeadline], state: 'ready' }));
    fixture.autoDetectChanges();
    await Promise.resolve();

    expect(fixture.componentInstance.actionabilityGuidance(requestAtDeadline)).toContain('at the last read');
    vi.advanceTimersByTime(60_000);
    await Promise.resolve();
    await Promise.resolve();
    expect(fixture.componentInstance.actionabilityGuidance(requestAtDeadline)).toContain('deadline has now passed');
    expect(fixture.nativeElement.textContent).toContain('deadline has now passed');
    vi.advanceTimersByTime(1);
    await Promise.resolve();
    expect(fixture.componentInstance.actionabilityGuidance(requestAtDeadline)).toContain('deadline has now passed');
    fixture.destroy();
    vi.useRealTimers();
  });

  it('marks a request expired when a delayed feed response arrives after its deadline', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2027-01-09T08:59:00Z'));
    const feed = fakeFeed({ state: 'ready' });
    const fixture = await createComponent(feed);
    fixture.autoDetectChanges();

    vi.setSystemTime(new Date('2027-01-09T09:01:00Z'));
    feed.items.set([request('request-late', 'OPEN', true)]);
    fixture.componentInstance.refresh();
    await Promise.resolve();
    await Promise.resolve();

    expect(fixture.nativeElement.textContent).toContain('deadline has now passed');
    fixture.destroy();
    vi.useRealTimers();
  });
});

async function createComponent(feed: ReturnType<typeof fakeFeed>, requestId: string | null = null) {
  await TestBed.configureTestingModule({
    imports: [ProviderRequestFeedComponent],
    providers: [
      { provide: ProviderRequestFeedService, useValue: feed },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ providerId: 'provider-1' })), queryParamMap: of(convertToParamMap(requestId === null ? {} : { requestId })), snapshot: { paramMap: convertToParamMap({ providerId: 'provider-1' }) } } },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderRequestFeedComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeFeed(initial: { readonly items?: readonly ProviderPublishedRequest[]; readonly state: 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing'; readonly detail?: ProviderPublishedRequest | null; readonly detailState?: 'absent' | 'error' | 'loading' | 'ready'; readonly correlationId?: string | null }) {
  return {
    items: signal(initial.items ?? []), nextCursor: signal<string | null>(null), state: signal(initial.state), correlationId: signal(initial.correlationId ?? null),
    detail: signal(initial.detail ?? null), detailRequestId: signal<string | null>(null), detailState: signal(initial.detailState ?? 'absent'), detailCorrelationId: signal<string | null>(null),
    refresh: vi.fn().mockResolvedValue(undefined), loadMore: vi.fn().mockResolvedValue(undefined), readDetail: vi.fn().mockResolvedValue(undefined),
  };
}

function request(requestId: string, state: 'OPEN' | 'SUPERSEDED' | 'CLOSED' | 'CANCELLED', actionable: boolean): ProviderPublishedRequest {
  return {
    requestId, state, actionable, category: 'COURT', timeZone: 'Asia/Manila', offerDeadline: '2027-01-09T09:00:00Z',
    requestedWindow: { startAt: '2027-01-09T10:00:00Z', endAt: '2027-01-09T12:00:00Z' }, area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 }, budget: { currency: 'PHP', minimumAmount: '100.00', maximumAmount: '200.00' },
    mustHaves: ['parking'], providerSafeNotes: 'Indoor court preferred.', categoryAttributes: { hasParking: true },
  };
}

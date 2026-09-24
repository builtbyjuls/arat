import { KeyValuePipe } from '@angular/common';
import { ChangeDetectorRef, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ProviderPublishedRequest } from './provider-api.service';
import { ProviderRequestFeedService } from './provider-request-feed.service';

@Component({
  imports: [KeyValuePipe, RouterLink],
  selector: 'app-provider-request-feed',
  styleUrl: './provider-request-feed.component.scss',
  templateUrl: './provider-request-feed.component.html',
})
export class ProviderRequestFeedComponent implements OnDestroy, OnInit {
  readonly feed = inject(ProviderRequestFeedService);
  readonly #route = inject(ActivatedRoute);
  readonly #changeDetector = inject(ChangeDetectorRef);
  readonly #now = signal(Date.now());
  readonly actionabilityNow = this.#now.asReadonly();
  #deadlineTimer: ReturnType<typeof globalThis.setTimeout> | null = null;

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const providerId = params.get('providerId');
      if (providerId !== null) void this.refreshProvider(providerId);
    });
    this.#route.queryParamMap.subscribe((params) => {
      const providerId = this.providerId();
      if (providerId !== null) void this.readSelectedRequest(providerId, params.get('requestId'));
    });
  }

  ngOnDestroy(): void { this.clearDeadlineWakeup(); }

  refresh(): void {
    const providerId = this.providerId();
    if (providerId !== null) void this.refreshProvider(providerId);
  }

  loadMore(): void {
    const providerId = this.providerId();
    if (providerId !== null) void this.loadMoreForProvider(providerId);
  }

  actionabilityGuidance(request: ProviderPublishedRequest, now = this.actionabilityNow()): string {
    if (request.state === 'SUPERSEDED') return 'Superseded by a newer request version. This version cannot be acted on.';
    if (request.state === 'CLOSED') return 'Closed by the group. This version cannot be acted on.';
    if (request.state === 'CANCELLED') return 'Cancelled with its plan. This version cannot be acted on.';
    if (request.actionable && this.hasReachedDeadline(request, now)) {
      return 'The last server read marked this request actionable, but its deadline has now passed. Refresh to confirm the server state. UI1 does not include an offer action.';
    }
    if (request.actionable) return 'The server marked this open request actionable at the last read. The server decides current actionability. UI1 does not include an offer action.';
    return 'The server marked this open request not actionable at the last read because it was expired by time. Refresh before relying on this state.';
  }

  stateLabel(state: string | undefined): string {
    return { OPEN: 'Open', SUPERSEDED: 'Superseded', CLOSED: 'Closed', CANCELLED: 'Cancelled' }[state ?? ''] ?? 'Unknown state';
  }

  formatDate(instant: string | undefined, timeZone: string | undefined): string {
    if (instant === undefined || timeZone === undefined) return 'Unavailable';
    const date = new Date(instant);
    if (Number.isNaN(date.getTime())) return 'Unavailable';
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(date);
  }

  private hasReachedDeadline(request: ProviderPublishedRequest, now: number): boolean {
    const deadline = Date.parse(request.offerDeadline ?? '');
    return Number.isFinite(deadline) && now >= deadline;
  }

  private async refreshProvider(providerId: string): Promise<void> {
    await this.feed.refresh(providerId);
    this.scheduleDeadlineWakeup();
  }

  private async loadMoreForProvider(providerId: string): Promise<void> {
    await this.feed.loadMore(providerId);
    this.scheduleDeadlineWakeup();
  }

  private async readSelectedRequest(providerId: string, requestId: string | null): Promise<void> {
    await this.feed.readDetail(providerId, requestId);
    this.scheduleDeadlineWakeup();
  }

  private scheduleDeadlineWakeup(): void {
    this.clearDeadlineWakeup();
    const now = Date.now();
    this.#now.set(now);
    this.#changeDetector.detectChanges();
    const deadlines = [...this.feed.items(), this.feed.detail()]
      .filter((request): request is ProviderPublishedRequest => request !== null && request.state === 'OPEN' && request.actionable === true)
      .map((request) => Date.parse(request.offerDeadline ?? ''))
      .filter((deadline) => Number.isFinite(deadline) && deadline > now);
    const deadline = Math.min(...deadlines);
    if (!Number.isFinite(deadline)) return;
    this.#deadlineTimer = globalThis.setTimeout(() => {
      this.#deadlineTimer = null;
      this.#now.set(Date.now());
      this.#changeDetector.detectChanges();
      this.scheduleDeadlineWakeup();
    }, Math.min(deadline - now, 2_147_483_647));
  }

  private clearDeadlineWakeup(): void {
    if (this.#deadlineTimer !== null) globalThis.clearTimeout(this.#deadlineTimer);
    this.#deadlineTimer = null;
  }

  private providerId(): string | null { return this.#route.snapshot.paramMap.get('providerId'); }
}

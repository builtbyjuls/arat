import { KeyValuePipe } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanDetailService } from './plan-detail.service';

@Component({
  imports: [KeyValuePipe, RouterLink],
  selector: 'app-plan-detail',
  styleUrl: './plan-detail.component.scss',
  templateUrl: './plan-detail.component.html',
})
export class PlanDetailComponent implements OnInit {
  readonly planDetail = inject(PlanDetailService);
  readonly #route = inject(ActivatedRoute);

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const planId = params.get('planId');
      if (planId !== null) { void this.planDetail.load(planId); }
    });
  }

  refresh(): void {
    const planId = this.#route.snapshot.paramMap.get('planId');
    if (planId !== null) { void this.planDetail.load(planId); }
  }

  stateLabel(state: string | undefined): string {
    return ({
      COLLABORATING: 'Collaborating',
      OPEN_FOR_OFFERS: 'Open for provider offers',
      CANCELLED: 'Cancelled',
    } as Record<string, string>)[state ?? ''] ?? 'Unavailable state';
  }

  actionGuidance(state: string | undefined): string {
    switch (state) {
      case 'COLLABORATING': return 'The group is collaborating on this plan. Preference and organizer planning actions will appear here in later steps.';
      case 'OPEN_FOR_OFFERS': return 'A provider request is open. Its provider-request lifecycle is separate from this collaboration state.';
      case 'CANCELLED': return 'This plan is cancelled. No plan actions are available.';
      default: return 'No plan actions are available for this state in this view.';
    }
  }

  formatWindow(instant: string | undefined, timeZone: string | undefined): string {
    if (instant === undefined || timeZone === undefined) { return 'Unavailable'; }
    const date = new Date(instant);
    if (Number.isNaN(date.getTime())) { return 'Unavailable'; }
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium', timeStyle: 'short', timeZone,
    }).format(date);
  }

  attributeValue(value: unknown): string { return String(value); }
}

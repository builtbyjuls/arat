import { KeyValuePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanDetailService } from './plan-detail.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';
import { PlanRequirementFieldsComponent } from './plan-requirement-fields.component';
import {
  createRequirementForm,
  createRequirementReplacementRequest,
  hydrateRequirementForm,
} from './plan-requirement-form';

@Component({
  imports: [KeyValuePipe, PlanRequirementFieldsComponent, ReactiveFormsModule, RouterLink],
  selector: 'app-plan-detail',
  styleUrl: './plan-detail.component.scss',
  templateUrl: './plan-detail.component.html',
})
export class PlanDetailComponent implements OnDestroy, OnInit {
  readonly planDetail = inject(PlanDetailService);
  readonly requirementEdit = inject(PlanRequirementEditService);
  readonly #route = inject(ActivatedRoute);
  readonly editForm = createRequirementForm();
  #destroyed = false;

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const planId = params.get('planId');
      if (planId !== null) {
        this.requirementEdit.usePlan(planId);
        void this.loadAndHydrate(planId);
      }
    });
  }

  ngOnDestroy(): void {
    this.#destroyed = true;
    const planId = this.planId();
    if (planId !== null) {
      this.requirementEdit.releasePlan(planId);
    }
  }

  refresh(): void {
    const planId = this.planId();
    if (planId !== null) {
      this.requirementEdit.dismiss();
      void this.loadAndHydrate(planId);
    }
  }

  submitRequirements(): void {
    const request = this.requirementRequest();
    const planId = this.planId();
    const etag = this.planDetail.etag();
    if (request === null || planId === null) {
      return;
    }
    if (etag === null) {
      void this.loadAndHydrate(planId);
      return;
    }
    void this.requirementEdit.replace(planId, request, etag)
      .then((result) => this.handleReplacement(result));
  }

  retryRequirements(): void {
    void this.requirementEdit.retry().then((result) => this.handleReplacement(result));
  }

  refreshAfterConflict(): void {
    const planId = this.planId();
    if (planId === null) {
      return;
    }
    void this.planDetail.load(planId).then(() => {
      if (this.#destroyed) {
        return;
      }
      const etag = this.planDetail.etag();
      if (this.planDetail.state() === 'ready' && etag !== null) {
        this.requirementEdit.markRefreshed(etag);
      }
    });
  }

  reapplyRequirements(): void {
    const request = this.requirementRequest();
    const planId = this.planId();
    if (request === null || planId === null) {
      return;
    }
    void this.requirementEdit.reapply(planId, request)
      .then((result) => this.handleReplacement(result));
  }

  dismissRequirementError(): void {
    this.requirementEdit.dismiss();
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
      case 'COLLABORATING': return 'The group is collaborating on this plan. Organizer requirement editing is available here; member preferences arrive in the next step.';
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

  private async loadAndHydrate(planId: string): Promise<void> {
    await this.planDetail.load(planId);
    if (this.#destroyed || this.planId() !== planId) {
      return;
    }
    const plan = this.planDetail.plan();
    if (this.planDetail.state() === 'ready' && plan !== null) {
      try {
        hydrateRequirementForm(this.editForm, plan);
      } catch {
        this.editForm.controls.candidateWindows.setErrors({ invalidLocalDateTime: true });
      }
    }
  }

  private requirementRequest(): ReturnType<typeof createRequirementReplacementRequest> | null {
    this.updateConditionalValidation();
    if (this.editForm.invalid) {
      this.editForm.markAllAsTouched();
      return null;
    }
    try {
      return createRequirementReplacementRequest(this.editForm);
    } catch {
      this.editForm.controls.candidateWindows.setErrors({ invalidLocalDateTime: true });
      this.editForm.controls.candidateWindows.markAllAsTouched();
      return null;
    }
  }

  private updateConditionalValidation(): void {
    for (const window of this.editForm.controls.candidateWindows.controls) {
      window.updateValueAndValidity({ emitEvent: false });
    }
    this.editForm.controls.candidateWindows.updateValueAndValidity({ emitEvent: false });
    const budget = this.editForm.controls.budget.controls;
    budget.minimumAmount.updateValueAndValidity({ emitEvent: false });
    budget.maximumAmount.updateValueAndValidity({ emitEvent: false });
    this.editForm.updateValueAndValidity({ emitEvent: false });
  }

  private handleReplacement(
    result: Awaited<ReturnType<PlanRequirementEditService['replace']>>,
  ): void {
    if (result === null) {
      const planId = this.planId();
      if (
        planId !== null
        && this.requirementEdit.problemCode() === 'PRIVATE_RESOURCE_NOT_FOUND'
      ) {
        this.planDetail.discardInaccessiblePlan(planId);
        hydrateRequirementForm(this.editForm, {});
      }
      return;
    }
    if (this.#destroyed) {
      return;
    }
    const planId = this.planId();
    if (planId !== null) {
      void this.loadAndHydrate(planId);
    }
  }

  private planId(): string | null {
    return this.#route.snapshot.paramMap.get('planId');
  }
}

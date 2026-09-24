import { KeyValuePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanDetailService } from './plan-detail.service';
import { PlanRequirementEditService } from './plan-requirement-edit.service';
import { PlanRequirementFieldsComponent } from './plan-requirement-fields.component';
import { PlanPreferenceService } from './plan-preference.service';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import {
  createRequirementForm,
  createRequirementReplacementRequest,
  hydrateRequirementForm,
} from './plan-requirement-form';
import {
  createPreferenceForm,
  createPreferenceRequest,
  hydratePreferenceForm,
  togglePreferenceWindow,
  updatePreferenceValidation,
} from './plan-preference-form';

@Component({
  imports: [KeyValuePipe, PlanRequirementFieldsComponent, ReactiveFormsModule, RouterLink],
  selector: 'app-plan-detail',
  styleUrl: './plan-detail.component.scss',
  templateUrl: './plan-detail.component.html',
})
export class PlanDetailComponent implements OnDestroy, OnInit {
  readonly planDetail = inject(PlanDetailService);
  readonly requirementEdit = inject(PlanRequirementEditService);
  readonly preferences = inject(PlanPreferenceService);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #route = inject(ActivatedRoute);
  readonly editForm = createRequirementForm();
  readonly preferenceForm = createPreferenceForm();
  readonly preferenceFormReady = signal(false);
  #destroyed = false;
  #preferenceFormPlanId: string | null = null;
  #unregisterScopeReset: (() => void) | null = null;

  ngOnInit(): void {
    this.#unregisterScopeReset = this.#scopeReset.register(() => {
      this.#preferenceFormPlanId = null;
      this.preferenceFormReady.set(false);
      hydratePreferenceForm(this.preferenceForm, null);
    });
    this.#route.paramMap.subscribe((params) => {
      const planId = params.get('planId');
      if (planId !== null) {
        this.requirementEdit.usePlan(planId);
        this.preferences.usePlan(planId);
        void this.loadAndHydrate(planId);
      }
    });
  }

  ngOnDestroy(): void {
    this.#destroyed = true;
    this.#unregisterScopeReset?.();
    this.#unregisterScopeReset = null;
    const planId = this.planId();
    if (planId !== null) {
      this.requirementEdit.releasePlan(planId);
      this.preferences.releasePlan(planId);
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

  submitPreference(): void {
    const plan = this.planDetail.plan();
    const planId = this.planId();
    const planVersion = plan?.version;
    updatePreferenceValidation(this.preferenceForm);
    if (plan === null || planId === null || typeof planVersion !== 'number' || !Number.isInteger(planVersion) || this.preferenceForm.invalid) {
      this.preferenceForm.markAllAsTouched();
      return;
    }
    const request = createPreferenceRequest(this.preferenceForm, planVersion);
    void this.preferences.save(planId, request).then((preference) => {
      if (preference !== null && !this.#destroyed && this.planId() === planId) {
        hydratePreferenceForm(this.preferenceForm, preference);
      } else if (
        preference === null
        && this.preferences.problemCode() === 'PRIVATE_RESOURCE_NOT_FOUND'
        && this.planId() === planId
      ) {
        this.planDetail.discardInaccessiblePlan(planId);
      }
    });
  }

  togglePreferenceWindow(windowId: string, selected: boolean): void {
    togglePreferenceWindow(this.preferenceForm, windowId, selected);
  }

  updatePreferenceValidation(): void {
    updatePreferenceValidation(this.preferenceForm);
  }

  retiredPreferenceWindowIds(): readonly string[] {
    const activeWindowIds = new Set(
      (this.planDetail.plan()?.requirements?.candidateWindows ?? [])
        .map((window) => window.id)
        .filter((windowId): windowId is string => typeof windowId === 'string'),
    );
    return this.preferenceForm.controls.selectedWindowIds.value
      .filter((windowId) => !activeWindowIds.has(windowId));
  }

  preferenceViolations(field: string): readonly { field: string; message: string }[] {
    return this.preferences.violations().filter((violation) => violation.field === field);
  }

  preferenceUnassociatedViolations(): readonly { field: string; message: string }[] {
    const associatedFields = new Set([
      'attendance', 'guestCount', 'selectedWindowIds', 'personalBudget', 'personalBudget.amount',
      'rankedPreferences', 'privateNote',
    ]);
    return this.preferences.violations().filter((violation) => !associatedFields.has(violation.field));
  }

  refreshPreferenceConflict(): void {
    const planId = this.planId();
    if (planId === null) {
      return;
    }
    void Promise.all([this.planDetail.load(planId), this.preferences.load(planId)]).then(() => {
      if (!this.#destroyed && this.planId() === planId && this.planDetail.state() === 'ready') {
        if (
          this.#preferenceFormPlanId === null
          && (this.preferences.ownState() === 'ready' || this.preferences.ownState() === 'absent')
        ) {
          hydratePreferenceForm(this.preferenceForm, this.preferences.ownPreference());
          this.#preferenceFormPlanId = planId;
          this.preferenceFormReady.set(true);
        }
        this.preferences.dismiss();
      }
    });
  }

  dismissPreferenceError(): void {
    this.preferences.dismiss();
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
    if (this.#preferenceFormPlanId !== planId) {
      this.preferenceFormReady.set(false);
    }
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
      await this.preferences.load(planId);
      if (this.preferences.summaryProblemCode() === 'PRIVATE_RESOURCE_NOT_FOUND') {
        this.planDetail.discardInaccessiblePlan(planId);
        return;
      }
      if (
        !this.#destroyed
        && this.planId() === planId
        && this.#preferenceFormPlanId !== planId
        && (this.preferences.ownState() === 'ready' || this.preferences.ownState() === 'absent')
      ) {
        hydratePreferenceForm(this.preferenceForm, this.preferences.ownPreference());
        this.#preferenceFormPlanId = planId;
        this.preferenceFormReady.set(true);
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

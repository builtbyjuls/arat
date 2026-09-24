import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PlanCreationService } from './plan-creation.service';
import { PlanIndexService } from './plan-index.service';
import { PlanRequirementFieldsComponent } from './plan-requirement-fields.component';
import { createPlanRequest, createRequirementForm } from './plan-requirement-form';

@Component({
  imports: [PlanRequirementFieldsComponent, ReactiveFormsModule, RouterLink],
  selector: 'app-plan-index',
  styleUrl: './plan-index.component.scss',
  templateUrl: './plan-index.component.html',
})
export class PlanIndexComponent implements OnDestroy, OnInit {
  readonly planIndex = inject(PlanIndexService);
  readonly planCreation = inject(PlanCreationService);
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly createForm = createRequirementForm();
  #destroyed = false;

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const groupId = params.get('groupId');
      if (groupId !== null) {
        this.planCreation.useGroup(groupId);
        void this.planIndex.refresh(groupId);
      }
    });
  }

  ngOnDestroy(): void {
    this.#destroyed = true;
    const groupId = this.groupId();
    if (groupId !== null) {
      this.planCreation.releaseGroup(groupId);
    }
  }

  refresh(): void { const groupId = this.groupId(); if (groupId !== null) { void this.planIndex.refresh(groupId); } }
  loadMore(): void { void this.planIndex.loadMore(); }

  submitCreate(): void {
    this.updateConditionalValidation();
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }

    const groupId = this.groupId();
    if (groupId === null) {
      return;
    }

    let request;
    try {
      request = createPlanRequest(this.createForm);
    } catch {
      this.createForm.controls.candidateWindows.setErrors({ invalidLocalDateTime: true });
      this.createForm.controls.candidateWindows.markAllAsTouched();
      return;
    }
    void this.planCreation.create(groupId, request).then((result) => this.handleCreateResult(result));
  }

  retryCreate(): void {
    void this.planCreation.retry().then((result) => this.handleCreateResult(result));
  }

  dismissCreateError(): void {
    this.planCreation.dismissError();
  }

  private updateConditionalValidation(): void {
    this.createForm.controls.candidateWindows.updateValueAndValidity({ emitEvent: false });
    const budget = this.createForm.controls.budget.controls;
    budget.minimumAmount.updateValueAndValidity({ emitEvent: false });
    budget.maximumAmount.updateValueAndValidity({ emitEvent: false });
    this.createForm.updateValueAndValidity({ emitEvent: false });
  }

  private handleCreateResult(result: Awaited<ReturnType<PlanCreationService['create']>>): void {
    if (this.#destroyed) {
      return;
    }
    if (result === null) {
      if (isInaccessibleProblem(this.planCreation.problemCode())) {
        const groupId = this.groupId();
        if (groupId !== null) {
          this.planIndex.discardInaccessibleGroup(groupId);
        }
      }
      return;
    }
    const planId = planIdFromLocation(result.location);
    if (planId === null || result.etag === null) {
      this.planCreation.reportInvalidResponse();
      return;
    }
    void this.#router.navigate(['/plans', planId], { state: { etag: result.etag } });
  }

  private groupId(): string | null { return this.#route.snapshot.paramMap.get('groupId'); }
}

function isInaccessibleProblem(problemCode: string | null): boolean {
  return problemCode === 'ACCESS_DENIED'
    || problemCode === 'FORBIDDEN_ROLE'
    || problemCode === 'PRIVATE_RESOURCE_NOT_FOUND';
}

function planIdFromLocation(location: string | null): string | null {
  if (location === null) {
    return null;
  }
  try {
    const path = new URL(location, globalThis.location.origin).pathname;
    const match = /^\/api\/v1\/plans\/([^/]+)$/.exec(path);
    return match === null ? null : decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

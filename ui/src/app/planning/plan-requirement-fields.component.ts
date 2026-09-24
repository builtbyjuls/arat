import { Component, input } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { ApiProblemViolation } from '../api/api-http-client';
import {
  RequirementForm,
  createCandidateWindowForm,
  createCategoryAttributeForm,
  createMustHaveControl,
} from './plan-requirement-form';

@Component({
  imports: [ReactiveFormsModule],
  selector: 'app-plan-requirement-fields',
  styleUrl: './plan-requirement-fields.component.scss',
  templateUrl: './plan-requirement-fields.component.html',
})
export class PlanRequirementFieldsComponent {
  readonly form = input.required<RequirementForm>();
  readonly serverViolations = input<readonly ApiProblemViolation[]>([]);

  addWindow(): void {
    if (this.form().controls.candidateWindows.length < 10) {
      this.form().controls.candidateWindows.push(createCandidateWindowForm());
    }
  }

  removeWindow(index: number): void {
    if (this.form().controls.candidateWindows.length > 1) {
      this.form().controls.candidateWindows.removeAt(index);
    }
  }

  addMustHave(): void {
    if (this.form().controls.mustHaves.length < 20) {
      this.form().controls.mustHaves.push(createMustHaveControl());
    }
  }

  removeMustHave(index: number): void {
    this.form().controls.mustHaves.removeAt(index);
  }

  addAttribute(): void {
    if (this.form().controls.categoryAttributes.length < 20) {
      this.form().controls.categoryAttributes.push(createCategoryAttributeForm());
    }
  }

  removeAttribute(index: number): void {
    this.form().controls.categoryAttributes.removeAt(index);
  }

  serverViolation(field: string): string | null {
    return this.serverViolations().find((violation) => violation.field === field)?.message ?? null;
  }

  serverViolationFor(...fields: readonly string[]): string | null {
    return this.serverViolations().find((violation) => fields.includes(violation.field))?.message ?? null;
  }

  serverViolationUnder(prefix: string): string | null {
    return this.serverViolations().find((violation) => (
      violation.field === prefix
      || violation.field.startsWith(`${prefix}.`)
      || violation.field.startsWith(`${prefix}[`)
    ))?.message ?? null;
  }
}

import { FormControl, FormGroup, Validators } from '@angular/forms';
import {
  FinalizeRequirementsRequest,
  PlanDetail,
} from './plan-api.service';
import { localDateTimeToOffset } from './plan-requirement-form';

export type PlanFinalizationForm = FormGroup<{
  candidateWindowId: FormControl<string>;
  offerDeadline: FormControl<string>;
}>;

const FINALIZATION_ERRORS = [
  'activeWindow',
  'invalidLocalDateTime',
  'notBeforeStart',
  'notFuture',
] as const;

export function createPlanFinalizationForm(): PlanFinalizationForm {
  return new FormGroup({
    candidateWindowId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    offerDeadline: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });
}

export function validatePlanFinalization(
  form: PlanFinalizationForm,
  plan: PlanDetail,
  now = new Date(),
): boolean {
  const candidateControl = form.controls.candidateWindowId;
  const deadlineControl = form.controls.offerDeadline;
  removeErrors(candidateControl, FINALIZATION_ERRORS);
  removeErrors(deadlineControl, FINALIZATION_ERRORS);
  candidateControl.updateValueAndValidity({ emitEvent: false });
  deadlineControl.updateValueAndValidity({ emitEvent: false });

  const candidate = plan.requirements?.candidateWindows?.find(
    (window) => window.id === candidateControl.value,
  );
  if (candidate === undefined || candidate.startAt === undefined) {
    addError(candidateControl, 'activeWindow');
  }

  let deadline: string | null = null;
  if (deadlineControl.value.length > 0 && plan.requirements?.timeZone !== undefined) {
    try {
      deadline = localDateTimeToOffset(deadlineControl.value, plan.requirements.timeZone);
    } catch {
      addError(deadlineControl, 'invalidLocalDateTime');
    }
  }

  if (deadline !== null) {
    const deadlineTime = new Date(deadline).getTime();
    const chosenStartTime = new Date(candidate?.startAt ?? '').getTime();
    if (deadlineTime <= now.getTime()) {
      addError(deadlineControl, 'notFuture');
    }
    if (!Number.isNaN(chosenStartTime) && deadlineTime >= chosenStartTime) {
      addError(deadlineControl, 'notBeforeStart');
    }
  }

  return form.valid;
}

export function createFinalizeRequirementsRequest(
  form: PlanFinalizationForm,
  plan: PlanDetail,
): FinalizeRequirementsRequest {
  const timeZone = plan.requirements?.timeZone;
  if (timeZone === undefined) {
    throw new Error('A plan time zone is required.');
  }
  return {
    candidateWindowId: form.controls.candidateWindowId.value,
    offerDeadline: localDateTimeToOffset(form.controls.offerDeadline.value, timeZone),
  };
}

function addError(control: FormControl<string>, name: string): void {
  control.setErrors({ ...(control.errors ?? {}), [name]: true });
}

function removeErrors(
  control: FormControl<string>,
  names: readonly string[],
): void {
  if (control.errors === null) {
    return;
  }
  const excluded = new Set(names);
  const errors = Object.fromEntries(
    Object.entries(control.errors).filter(([name]) => !excluded.has(name)),
  );
  control.setErrors(Object.keys(errors).length === 0 ? null : errors);
}

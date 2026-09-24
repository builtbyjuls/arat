import { FormControl, FormGroup, ValidatorFn, Validators } from '@angular/forms';
import { CreatePreferenceRequest, PlanPreference } from './plan-api.service';

type Attendance = NonNullable<CreatePreferenceRequest['attendance']>;

export type PreferenceForm = FormGroup<{
  attendance: FormControl<Attendance>;
  guestCount: FormControl<number>;
  selectedWindowIds: FormControl<string[]>;
  personalBudgetEnabled: FormControl<boolean>;
  personalBudgetAmount: FormControl<string>;
  rankedPreferences: FormControl<string>;
  privateNote: FormControl<string>;
}>;

export function createPreferenceForm(): PreferenceForm {
  const form = new FormGroup({
    attendance: new FormControl<Attendance>('INTERESTED', { nonNullable: true }),
    guestCount: new FormControl(0, {
      nonNullable: true,
      validators: [Validators.min(0), Validators.max(20), integer()],
    }),
    selectedWindowIds: new FormControl<string[]>([], { nonNullable: true }),
    personalBudgetEnabled: new FormControl(false, { nonNullable: true }),
    personalBudgetAmount: new FormControl('', { nonNullable: true, validators: [personalBudget()] }),
    rankedPreferences: new FormControl('', { nonNullable: true, validators: [rankedPreferences()] }),
    privateNote: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(1000)] }),
  }, { validators: [notJoiningHasNoGuests()] });
  form.controls.personalBudgetEnabled.valueChanges.subscribe(() => updatePreferenceValidation(form));
  return form;
}

export function hydratePreferenceForm(form: PreferenceForm, preference: PlanPreference | null): void {
  form.setValue({
    attendance: preference?.attendance ?? 'INTERESTED',
    guestCount: preference?.guestCount ?? 0,
    selectedWindowIds: [...(preference?.selectedWindowIds ?? [])],
    personalBudgetEnabled: preference?.personalBudget !== undefined && preference.personalBudget !== null,
    personalBudgetAmount: preference?.personalBudget?.amount ?? '',
    rankedPreferences: (preference?.rankedPreferences ?? []).join('\n'),
    privateNote: preference?.privateNote ?? '',
  });
}

export function createPreferenceRequest(
  form: PreferenceForm,
  basisPlanVersion: number,
): CreatePreferenceRequest {
  const value = form.getRawValue();
  const rankedPreferences = value.rankedPreferences
    .split('\n')
    .map((preference) => preference.trim())
    .filter((preference) => preference.length > 0);
  return {
    basisPlanVersion,
    attendance: value.attendance,
    guestCount: value.guestCount,
    selectedWindowIds: [...value.selectedWindowIds],
    rankedPreferences,
    ...(value.personalBudgetEnabled ? {
      personalBudget: { currency: 'PHP', amount: value.personalBudgetAmount },
    } : {}),
    ...(value.privateNote.length > 0 ? { privateNote: value.privateNote } : {}),
  };
}

export function togglePreferenceWindow(form: PreferenceForm, windowId: string, selected: boolean): void {
  const selectedWindowIds = form.controls.selectedWindowIds.value;
  form.controls.selectedWindowIds.setValue(selected
    ? [...new Set([...selectedWindowIds, windowId])]
    : selectedWindowIds.filter((id) => id !== windowId));
}

export function updatePreferenceValidation(form: PreferenceForm): void {
  form.controls.personalBudgetAmount.updateValueAndValidity({ emitEvent: false });
  form.updateValueAndValidity({ emitEvent: false });
}

function integer(): ValidatorFn {
  return (control) => Number.isInteger(control.value) ? null : { integer: true };
}

function personalBudget(): ValidatorFn {
  return (control) => {
    if (control.parent?.get('personalBudgetEnabled')?.value !== true) {
      return null;
    }
    return /^(?:(?:0|[1-9][0-9]{0,5})\.[0-9]{2}|1000000\.00)$/.test(control.value)
      ? null
      : { money: true };
  };
}

function rankedPreferences(): ValidatorFn {
  return (control) => {
    const rawValue = typeof control.value === 'string' ? control.value : '';
    const values = rawValue.split('\n').map((value: string) => value.trim()).filter(Boolean);
    return values.length <= 10
      && values.every((value: string) => value.length <= 80)
      && new Set(values).size === values.length
      ? null
      : { rankedPreferences: true };
  };
}

function notJoiningHasNoGuests(): ValidatorFn {
  return (control) => control.get('attendance')?.value !== 'NOT_JOINING'
    || control.get('guestCount')?.value === 0
    ? null
    : { notJoiningGuests: true };
}

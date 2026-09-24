import {
  FormArray,
  FormControl,
  FormGroup,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import {
  CreatePlanRequest,
  PlanDetail,
  RequirementReplacementRequest,
} from './plan-api.service';

export type ActivityCategory = 'COURT' | 'GROUP_DINING' | 'KTV';
export type CategoryAttributeType = 'boolean' | 'integer' | 'string';

export type CandidateWindowForm = FormGroup<{
  id: FormControl<string | null>;
  startAt: FormControl<string>;
  endAt: FormControl<string>;
}>;

export type CategoryAttributeForm = FormGroup<{
  key: FormControl<string>;
  type: FormControl<CategoryAttributeType>;
  value: FormControl<string>;
}>;

export type RequirementForm = FormGroup<{
  title: FormControl<string>;
  category: FormControl<ActivityCategory>;
  timeZone: FormControl<string>;
  candidateWindows: FormArray<CandidateWindowForm>;
  area: FormGroup<{
    code: FormControl<string>;
    radiusKm: FormControl<number>;
  }>;
  headcount: FormGroup<{
    minimum: FormControl<number>;
    maximum: FormControl<number>;
  }>;
  budgetEnabled: FormControl<boolean>;
  budget: FormGroup<{
    minimumAmount: FormControl<string>;
    maximumAmount: FormControl<string>;
  }>;
  mustHaves: FormArray<FormControl<string>>;
  providerSafeNotes: FormControl<string>;
  categoryAttributes: FormArray<CategoryAttributeForm>;
}>;

interface CandidateWindowOriginal {
  readonly endAt: string;
  readonly endLocal: string;
  readonly startAt: string;
  readonly startLocal: string;
  readonly timeZone: string;
}

const MONEY_PATTERN = /^(0|[1-9][0-9]{0,6})\.[0-9]{2}$/;
const ATTRIBUTE_KEY_PATTERN = /^[a-z][A-Za-z0-9]{0,39}$/;
const LOCAL_DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;
const MAX_MONEY_CENTAVOS = 100_000_000n;
const candidateWindowOriginals = new WeakMap<CandidateWindowForm, CandidateWindowOriginal>();

export function createRequirementForm(): RequirementForm {
  return new FormGroup({
    title: textControl('', [trimmedRequired(), Validators.maxLength(120)]),
    category: new FormControl<ActivityCategory>('COURT', { nonNullable: true }),
    timeZone: textControl('Asia/Manila', [trimmedRequired(), Validators.maxLength(64), ianaTimeZone()]),
    candidateWindows: new FormArray<CandidateWindowForm>([createCandidateWindowForm()], {
      validators: [Validators.required, Validators.maxLength(10)],
    }),
    area: new FormGroup({
      code: textControl('', [trimmedRequired(), Validators.maxLength(64)]),
      radiusKm: numberControl(5, [Validators.required, integer(), Validators.min(1), Validators.max(100)]),
    }),
    headcount: new FormGroup({
      minimum: numberControl(1, [Validators.required, integer(), Validators.min(1), Validators.max(100)]),
      maximum: numberControl(1, [Validators.required, integer(), Validators.min(1), Validators.max(100)]),
    }, { validators: orderedNumberRange('minimum', 'maximum') }),
    budgetEnabled: new FormControl(false, { nonNullable: true }),
    budget: new FormGroup({
      minimumAmount: textControl('', [moneyAmount()]),
      maximumAmount: textControl('', [moneyAmount()]),
    }),
    mustHaves: new FormArray<FormControl<string>>([], {
      validators: [Validators.maxLength(20), uniqueTrimmedValues()],
    }),
    providerSafeNotes: textControl('', [Validators.maxLength(1000)]),
    categoryAttributes: new FormArray<CategoryAttributeForm>([], {
      validators: [Validators.maxLength(20), uniqueAttributeKeys()],
    }),
  }, { validators: optionalBudgetRange() });
}

export function createCandidateWindowForm(
  startAt = '',
  endAt = '',
  id: string | null = null,
): CandidateWindowForm {
  return new FormGroup({
    id: new FormControl(id),
    startAt: textControl(startAt, [Validators.required]),
    endAt: textControl(endAt, [Validators.required]),
  }, { validators: localDateTimeRange() });
}

export function createMustHaveControl(value = ''): FormControl<string> {
  return textControl(value, [trimmedRequired(), Validators.maxLength(120)]);
}

export function createCategoryAttributeForm(): CategoryAttributeForm {
  return new FormGroup({
    key: textControl('', [Validators.required, Validators.pattern(ATTRIBUTE_KEY_PATTERN)]),
    type: new FormControl<CategoryAttributeType>('string', { nonNullable: true }),
    value: textControl('', [Validators.required]),
  }, { validators: categoryAttributeValue() });
}

export function createPlanRequest(form: RequirementForm): CreatePlanRequest {
  const request = requirementRequestValue(form);
  return {
    ...request,
    candidateWindows: request.candidateWindows.map(({ startAt, endAt }) => ({ startAt, endAt })),
  };
}

export function createRequirementReplacementRequest(
  form: RequirementForm,
): RequirementReplacementRequest {
  return requirementRequestValue(form);
}

export function hydrateRequirementForm(form: RequirementForm, plan: PlanDetail): void {
  const requirements = plan.requirements;
  const timeZone = requirements?.timeZone ?? '';
  const windows = requirements?.candidateWindows ?? [];

  form.reset({
    title: plan.title ?? '',
    category: isActivityCategory(requirements?.category) ? requirements.category : 'COURT',
    timeZone,
    area: {
      code: requirements?.area?.code ?? '',
      radiusKm: requirements?.area?.radiusKm ?? 5,
    },
    headcount: {
      minimum: requirements?.headcount?.minimum ?? 1,
      maximum: requirements?.headcount?.maximum ?? 1,
    },
    budgetEnabled: requirements?.budget != null,
    budget: {
      minimumAmount: requirements?.budget?.minimumAmount ?? '',
      maximumAmount: requirements?.budget?.maximumAmount ?? '',
    },
    providerSafeNotes: requirements?.providerSafeNotes ?? '',
  });

  form.controls.candidateWindows.clear();
  for (const window of windows) {
    const startAt = window.startAt ?? '';
    const endAt = window.endAt ?? '';
    const startLocal = instantToLocalDateTime(startAt, timeZone);
    const endLocal = instantToLocalDateTime(endAt, timeZone);
    const candidateWindow = createCandidateWindowForm(startLocal, endLocal, window.id ?? null);
    candidateWindowOriginals.set(candidateWindow, {
      startAt,
      endAt,
      startLocal,
      endLocal,
      timeZone,
    });
    form.controls.candidateWindows.push(candidateWindow);
    candidateWindow.updateValueAndValidity();
  }
  if (form.controls.candidateWindows.length === 0) {
    form.controls.candidateWindows.push(createCandidateWindowForm());
  }

  form.controls.mustHaves.clear();
  for (const mustHave of requirements?.mustHaves ?? []) {
    form.controls.mustHaves.push(createMustHaveControl(mustHave));
  }

  form.controls.categoryAttributes.clear();
  for (const [key, value] of Object.entries(requirements?.categoryAttributes ?? {})) {
    const attribute = createCategoryAttributeForm();
    attribute.setValue({
      key,
      type: categoryAttributeType(value),
      value: String(value),
    });
    form.controls.categoryAttributes.push(attribute);
  }

  form.markAsPristine();
  form.markAsUntouched();
  form.updateValueAndValidity();
}

function requirementRequestValue(form: RequirementForm): RequirementReplacementRequest {
  const value = form.getRawValue();
  const timeZone = value.timeZone.trim();
  const categoryAttributes: Record<string, boolean | number | string> = {};

  for (const attribute of value.categoryAttributes) {
    const key = attribute.key.trim();
    categoryAttributes[key] = attribute.type === 'boolean'
      ? attribute.value === 'true'
      : attribute.type === 'integer'
        ? Number.parseInt(attribute.value, 10)
        : attribute.value;
  }

  return {
    title: value.title.trim(),
    category: value.category,
    timeZone,
    candidateWindows: form.controls.candidateWindows.controls.map((windowForm) => {
      const window = windowForm.getRawValue();
      const original = candidateWindowOriginals.get(windowForm);
      return {
        ...(window.id === null ? {} : { id: window.id }),
        startAt: original !== undefined
          && original.timeZone === timeZone
          && original.startLocal === window.startAt
          ? original.startAt
          : localDateTimeToOffset(window.startAt, timeZone),
        endAt: original !== undefined
          && original.timeZone === timeZone
          && original.endLocal === window.endAt
          ? original.endAt
          : localDateTimeToOffset(window.endAt, timeZone),
      };
    }),
    area: {
      code: value.area.code.trim(),
      radiusKm: value.area.radiusKm,
    },
    headcount: value.headcount,
    ...(value.budgetEnabled ? {
      budget: {
        currency: 'PHP',
        minimumAmount: value.budget.minimumAmount,
        maximumAmount: value.budget.maximumAmount,
      },
    } : {}),
    mustHaves: value.mustHaves.map((mustHave) => mustHave.trim()),
    ...(value.providerSafeNotes.length > 0
      ? { providerSafeNotes: value.providerSafeNotes }
      : {}),
    categoryAttributes,
  };
}

export function instantToLocalDateTime(instant: string, timeZone: string): string {
  if (!isIanaTimeZone(timeZone)) {
    throw new Error('A valid instant and IANA time zone are required.');
  }
  const parsed = new Date(instant);
  if (Number.isNaN(parsed.getTime())) {
    throw new Error('A valid instant and IANA time zone are required.');
  }
  const parts = dateTimeParts(parsed.getTime(), timeZone);
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`;
}

export function localDateTimeToOffset(value: string, timeZone: string): string {
  const match = LOCAL_DATE_TIME_PATTERN.exec(value);
  if (match === null || !isIanaTimeZone(timeZone)) {
    throw new Error('A valid local date-time and IANA time zone are required.');
  }

  const components = match.slice(1).map((part) => Number.parseInt(part, 10));
  const [year, month, day, hour, minute] = components;
  const localAsUtc = Date.UTC(year, month - 1, day, hour, minute);
  let instant = localAsUtc;

  for (let attempt = 0; attempt < 3; attempt += 1) {
    instant = localAsUtc - timeZoneOffsetMilliseconds(instant, timeZone);
  }

  const localParts = dateTimeParts(instant, timeZone);
  if (
    localParts.year !== year
    || localParts.month !== month
    || localParts.day !== day
    || localParts.hour !== hour
    || localParts.minute !== minute
  ) {
    throw new Error('The local date-time does not exist in this time zone.');
  }

  const offsetMinutes = Math.round((localAsUtc - instant) / 60_000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const absoluteMinutes = Math.abs(offsetMinutes);
  const offset = `${sign}${pad(Math.floor(absoluteMinutes / 60))}:${pad(absoluteMinutes % 60)}`;
  return `${value}:00${offset}`;
}

function textControl(value: string, validators: readonly ValidatorFn[] = []): FormControl<string> {
  return new FormControl(value, { nonNullable: true, validators: [...validators] });
}

function numberControl(value: number, validators: readonly ValidatorFn[]): FormControl<number> {
  return new FormControl(value, { nonNullable: true, validators: [...validators] });
}

function trimmedRequired(): ValidatorFn {
  return (control) => typeof control.value === 'string' && control.value.trim().length > 0
    ? null
    : { required: true };
}

function ianaTimeZone(): ValidatorFn {
  return (control) => typeof control.value === 'string' && isIanaTimeZone(control.value.trim())
    ? null
    : { ianaTimeZone: true };
}

function isIanaTimeZone(value: string): boolean {
  try {
    if (value !== 'UTC' && !value.includes('/')) {
      return false;
    }
    return new Intl.DateTimeFormat('en-US', { timeZone: value }).resolvedOptions().timeZone.length > 0;
  } catch {
    return false;
  }
}

function localDateTimeRange(): ValidatorFn {
  return (control) => {
    const startAt = control.get('startAt')?.value;
    const endAt = control.get('endAt')?.value;
    if (typeof startAt !== 'string' || typeof endAt !== 'string' || startAt.length === 0 || endAt.length === 0) {
      return null;
    }
    const windowForm = control as CandidateWindowForm;
    const original = candidateWindowOriginals.get(windowForm);
    const timeZone = control.parent?.parent?.get('timeZone')?.value;
    if (
      original !== undefined
      && timeZone === original.timeZone
      && startAt === original.startLocal
      && endAt === original.endLocal
    ) {
      return null;
    }
    if (typeof timeZone !== 'string') {
      return startAt < endAt ? null : { invalidInterval: true };
    }
    try {
      const submittedStart = original !== undefined
        && timeZone === original.timeZone
        && startAt === original.startLocal
        ? original.startAt
        : localDateTimeToOffset(startAt, timeZone);
      const submittedEnd = original !== undefined
        && timeZone === original.timeZone
        && endAt === original.endLocal
        ? original.endAt
        : localDateTimeToOffset(endAt, timeZone);
      return new Date(submittedStart).getTime() < new Date(submittedEnd).getTime()
        ? null
        : { invalidInterval: true };
    } catch {
      return null;
    }
  };
}

function orderedNumberRange(minimumName: string, maximumName: string): ValidatorFn {
  return (control) => {
    const minimum = control.get(minimumName)?.value;
    const maximum = control.get(maximumName)?.value;
    return typeof minimum === 'number' && typeof maximum === 'number' && minimum > maximum
      ? { invalidRange: true }
      : null;
  };
}

function integer(): ValidatorFn {
  return (control) => typeof control.value === 'number' && Number.isInteger(control.value)
    ? null
    : { integer: true };
}

function moneyAmount(): ValidatorFn {
  return (control) => {
    if (control.parent?.parent?.get('budgetEnabled')?.value !== true) {
      return null;
    }
    return typeof control.value === 'string' && validMoney(control.value)
      ? null
      : { money: true };
  };
}

function optionalBudgetRange(): ValidatorFn {
  return (control) => {
    if (control.get('budgetEnabled')?.value !== true) {
      return null;
    }
    const minimum = control.get('budget.minimumAmount')?.value;
    const maximum = control.get('budget.maximumAmount')?.value;
    if (typeof minimum !== 'string' || typeof maximum !== 'string') {
      return { invalidBudget: true };
    }
    if (!validMoney(minimum) || !validMoney(maximum)) {
      return { invalidBudget: true };
    }
    return moneyCentavos(minimum) <= moneyCentavos(maximum)
      ? null
      : { invalidBudgetRange: true };
  };
}

function uniqueTrimmedValues(): ValidatorFn {
  return (control) => {
    const values = control.value;
    if (!Array.isArray(values)) {
      return null;
    }
    const normalized = values.map((value: unknown) => typeof value === 'string' ? value.trim() : value);
    return new Set(normalized).size === normalized.length ? null : { duplicate: true };
  };
}

function uniqueAttributeKeys(): ValidatorFn {
  return (control) => {
    const values = control.value;
    if (!Array.isArray(values)) {
      return null;
    }
    const keys = values.map((value: unknown) => {
      if (typeof value !== 'object' || value === null || !('key' in value)) {
        return '';
      }
      const key = (value as { key: unknown }).key;
      return typeof key === 'string' ? key.trim() : '';
    });
    return new Set(keys).size === keys.length ? null : { duplicate: true };
  };
}

function categoryAttributeValue(): ValidatorFn {
  return (control) => {
    const type = control.get('type')?.value;
    const value = control.get('value')?.value;
    if (typeof value !== 'string') {
      return { invalidAttributeValue: true };
    }
    if (type === 'boolean') {
      return value === 'true' || value === 'false' ? null : { invalidAttributeValue: true };
    }
    if (type === 'integer') {
      return /^(0|[1-9][0-9]{0,6})$/.test(value) && Number.parseInt(value, 10) <= 1_000_000
        ? null
        : { invalidAttributeValue: true };
    }
    return value.length >= 1 && value.length <= 120 ? null : { invalidAttributeValue: true };
  };
}

function validMoney(value: string): boolean {
  return MONEY_PATTERN.test(value) && moneyCentavos(value) <= MAX_MONEY_CENTAVOS;
}

function moneyCentavos(value: string): bigint {
  const [whole, fraction] = value.split('.');
  return BigInt(whole) * 100n + BigInt(fraction);
}

function timeZoneOffsetMilliseconds(instant: number, timeZone: string): number {
  const parts = dateTimeParts(instant, timeZone);
  return Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute) - instant;
}

function dateTimeParts(instant: number, timeZone: string): Record<'day' | 'hour' | 'minute' | 'month' | 'year', number> {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  });
  const values = Object.fromEntries(formatter.formatToParts(new Date(instant))
    .filter((part) => part.type !== 'literal')
    .map((part) => [part.type, Number.parseInt(part.value, 10)]));
  return values as Record<'day' | 'hour' | 'minute' | 'month' | 'year', number>;
}

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

function isActivityCategory(value: unknown): value is ActivityCategory {
  return value === 'COURT' || value === 'GROUP_DINING' || value === 'KTV';
}

function categoryAttributeType(value: unknown): CategoryAttributeType {
  if (typeof value === 'boolean') {
    return 'boolean';
  }
  if (typeof value === 'number' && Number.isInteger(value)) {
    return 'integer';
  }
  return 'string';
}

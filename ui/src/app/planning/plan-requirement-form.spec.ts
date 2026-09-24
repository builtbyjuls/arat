import { describe, expect, it } from 'vitest';
import {
  createCandidateWindowForm,
  createCategoryAttributeForm,
  createMustHaveControl,
  createPlanRequest,
  createRequirementReplacementRequest,
  createRequirementForm,
  hydrateRequirementForm,
  instantToLocalDateTime,
  localDateTimeToOffset,
} from './plan-requirement-form';

describe('plan requirement form', () => {
  it('maps every creation field without candidate-window IDs or numeric money conversion', () => {
    const form = createRequirementForm();
    form.patchValue({
      title: 'Friday badminton',
      category: 'COURT',
      timeZone: 'Asia/Manila',
      area: { code: 'BGC', radiusKm: 5 },
      headcount: { minimum: 4, maximum: 10 },
      budgetEnabled: true,
      budget: { minimumAmount: '123.40', maximumAmount: '2500.00' },
      providerSafeNotes: 'Indoor court preferred.',
    });
    form.controls.candidateWindows.setControl(0, createCandidateWindowForm('2027-01-09T09:00', '2027-01-09T11:00'));
    form.controls.mustHaves.push(createMustHaveControl('parking'));
    form.controls.mustHaves.push(createMustHaveControl('shower'));
    const booleanAttribute = createCategoryAttributeForm();
    booleanAttribute.setValue({ key: 'hasParking', type: 'boolean', value: 'true' });
    const integerAttribute = createCategoryAttributeForm();
    integerAttribute.setValue({ key: 'courtCount', type: 'integer', value: '2' });
    form.controls.categoryAttributes.push(booleanAttribute);
    form.controls.categoryAttributes.push(integerAttribute);

    expect(form.valid).toBe(true);
    expect(createPlanRequest(form)).toEqual({
      title: 'Friday badminton',
      category: 'COURT',
      timeZone: 'Asia/Manila',
      candidateWindows: [{
        startAt: '2027-01-09T09:00:00+08:00',
        endAt: '2027-01-09T11:00:00+08:00',
      }],
      area: { code: 'BGC', radiusKm: 5 },
      headcount: { minimum: 4, maximum: 10 },
      budget: { currency: 'PHP', minimumAmount: '123.40', maximumAmount: '2500.00' },
      mustHaves: ['parking', 'shower'],
      providerSafeNotes: 'Indoor court preferred.',
      categoryAttributes: { hasParking: true, courtCount: 2 },
    });
    expect('id' in createPlanRequest(form).candidateWindows[0]).toBe(false);
  });

  it('omits the optional budget and provider notes without weakening required fields', () => {
    const form = validForm();
    form.patchValue({ budgetEnabled: false, providerSafeNotes: '' });

    const request = createPlanRequest(form);

    expect(request.budget).toBeUndefined();
    expect(request.providerSafeNotes).toBeUndefined();
    expect(request.mustHaves).toEqual([]);
    expect(request.categoryAttributes).toEqual({});
  });

  it('rejects invalid intervals, bounded ranges, and duplicate unique values', () => {
    const form = validForm();
    form.controls.candidateWindows.setControl(0, createCandidateWindowForm('2027-01-09T11:00', '2027-01-09T09:00'));
    form.controls.area.patchValue({ radiusKm: 101 });
    form.controls.headcount.patchValue({ minimum: 11, maximum: 10 });
    form.controls.budgetEnabled.setValue(true);
    form.controls.budget.setValue({ minimumAmount: '2500.01', maximumAmount: '2500.00' });
    form.controls.mustHaves.push(createMustHaveControl('parking'));
    form.controls.mustHaves.push(createMustHaveControl(' parking '));
    const first = createCategoryAttributeForm();
    const second = createCategoryAttributeForm();
    first.setValue({ key: 'courtCount', type: 'integer', value: '2' });
    second.setValue({ key: 'courtCount', type: 'string', value: 'two' });
    form.controls.categoryAttributes.push(first);
    form.controls.categoryAttributes.push(second);
    form.updateValueAndValidity();

    expect(form.controls.candidateWindows.at(0).hasError('invalidInterval')).toBe(true);
    expect(form.controls.area.controls.radiusKm.hasError('max')).toBe(true);
    expect(form.controls.headcount.hasError('invalidRange')).toBe(true);
    expect(form.hasError('invalidBudgetRange')).toBe(true);
    expect(form.controls.mustHaves.hasError('duplicate')).toBe(true);
    expect(form.controls.categoryAttributes.hasError('duplicate')).toBe(true);
  });

  it('rejects fractional radius and headcount values before mapping', () => {
    const form = validForm();
    form.controls.area.controls.radiusKm.setValue(1.5);
    form.controls.headcount.setValue({ minimum: 1.5, maximum: 2.5 });

    expect(form.controls.area.controls.radiusKm.hasError('integer')).toBe(true);
    expect(form.controls.headcount.controls.minimum.hasError('integer')).toBe(true);
    expect(form.controls.headcount.controls.maximum.hasError('integer')).toBe(true);
    expect(form.valid).toBe(false);
  });

  it('converts local date-time intent with non-hour IANA offsets', () => {
    expect(localDateTimeToOffset('2027-01-09T09:30', 'Asia/Kathmandu'))
      .toBe('2027-01-09T09:30:00+05:45');
    expect(localDateTimeToOffset('2027-01-09T09:30', 'UTC'))
      .toBe('2027-01-09T09:30:00+00:00');
  });

  it('hydrates and round-trips existing requirements with stable IDs and exact strings', () => {
    const form = createRequirementForm();
    hydrateRequirementForm(form, {
      planId: 'plan-1',
      title: 'Friday badminton',
      state: 'COLLABORATING',
      version: 7,
      requirements: {
        category: 'COURT',
        timeZone: 'Asia/Manila',
        candidateWindows: [
          { id: 'window-1', startAt: '2027-01-09T01:00:00Z', endAt: '2027-01-09T03:00:00Z' },
          { id: 'window-2', startAt: '2027-01-10T05:30:00Z', endAt: '2027-01-10T07:30:00Z' },
        ],
        area: { code: 'BGC', radiusKm: 5 },
        headcount: { minimum: 4, maximum: 10 },
        budget: { currency: 'PHP', minimumAmount: '0.00', maximumAmount: '2500.00' },
        mustHaves: ['parking', 'shower'],
        providerSafeNotes: 'Indoor court preferred.',
        categoryAttributes: { hasParking: true, courtCount: 2, surface: 'wood' },
      },
    });
    form.controls.budget.controls.minimumAmount.setValue('123.40');
    form.controls.candidateWindows.push(createCandidateWindowForm(
      '2027-01-11T09:00',
      '2027-01-11T11:00',
    ));

    const request = createRequirementReplacementRequest(form);

    expect(request.title).toBe('Friday badminton');
    expect(request.timeZone).toBe('Asia/Manila');
    expect(request.budget).toEqual({
      currency: 'PHP', minimumAmount: '123.40', maximumAmount: '2500.00',
    });
    expect(request.mustHaves).toEqual(['parking', 'shower']);
    expect(request.providerSafeNotes).toBe('Indoor court preferred.');
    expect(request.categoryAttributes).toEqual({ hasParking: true, courtCount: 2, surface: 'wood' });
    expect(request.candidateWindows).toEqual([
      { id: 'window-1', startAt: '2027-01-09T01:00:00Z', endAt: '2027-01-09T03:00:00Z' },
      { id: 'window-2', startAt: '2027-01-10T05:30:00Z', endAt: '2027-01-10T07:30:00Z' },
      { startAt: '2027-01-11T09:00:00+08:00', endAt: '2027-01-11T11:00:00+08:00' },
    ]);
  });

  it('formats API instants in the named plan zone instead of the browser zone', () => {
    expect(instantToLocalDateTime('2027-01-09T01:30:00Z', 'Asia/Manila'))
      .toBe('2027-01-09T09:30');
    expect(instantToLocalDateTime('2027-01-09T01:30:00Z', 'Asia/Kathmandu'))
      .toBe('2027-01-09T07:15');
  });

  it('keeps a null budget absent and preserves untouched DST-fold instants exactly', () => {
    const form = createRequirementForm();
    hydrateRequirementForm(form, {
      planId: 'plan-1',
      title: 'Late fall gathering',
      state: 'COLLABORATING',
      version: 4,
      requirements: {
        category: 'GROUP_DINING',
        timeZone: 'America/New_York',
        candidateWindows: [
          {
            id: 'window-fold',
            startAt: '2027-11-07T05:45:12.123456Z',
            endAt: '2027-11-07T06:15:34.654321Z',
          },
          {
            id: 'window-sub-minute',
            startAt: '2027-11-07T12:00:10.111111Z',
            endAt: '2027-11-07T12:00:20.222222Z',
          },
        ],
        area: { code: 'NYC', radiusKm: 4 },
        headcount: { minimum: 4, maximum: 8 },
        budget: null as never,
        mustHaves: [],
        categoryAttributes: {},
      },
    });
    form.controls.title.setValue('Updated title only');

    const request = createRequirementReplacementRequest(form);

    expect(form.controls.budgetEnabled.value).toBe(false);
    expect(form.valid).toBe(true);
    expect(request.budget).toBeUndefined();
    expect(request.candidateWindows).toEqual([
      {
        id: 'window-fold',
        startAt: '2027-11-07T05:45:12.123456Z',
        endAt: '2027-11-07T06:15:34.654321Z',
      },
      {
        id: 'window-sub-minute',
        startAt: '2027-11-07T12:00:10.111111Z',
        endAt: '2027-11-07T12:00:20.222222Z',
      },
    ]);
  });
});

function validForm() {
  const form = createRequirementForm();
  form.patchValue({
    title: 'Friday badminton',
    timeZone: 'Asia/Manila',
    area: { code: 'BGC', radiusKm: 5 },
    headcount: { minimum: 4, maximum: 10 },
  });
  form.controls.candidateWindows.setControl(0, createCandidateWindowForm('2027-01-09T09:00', '2027-01-09T11:00'));
  return form;
}

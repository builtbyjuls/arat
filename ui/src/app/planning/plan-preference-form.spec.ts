import { describe, expect, it } from 'vitest';
import {
  createPreferenceForm,
  createPreferenceRequest,
  hydratePreferenceForm,
  togglePreferenceWindow,
  updatePreferenceValidation,
} from './plan-preference-form';

describe('plan preference form', () => {
  it('maps exact PHP strings, ranks, and selected windows without numeric conversion', () => {
    const form = createPreferenceForm();
    form.patchValue({
      attendance: 'JOINING', guestCount: 1, personalBudgetEnabled: true,
      personalBudgetAmount: '500.00', rankedPreferences: 'indoor court\nparking',
      privateNote: 'I can bring a shuttlecock.',
    });
    togglePreferenceWindow(form, 'window-1', true);
    togglePreferenceWindow(form, 'window-2', true);

    expect(form.valid).toBe(true);
    expect(createPreferenceRequest(form, 7)).toEqual({
      basisPlanVersion: 7, attendance: 'JOINING', guestCount: 1,
      selectedWindowIds: ['window-1', 'window-2'],
      personalBudget: { currency: 'PHP', amount: '500.00' },
      rankedPreferences: ['indoor court', 'parking'],
      privateNote: 'I can bring a shuttlecock.',
    });
  });

  it('requires zero guests when not joining and rejects invalid exact money or ranks', () => {
    const form = createPreferenceForm();
    form.patchValue({
      attendance: 'NOT_JOINING', guestCount: 1, personalBudgetEnabled: true,
      personalBudgetAmount: '500', rankedPreferences: 'parking\n parking ',
    });

    expect(form.hasError('notJoiningGuests')).toBe(true);
    expect(form.controls.personalBudgetAmount.hasError('money')).toBe(true);
    expect(form.controls.rankedPreferences.hasError('rankedPreferences')).toBe(true);
  });

  it('revalidates optional personal budget when toggled and enforces the exact maximum', () => {
    const form = createPreferenceForm();
    form.controls.personalBudgetEnabled.setValue(true);
    expect(form.controls.personalBudgetAmount.hasError('money')).toBe(true);

    form.controls.personalBudgetAmount.setValue('1000000.99');
    updatePreferenceValidation(form);
    expect(form.controls.personalBudgetAmount.hasError('money')).toBe(true);

    form.controls.personalBudgetAmount.setValue('1000000.00');
    updatePreferenceValidation(form);
    expect(form.controls.personalBudgetAmount.valid).toBe(true);

    form.controls.personalBudgetEnabled.setValue(false);
    expect(form.controls.personalBudgetAmount.valid).toBe(true);
  });

  it('hydrates an existing preference and removes an unchecked window', () => {
    const form = createPreferenceForm();
    hydratePreferenceForm(form, {
      accountId: 'member-1', attendance: 'AVAILABLE', guestCount: 0,
      selectedWindowIds: ['window-1', 'window-2'], basisPlanVersion: 7, version: 3,
      personalBudget: { currency: 'PHP', amount: '123.40' },
      rankedPreferences: ['parking'], privateNote: 'Near the station.',
    });
    togglePreferenceWindow(form, 'window-1', false);

    expect(form.controls.personalBudgetAmount.value).toBe('123.40');
    expect(createPreferenceRequest(form, 8)).toEqual(expect.objectContaining({
      basisPlanVersion: 8, selectedWindowIds: ['window-2'], rankedPreferences: ['parking'],
    }));
  });
});

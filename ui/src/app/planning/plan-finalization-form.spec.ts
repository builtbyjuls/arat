import { describe, expect, it } from 'vitest';
import {
  createFinalizeRequirementsRequest,
  createPlanFinalizationForm,
  validatePlanFinalization,
} from './plan-finalization-form';

describe('plan finalization form', () => {
  it('submits the plan-zone deadline with an explicit offset', () => {
    const form = createPlanFinalizationForm();
    form.setValue({ candidateWindowId: 'window-1', offerDeadline: '2027-01-08T10:00' });
    const plan = planDetail();

    expect(validatePlanFinalization(form, plan, new Date('2027-01-08T00:00:00Z'))).toBe(true);
    expect(createFinalizeRequirementsRequest(form, plan)).toEqual({
      candidateWindowId: 'window-1',
      offerDeadline: '2027-01-08T10:00:00+08:00',
    });
  });

  it('rejects an inactive window, elapsed deadline, and deadline at the chosen start', () => {
    const form = createPlanFinalizationForm();
    const plan = planDetail();

    form.setValue({ candidateWindowId: 'retired-window', offerDeadline: '2027-01-08T10:00' });
    expect(validatePlanFinalization(form, plan, new Date('2027-01-08T03:00:00Z'))).toBe(false);
    expect(form.controls.candidateWindowId.hasError('activeWindow')).toBe(true);
    expect(form.controls.offerDeadline.hasError('notFuture')).toBe(true);

    form.setValue({ candidateWindowId: 'window-1', offerDeadline: '2027-01-09T09:00' });
    expect(validatePlanFinalization(form, plan, new Date('2027-01-08T00:00:00Z'))).toBe(false);
    expect(form.controls.offerDeadline.hasError('notBeforeStart')).toBe(true);
  });

  it('rejects a local date-time that does not exist in the plan time zone', () => {
    const form = createPlanFinalizationForm();
    const plan = planDetail('America/New_York', '2027-03-14T08:00:00Z');
    form.setValue({ candidateWindowId: 'window-1', offerDeadline: '2027-03-14T02:30' });

    expect(validatePlanFinalization(form, plan, new Date('2027-03-13T00:00:00Z'))).toBe(false);
    expect(form.controls.offerDeadline.hasError('invalidLocalDateTime')).toBe(true);
  });
});

function planDetail(timeZone = 'Asia/Manila', startAt = '2027-01-09T01:00:00Z') {
  return {
    planId: 'plan-1',
    title: 'Friday badminton',
    state: 'COLLABORATING',
    version: 7,
    requirements: {
      timeZone,
      candidateWindows: [{ id: 'window-1', startAt, endAt: '2027-01-09T03:00:00Z' }],
    },
  };
}

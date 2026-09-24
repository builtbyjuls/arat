import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { BehaviorSubject, of } from 'rxjs';
import { signal } from '@angular/core';
import { ProviderDetailComponent } from './provider-detail.component';
import { ProviderDetailService } from './provider-detail.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';
import { ProviderVerificationSubmissionService } from './provider-verification-submission.service';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';

describe('ProviderDetailComponent', () => {
  it.each(['UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED', 'SUSPENDED'])('renders %s with staff-private fields', async (verificationStatus) => {
    const detail = fakeDetail('ready', verificationStatus);
    const fixture = await createComponent(detail);
    const text = fixture.nativeElement.textContent;
    expect(detail.load).toHaveBeenCalledWith('provider-1');
    expect(text).toContain(verificationStatus);
    expect(text).toContain('Admin');
    expect(text).toContain('2');
    expect(text).toContain('BGC');
  });

  it('uses one safe unavailable view for a private 404', async () => {
    const fixture = await createComponent(fakeDetail('not-found', 'UNVERIFIED'));
    expect(fixture.nativeElement.textContent).toContain('This provider organization is unavailable.');
    expect(fixture.nativeElement.textContent).not.toContain('BGC Courts');
  });

  it('shows profile editing only for the server-returned administrator role', async () => {
    const admin = fakeDetail('ready', 'UNVERIFIED', 'ADMIN');
    const adminFixture = await createComponent(admin);
    expect(adminFixture.nativeElement.textContent).toContain('Edit provider profile');

    const staff = fakeDetail('ready', 'UNVERIFIED', 'STAFF');
    const staffFixture = await createComponent(staff);
    expect(staffFixture.nativeElement.textContent).not.toContain('Edit provider profile');
  });

  it('uses exact server verification states with precise status guidance', async () => {
    const expected = {
      UNVERIFIED: 'This provider has not submitted evidence for operator review.',
      PENDING: 'Pending review is not verified eligibility.',
      VERIFIED: 'Verification is not a safety, licensing, quality, inventory, or availability guarantee.',
      REJECTED: 'An administrator may submit a new evidence set for review.',
      SUSPENDED: 'Verification submission is unavailable while suspended.',
    };
    for (const [status, guidance] of Object.entries(expected)) {
      const fixture = await createComponent(fakeDetail('ready', status));
      expect(fixture.nativeElement.textContent).toContain(`${status}`);
      expect(fixture.nativeElement.textContent).toContain(guidance);
    }
  });

  it('validates plain-text evidence references before creating a submission intent', async () => {
    const verification = fakeVerificationSubmission();
    const fixture = await createComponent(fakeDetail('ready', 'UNVERIFIED'), fakeProfileEdit(), verification);
    const component = fixture.componentInstance;

    component.submitVerification();
    fixture.detectChanges();
    expect(verification.submit).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter 1 to 256 printable ASCII characters.');

    component.verificationForm.controls.evidenceReferences.at(0).setValue('reference-1');
    component.addEvidenceReference();
    component.verificationForm.controls.evidenceReferences.at(1).setValue('reference-1');
    component.submitVerification();
    expect(verification.submit).not.toHaveBeenCalled();

    component.verificationForm.controls.evidenceReferences.at(1).setValue('permit-2');
    component.submitVerification();
    expect(verification.submit).toHaveBeenCalledWith('provider-1', {
      evidenceReferences: ['reference-1', 'permit-2'],
    });
  });

  it('refreshes provider detail after submission success without claiming verification', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const verification = fakeVerificationSubmission();
    verification.state.set('succeeded');
    verification.result.set({
      etag: '"3"', exactRetry: false,
      submission: { providerId: 'provider-1', submissionId: 'submission-1', providerVersion: 3, evidenceCount: 1 },
    });
    verification.submit.mockResolvedValue(verification.result());
    const fixture = await createComponent(detail, fakeProfileEdit(), verification);
    fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).setValue('reference-1');

    fixture.componentInstance.submitVerification();
    await Promise.resolve();
    await Promise.resolve();

    expect(detail.load).toHaveBeenLastCalledWith('provider-1');
    expect(fixture.componentInstance.verificationForm.controls.evidenceReferences.length).toBe(1);
    expect(fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).value).toBe('');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('accepted for pending operator review');
    expect(fixture.nativeElement.textContent).toContain('This is not verified eligibility.');
  });

  it('explains a source-state conflict and refreshes without resubmitting the stale form', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const verification = fakeVerificationSubmission();
    verification.state.set('conflict');
    verification.problemCode.set('INVALID_PROVIDER_STATE');
    const fixture = await createComponent(detail, fakeProfileEdit(), verification);

    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('The provider state no longer accepts this verification submission.');
    fixture.componentInstance.refreshAfterVerificationConflict();
    await Promise.resolve();
    expect(detail.load).toHaveBeenLastCalledWith('provider-1');
    expect(verification.submit).not.toHaveBeenCalled();
  });

  it('clears unsent evidence after an actor change', async () => {
    const fixture = await createComponent(fakeDetail('ready', 'UNVERIFIED'));
    fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).setValue('reference-1');

    TestBed.inject(ActorScopeResetService).reset();

    expect(fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).value).toBe('');
  });

  it('clears evidence when the provider route changes before it can be submitted to another provider', async () => {
    const parameters = new BehaviorSubject(convertToParamMap({ providerId: 'provider-1' }));
    const route = { paramMap: parameters, snapshot: { paramMap: convertToParamMap({ providerId: 'provider-1' }) } };
    const verification = fakeVerificationSubmission();
    const fixture = await createComponent(fakeDetail('ready', 'UNVERIFIED'), fakeProfileEdit(), verification, route);
    fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).setValue('provider-one-reference');

    route.snapshot.paramMap = convertToParamMap({ providerId: 'provider-2' });
    parameters.next(route.snapshot.paramMap);

    expect(fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).value).toBe('');
    fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).setValue('provider-two-reference');
    fixture.componentInstance.submitVerification();
    expect(verification.submit).toHaveBeenCalledWith('provider-2', { evidenceReferences: ['provider-two-reference'] });
  });

  it('refreshes authoritative provider state after a successful response lacks its ETag', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const verification = fakeVerificationSubmission();
    verification.state.set('refresh-required');
    verification.problemCode.set('MISSING_PROVIDER_ETAG');
    const fixture = await createComponent(detail, fakeProfileEdit(), verification);
    fixture.componentInstance.verificationForm.controls.evidenceReferences.at(0).setValue('reference-1');

    fixture.componentInstance.submitVerification();
    await Promise.resolve();

    expect(detail.load).toHaveBeenLastCalledWith('provider-1');
  });

  it('completes refresh-required recovery after a successful manual reload', async () => {
    const detail = fakeDetail('ready', 'REJECTED');
    detail.load
      .mockImplementationOnce(() => { detail.state.set('error'); return Promise.resolve(); })
      .mockImplementationOnce(() => { detail.state.set('ready'); return Promise.resolve(); });
    const verification = fakeVerificationSubmission();
    verification.state.set('refresh-required');
    const fixture = await createComponent(detail, fakeProfileEdit(), verification);

    fixture.componentInstance.reload();
    await fixture.whenStable();

    expect(verification.completeRefresh).toHaveBeenCalledWith('provider-1');
  });

  it('keeps exact field mapping and preserves a stale edit for deliberate reapply', async () => {
    const profileEdit = fakeProfileEdit();
    const fixture = await createComponent(fakeDetail('ready', 'UNVERIFIED'), profileEdit);
    const component = fixture.componentInstance;
    component.profileForm.setValue({
      displayName: 'Updated Courts',
      supportedCategories: ['KTV', 'GROUP_DINING'],
      serviceAreaCodes: 'BGC\n MAKATI ',
    });

    component.submitProfile();
    expect(profileEdit.replace).toHaveBeenCalledWith('provider-1', {
      displayName: 'Updated Courts',
      supportedCategories: ['KTV', 'GROUP_DINING'],
      serviceAreaCodes: ['BGC', 'MAKATI'],
    }, '"2"');

    profileEdit.state.set('conflict');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Your edits are still in this form.');
    component.refreshAfterConflict();
    await Promise.resolve();
    expect(profileEdit.markRefreshed).toHaveBeenCalledWith('"2"');
    expect(profileEdit.reapply).not.toHaveBeenCalled();
  });

  it('blocks invalid input and presents the server forbidden-role response', async () => {
    const profileEdit = fakeProfileEdit();
    const fixture = await createComponent(fakeDetail('ready', 'UNVERIFIED'), profileEdit);
    const component = fixture.componentInstance;
    component.profileForm.controls.displayName.setValue('   ');
    component.submitProfile();
    expect(profileEdit.replace).not.toHaveBeenCalled();

    profileEdit.state.set('error');
    profileEdit.problemCode.set('FORBIDDEN_ROLE');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Only an active provider administrator can replace this profile.');
  });

  it('uses a routine reload to synchronize the form with the current provider and ETag', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const fixture = await createComponent(detail);
    detail.provider.set(currentProvider('Current Courts', ['KTV'], ['MAKATI'], 3));
    detail.etag.set('"3"');

    fixture.componentInstance.reload();
    await Promise.resolve();

    expect(fixture.componentInstance.profileForm.getRawValue()).toEqual({
      displayName: 'Current Courts', supportedCategories: ['KTV'], serviceAreaCodes: 'MAKATI',
    });
  });

  it('discards retained conflict values when keeping the current profile', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const profileEdit = fakeProfileEdit();
    const fixture = await createComponent(detail, profileEdit);
    const component = fixture.componentInstance;
    component.profileForm.setValue({ displayName: 'Rejected Courts', supportedCategories: ['GROUP_DINING'], serviceAreaCodes: 'BGC' });
    detail.provider.set(currentProvider('Current Courts', ['KTV'], ['MAKATI'], 3));
    detail.etag.set('"3"');
    profileEdit.state.set('reapply-ready');

    component.keepCurrentProfile();
    component.submitProfile();

    expect(component.profileForm.getRawValue()).toEqual({ displayName: 'Current Courts', supportedCategories: ['KTV'], serviceAreaCodes: 'MAKATI' });
    expect(profileEdit.replace).toHaveBeenCalledWith('provider-1', {
      displayName: 'Current Courts', supportedCategories: ['KTV'], serviceAreaCodes: ['MAKATI'],
    }, '"3"');
  });

  it('discards the detail when profile replacement reports a private 404', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const profileEdit = fakeProfileEdit();
    profileEdit.replace.mockResolvedValue(null);
    profileEdit.state.set('error');
    profileEdit.problemCode.set('PRIVATE_RESOURCE_NOT_FOUND');
    const fixture = await createComponent(detail, profileEdit);

    fixture.componentInstance.submitProfile();
    await Promise.resolve();

    expect(detail.markUnavailable).toHaveBeenCalled();
  });

  it('retains the conflict draft through a failed refresh and a successful reload retry', async () => {
    const detail = fakeDetail('ready', 'UNVERIFIED');
    const profileEdit = fakeProfileEdit();
    const fixture = await createComponent(detail, profileEdit);
    const component = fixture.componentInstance;
    component.profileForm.setValue({ displayName: 'Retained Courts', supportedCategories: ['GROUP_DINING'], serviceAreaCodes: 'BGC' });
    profileEdit.state.set('conflict');
    detail.load.mockImplementationOnce(() => {
      detail.state.set('error');
      return Promise.resolve();
    });

    component.refreshAfterConflict();
    await Promise.resolve();
    expect(component.profileForm.getRawValue().displayName).toBe('Retained Courts');
    expect(profileEdit.state()).toBe('conflict');

    detail.provider.set(currentProvider('Current Courts', ['KTV'], ['MAKATI'], 3));
    detail.etag.set('"3"');
    detail.state.set('ready');
    component.reload();
    await Promise.resolve();
    await Promise.resolve();

    expect(component.profileForm.getRawValue()).toEqual({ displayName: 'Retained Courts', supportedCategories: ['GROUP_DINING'], serviceAreaCodes: 'BGC' });
    expect(profileEdit.state()).toBe('reapply-ready');
    expect(profileEdit.markRefreshed).toHaveBeenLastCalledWith('"3"');
  });
});

async function createComponent(
  detail: ReturnType<typeof fakeDetail>,
  profileEdit = fakeProfileEdit(),
  verificationSubmission = fakeVerificationSubmission(),
  route = { paramMap: of(convertToParamMap({ providerId: 'provider-1' })), snapshot: { paramMap: convertToParamMap({ providerId: 'provider-1' }) } },
) {
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [ProviderDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ProviderDetailService, useValue: detail },
      { provide: ProviderProfileEditService, useValue: profileEdit },
      { provide: ProviderVerificationSubmissionService, useValue: verificationSubmission },
      { provide: ActivatedRoute, useValue: route },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderDetailComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeDetail(state: 'not-found' | 'ready', verificationStatus: string, callerStaffRole = 'ADMIN') {
  return {
    provider: signal(state === 'ready' ? { providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole, verificationStatus, supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 2 } : null),
    state: signal<string>(state), correlationId: signal<string | null>(null), etag: signal<string | null>('"2"'), load: vi.fn().mockResolvedValue(undefined), applyProfile: vi.fn(), markUnavailable: vi.fn(),
  };
}

function fakeProfileEdit() {
  const state = signal<string>('idle');
  return {
    state,
    correlationId: signal<string | null>(null),
    problemCode: signal<string | null>(null),
    violationFor: vi.fn(() => null),
    useProvider: vi.fn(), releaseProvider: vi.fn(), replace: vi.fn().mockResolvedValue(null), retry: vi.fn().mockResolvedValue(null), markRefreshed: vi.fn(() => state.set('reapply-ready')), reapply: vi.fn().mockResolvedValue(null), dismiss: vi.fn(() => state.set('idle')),
  };
}

function fakeVerificationSubmission() {
  return {
    state: signal<string>('idle'),
    correlationId: signal<string | null>(null),
    problemCode: signal<string | null>(null),
    result: signal<unknown | null>(null),
    violationFor: vi.fn(() => null),
    useProvider: vi.fn(), releaseProvider: vi.fn(), submit: vi.fn().mockResolvedValue(null), retry: vi.fn().mockResolvedValue(null), dismiss: vi.fn(), completeRefresh: vi.fn(),
  };
}

function currentProvider(displayName: string, supportedCategories: string[], serviceAreaCodes: string[], version: number) {
  return {
    providerId: 'provider-1', displayName, callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories, serviceAreaCodes, version,
  };
}

import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { ProviderDetailComponent } from './provider-detail.component';
import { ProviderDetailService } from './provider-detail.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';

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

async function createComponent(detail: ReturnType<typeof fakeDetail>, profileEdit = fakeProfileEdit()) {
  TestBed.resetTestingModule();
  await TestBed.configureTestingModule({
    imports: [ProviderDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ProviderDetailService, useValue: detail },
      { provide: ProviderProfileEditService, useValue: profileEdit },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ providerId: 'provider-1' })), snapshot: { paramMap: convertToParamMap({ providerId: 'provider-1' }) } } },
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

function currentProvider(displayName: string, supportedCategories: string[], serviceAreaCodes: string[], version: number) {
  return {
    providerId: 'provider-1', displayName, callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories, serviceAreaCodes, version,
  };
}

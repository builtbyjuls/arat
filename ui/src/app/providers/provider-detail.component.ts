import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateProviderRequest, ProviderRepresentation } from './provider-api.service';
import { ProviderDetailService } from './provider-detail.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';
import { ProviderVerificationSubmissionService } from './provider-verification-submission.service';

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-provider-detail',
  styleUrl: './provider-detail.component.scss',
  templateUrl: './provider-detail.component.html',
})
export class ProviderDetailComponent implements OnDestroy, OnInit {
  readonly providerDetail = inject(ProviderDetailService);
  readonly profileEdit = inject(ProviderProfileEditService);
  readonly verificationSubmission = inject(ProviderVerificationSubmissionService);
  readonly #route = inject(ActivatedRoute);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly profileForm = new FormGroup({
    displayName: new FormControl('', { nonNullable: true, validators: [trimmedRequired(), Validators.maxLength(120)] }),
    supportedCategories: new FormControl<ProviderCategory[]>([], { nonNullable: true, validators: [nonEmptyUniqueCategories()] }),
    serviceAreaCodes: new FormControl('', { nonNullable: true, validators: [areaCodesValid()] }),
  });
  readonly verificationForm = new FormGroup({
    evidenceReferences: new FormArray([evidenceReferenceControl()], { validators: [uniqueEvidenceReferences()] }),
  });
  #formProviderId: string | null = null;
  #verificationFormProviderId: string | null = null;
  #unregisterScopeReset: (() => void) | null = null;

  ngOnInit(): void {
    this.#unregisterScopeReset = this.#scopeReset.register(() => this.resetForm());
    this.#route.paramMap.subscribe((params) => {
      const providerId = params.get('providerId');
      if (providerId !== null) {
        if (this.#verificationFormProviderId !== providerId) {
          this.resetVerificationForm();
          this.#verificationFormProviderId = providerId;
        }
        this.profileEdit.useProvider(providerId);
        this.verificationSubmission.useProvider(providerId);
        void this.loadProvider(providerId);
      }
    });
  }

  ngOnDestroy(): void {
    this.#unregisterScopeReset?.();
    this.#unregisterScopeReset = null;
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId !== null) this.profileEdit.releaseProvider(providerId);
    if (providerId !== null) this.verificationSubmission.releaseProvider(providerId);
  }

  reload(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId === null) return;
    const preserveDraft = this.profileEdit.state() === 'conflict' || this.profileEdit.state() === 'reapply-ready';
    void this.loadProvider(providerId, preserveDraft).then(() => {
      const etag = this.providerDetail.etag();
      if (preserveDraft && this.providerDetail.state() === 'ready' && etag !== null) {
        this.profileEdit.markRefreshed(etag);
      }
      if (this.providerDetail.state() === 'ready') this.verificationSubmission.completeRefresh(providerId);
    });
  }

  submitProfile(): void {
    if (this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      return;
    }
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    const etag = this.providerDetail.etag();
    if (providerId === null) return;
    if (etag === null) {
      void this.providerDetail.load(providerId);
      return;
    }
    void this.profileEdit.replace(providerId, this.profileRequest(), etag)
      .then((result) => this.handleProfileResult(result));
  }

  retryProfile(): void {
    void this.profileEdit.retry().then((result) => this.handleProfileResult(result));
  }

  refreshAfterConflict(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId === null) return;
    void this.providerDetail.load(providerId).then(() => {
      const etag = this.providerDetail.etag();
      if (this.providerDetail.state() === 'ready' && etag !== null) {
        this.profileEdit.markRefreshed(etag);
      }
    });
  }

  reapplyProfile(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId === null || this.profileForm.invalid) {
      this.profileForm.markAllAsTouched();
      return;
    }
    void this.profileEdit.reapply(providerId, this.profileRequest())
      .then((result) => this.handleProfileResult(result));
  }

  dismissProfileError(): void {
    this.profileEdit.dismiss();
  }

  addEvidenceReference(): void {
    if (this.verificationForm.controls.evidenceReferences.length < 10) {
      this.verificationForm.controls.evidenceReferences.push(evidenceReferenceControl());
    }
  }

  removeEvidenceReference(index: number): void {
    const references = this.verificationForm.controls.evidenceReferences;
    if (references.length > 1) {
      references.removeAt(index);
      references.markAsTouched();
    }
  }

  submitVerification(): void {
    if (this.verificationForm.invalid) {
      this.verificationForm.markAllAsTouched();
      return;
    }
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId === null) return;
    void this.verificationSubmission.submit(providerId, this.verificationRequest())
      .then((result) => this.handleVerificationResult(providerId, result));
  }

  retryVerification(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId === null) return;
    void this.verificationSubmission.retry(providerId)
      .then((result) => this.handleVerificationResult(providerId, result));
  }

  refreshAfterVerificationConflict(): void {
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId !== null) void this.loadProvider(providerId, true);
  }

  dismissVerificationOutcome(): void {
    this.verificationSubmission.dismiss();
  }

  keepCurrentProfile(): void {
    const provider = this.providerDetail.provider();
    if (provider !== null) this.hydrateProfileForm(provider);
    this.profileEdit.dismiss();
  }

  toggleCategory(category: string, checked: boolean): void {
    const selected = this.profileForm.controls.supportedCategories.value;
    const typedCategory = category as ProviderCategory;
    this.profileForm.controls.supportedCategories.setValue(checked
      ? [...selected, typedCategory]
      : selected.filter((item) => item !== typedCategory));
    this.profileForm.controls.supportedCategories.markAsTouched();
  }

  isCategorySelected(category: string): boolean {
    return this.profileForm.controls.supportedCategories.value.includes(category as ProviderCategory);
  }

  roleLabel(role: string | undefined): string { return role === 'ADMIN' ? 'Admin' : 'Staff'; }

  verificationStatusLabel(status: string | undefined): string {
    return {
      UNVERIFIED: 'Unverified',
      PENDING: 'Pending review',
      VERIFIED: 'Verified',
      REJECTED: 'Rejected',
      SUSPENDED: 'Suspended',
    }[status ?? ''] ?? 'Unknown verification state';
  }

  verificationStatusGuidance(status: string | undefined): string {
    return {
      UNVERIFIED: 'This provider has not submitted evidence for operator review.',
      PENDING: 'Evidence is pending operator review. Pending review is not verified eligibility.',
      VERIFIED: 'An operator reviewed the configured evidence. Verification is not a safety, licensing, quality, inventory, or availability guarantee.',
      REJECTED: 'The last submission was rejected. An administrator may submit a new evidence set for review.',
      SUSPENDED: 'This provider is suspended. Verification submission is unavailable while suspended.',
    }[status ?? ''] ?? 'The server returned an unknown verification state.';
  }

  canSubmitVerification(provider: { callerStaffRole?: string; verificationStatus?: string }): boolean {
    return provider.callerStaffRole === 'ADMIN'
      && (provider.verificationStatus === 'UNVERIFIED' || provider.verificationStatus === 'REJECTED');
  }

  private async loadProvider(providerId: string, preserveDraft = false): Promise<void> {
    await this.providerDetail.load(providerId);
    const provider = this.providerDetail.provider();
    if (this.providerDetail.state() === 'ready' && provider !== null && (!preserveDraft || this.#formProviderId !== providerId)) {
      this.hydrateProfileForm(provider);
      this.#formProviderId = providerId;
    }
  }

  private handleProfileResult(result: { body: ProviderRepresentation | null; etag: string | null } | null): void {
    if (result === null || result.body === null || result.etag === null) {
      this.handleProfileFailure();
      return;
    }
    this.providerDetail.applyProfile(result.body, result.etag);
    this.hydrateProfileForm(result.body);
  }

  private handleProfileFailure(): void {
    if (this.profileEdit.state() !== 'error') return;
    if (this.profileEdit.problemCode() === 'PRIVATE_RESOURCE_NOT_FOUND') {
      this.providerDetail.markUnavailable();
    } else if (this.profileEdit.problemCode() === 'FORBIDDEN_ROLE') {
      const providerId = this.#route.snapshot.paramMap.get('providerId');
      if (providerId !== null) void this.loadProvider(providerId, false);
    }
  }

  private handleVerificationResult(
    providerId: string,
    result: { etag: string } | null,
  ): void {
    if (result === null) {
      this.handleVerificationFailure();
      return;
    }
    this.resetVerificationForm();
    void this.loadProvider(providerId, true);
  }

  private handleVerificationFailure(): void {
    if (this.verificationSubmission.state() === 'refresh-required') {
      const providerId = this.#route.snapshot.paramMap.get('providerId');
      if (providerId !== null) {
        void this.loadProvider(providerId, true).then(() => {
          if (this.providerDetail.state() === 'ready') this.verificationSubmission.completeRefresh(providerId);
        });
      }
      return;
    }
    if (this.verificationSubmission.state() !== 'error') return;
    if (this.verificationSubmission.problemCode() === 'PRIVATE_RESOURCE_NOT_FOUND') {
      this.providerDetail.markUnavailable();
    } else if (this.verificationSubmission.problemCode() === 'FORBIDDEN_ROLE') {
      const providerId = this.#route.snapshot.paramMap.get('providerId');
      if (providerId !== null) void this.loadProvider(providerId, true);
    }
  }

  private profileRequest(): CreateProviderRequest {
    return {
      displayName: this.profileForm.controls.displayName.value.trim(),
      supportedCategories: this.profileForm.controls.supportedCategories.value,
      serviceAreaCodes: this.profileForm.controls.serviceAreaCodes.value.split('\n').map((code) => code.trim()).filter(Boolean),
    };
  }

  private verificationRequest() {
    return {
      evidenceReferences: this.verificationForm.controls.evidenceReferences.controls
        .map((control) => control.value),
    };
  }

  private hydrateProfileForm(provider: Pick<ProviderRepresentation, 'displayName' | 'serviceAreaCodes' | 'supportedCategories'>): void {
    this.profileForm.reset({
      displayName: provider.displayName ?? '',
      supportedCategories: (provider.supportedCategories ?? []) as ProviderCategory[],
      serviceAreaCodes: (provider.serviceAreaCodes ?? []).join('\n'),
    });
  }

  private resetForm(): void {
    this.#formProviderId = null;
    this.#verificationFormProviderId = null;
    this.profileForm.reset({ displayName: '', supportedCategories: [], serviceAreaCodes: '' });
    this.resetVerificationForm();
  }

  private resetVerificationForm(): void {
    this.verificationForm.setControl('evidenceReferences', new FormArray([evidenceReferenceControl()], {
      validators: [uniqueEvidenceReferences()],
    }));
  }
}

type ProviderCategory = 'COURT' | 'KTV' | 'GROUP_DINING';

function trimmedRequired(): ValidatorFn {
  return (control) => control.value.trim().length > 0 ? null : { required: true };
}

function nonEmptyUniqueCategories(): ValidatorFn {
  return (control) => {
    const categories = control.value as readonly string[];
    return categories.length >= 1 && categories.length <= 3 && new Set(categories).size === categories.length
      ? null
      : { categories: true };
  };
}

function areaCodesValid(): ValidatorFn {
  return (control) => {
    const codes = control.value.split('\n').map((code: string) => code.trim()).filter(Boolean);
    return codes.length >= 1 && codes.length <= 20 && codes.every((code: string) => code.length <= 64) && new Set(codes).size === codes.length
      ? null
      : { areaCodes: true };
  };
}

function evidenceReferenceControl(): FormControl<string> {
  return new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(256), printableAscii()],
  });
}

function printableAscii(): ValidatorFn {
  return (control) => /^[\x20-\x7e]+$/.test(control.value) ? null : { printableAscii: true };
}

function uniqueEvidenceReferences(): ValidatorFn {
  return (control) => {
    const references = control.value as readonly string[];
    return references.length >= 1 && references.length <= 10 && new Set(references).size === references.length
      ? null
      : { evidenceReferences: true };
  };
}

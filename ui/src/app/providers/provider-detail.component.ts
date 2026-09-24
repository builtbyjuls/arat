import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { CreateProviderRequest, ProviderRepresentation } from './provider-api.service';
import { ProviderDetailService } from './provider-detail.service';
import { ProviderProfileEditService } from './provider-profile-edit.service';

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-provider-detail',
  styleUrl: './provider-detail.component.scss',
  templateUrl: './provider-detail.component.html',
})
export class ProviderDetailComponent implements OnDestroy, OnInit {
  readonly providerDetail = inject(ProviderDetailService);
  readonly profileEdit = inject(ProviderProfileEditService);
  readonly #route = inject(ActivatedRoute);
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly profileForm = new FormGroup({
    displayName: new FormControl('', { nonNullable: true, validators: [trimmedRequired(), Validators.maxLength(120)] }),
    supportedCategories: new FormControl<ProviderCategory[]>([], { nonNullable: true, validators: [nonEmptyUniqueCategories()] }),
    serviceAreaCodes: new FormControl('', { nonNullable: true, validators: [areaCodesValid()] }),
  });
  #formProviderId: string | null = null;
  #unregisterScopeReset: (() => void) | null = null;

  ngOnInit(): void {
    this.#unregisterScopeReset = this.#scopeReset.register(() => this.resetForm());
    this.#route.paramMap.subscribe((params) => {
      const providerId = params.get('providerId');
      if (providerId !== null) {
        this.profileEdit.useProvider(providerId);
        void this.loadProvider(providerId);
      }
    });
  }

  ngOnDestroy(): void {
    this.#unregisterScopeReset?.();
    this.#unregisterScopeReset = null;
    const providerId = this.#route.snapshot.paramMap.get('providerId');
    if (providerId !== null) this.profileEdit.releaseProvider(providerId);
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

  private profileRequest(): CreateProviderRequest {
    return {
      displayName: this.profileForm.controls.displayName.value.trim(),
      supportedCategories: this.profileForm.controls.supportedCategories.value,
      serviceAreaCodes: this.profileForm.controls.serviceAreaCodes.value.split('\n').map((code) => code.trim()).filter(Boolean),
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
    this.profileForm.reset({ displayName: '', supportedCategories: [], serviceAreaCodes: '' });
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

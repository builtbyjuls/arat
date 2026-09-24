import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CreateProviderRequest } from './provider-api.service';
import { ProviderCreationService } from './provider-creation.service';
import { ProviderIndexService } from './provider-index.service';

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-provider-index',
  styleUrl: './provider-index.component.scss',
  templateUrl: './provider-index.component.html',
})
export class ProviderIndexComponent implements OnDestroy, OnInit {
  readonly providerIndex = inject(ProviderIndexService);
  readonly providerCreation = inject(ProviderCreationService);
  readonly #router = inject(Router);
  readonly createForm = new FormGroup({
    displayName: new FormControl('', { nonNullable: true, validators: [trimmedRequired(), Validators.maxLength(120)] }),
    supportedCategories: new FormControl<('COURT' | 'KTV' | 'GROUP_DINING')[]>([], { nonNullable: true, validators: [nonEmptyUnique()] }),
    serviceAreaCodes: new FormControl('', { nonNullable: true, validators: [areaCodesValid()] }),
  });

  ngOnInit(): void {
    void this.providerIndex.refresh();
  }

  ngOnDestroy(): void {
    this.#destroyed = true;
    this.providerCreation.dismissError();
  }

  refresh(): void {
    void this.providerIndex.refresh();
  }

  loadMore(): void {
    void this.providerIndex.loadMore();
  }

  submitCreate(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    void this.providerCreation.create(this.createRequest()).then((result) => this.handleCreateResult(result?.location, result !== null));
  }

  retryCreate(): void {
    void this.providerCreation.retry().then((result) => this.handleCreateResult(result?.location, result !== null));
  }

  dismissCreateError(): void { this.providerCreation.dismissError(); }

  toggleCategory(category: string, checked: boolean): void {
    const selected = this.createForm.controls.supportedCategories.value;
    const typedCategory = category as 'COURT' | 'KTV' | 'GROUP_DINING';
    this.createForm.controls.supportedCategories.setValue(checked
      ? [...selected, typedCategory]
      : selected.filter((item) => item !== typedCategory));
    this.createForm.controls.supportedCategories.markAsTouched();
  }

  isCategorySelected(category: string): boolean {
    return this.createForm.controls.supportedCategories.value.includes(category as 'COURT' | 'KTV' | 'GROUP_DINING');
  }

  roleLabel(role: string | undefined): string {
    return role === 'ADMIN' ? 'Admin' : 'Staff';
  }

  private createRequest(): CreateProviderRequest {
    return {
      displayName: this.createForm.controls.displayName.value.trim(),
      supportedCategories: this.createForm.controls.supportedCategories.value,
      serviceAreaCodes: this.createForm.controls.serviceAreaCodes.value.split('\n').map((code) => code.trim()).filter(Boolean),
    };
  }

  private handleCreateResult(location: string | null | undefined, completed: boolean): void {
    if (this.#destroyed) return;
    const providerId = providerIdFromLocation(location);
    if (providerId !== null) {
      void this.providerIndex.refresh();
      void this.#router.navigate(['/providers', providerId]);
    } else if (completed) this.providerCreation.reportMissingLocation();
  }

  #destroyed = false;
}

function trimmedRequired(): ValidatorFn {
  return (control) => control.value.trim().length > 0 ? null : { required: true };
}

function nonEmptyUnique(): ValidatorFn {
  return (control) => {
    const value = control.value as readonly string[];
    return value.length >= 1 && value.length <= 3 && new Set(value).size === value.length ? null : { categories: true };
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

function providerIdFromLocation(location: string | null | undefined): string | null {
  if (location === null || location === undefined) return null;
  try {
    const path = new URL(location, globalThis.location.origin).pathname;
    const match = /^\/api\/v1\/providers\/([^/]+)$/.exec(path);
    return match === null ? null : decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

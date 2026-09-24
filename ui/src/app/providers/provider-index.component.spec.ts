import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ProviderIndexComponent } from './provider-index.component';
import { ProviderCreationService } from './provider-creation.service';
import { ProviderIndexService } from './provider-index.service';

describe('ProviderIndexComponent', () => {
  it('renders scoped roles and canonical provider links after loading', async () => {
    const providerIndex = fakeProviderIndex({
      providers: [{ providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'ADMIN', verificationStatus: 'UNVERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'] }],
      state: 'ready',
    });
    const fixture = await createComponent(providerIndex);

    expect(providerIndex.refresh).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('Admin');
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.provider-card')?.getAttribute('href'))
      .toBe('/providers/provider-1');
  });

  it('renders empty and safe failure states', async () => {
    const providerIndex = fakeProviderIndex({ state: 'empty' });
    const fixture = await createComponent(providerIndex);
    expect(fixture.nativeElement.textContent).toContain('No provider workspaces yet');

    providerIndex.state.set('error');
    providerIndex.correlationId.set('correlation-1');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Provider workspaces are unavailable');
    expect(fixture.nativeElement.textContent).toContain('correlation-1');
    expect(fixture.nativeElement.textContent).not.toContain('server internals');
  });

  it('keeps paging keyboard-accessible with practical touch targets', async () => {
    const providerIndex = fakeProviderIndex({
      providers: [{ providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'STAFF', verificationStatus: 'VERIFIED', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'] }],
      nextCursor: 'next-page', state: 'ready',
    });
    const fixture = await createComponent(providerIndex);
    const loadMore = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.load-more');

    loadMore?.click();

    expect(getComputedStyle(fixture.nativeElement.querySelector('button')).minHeight).toBe('44px');
    expect(providerIndex.loadMore).toHaveBeenCalledOnce();
  });

  it('validates mobile provider fields, creates once, refreshes the index, and navigates from Location', async () => {
    const providerIndex = fakeProviderIndex({ state: 'empty' });
    const creation = fakeProviderCreation('/api/v1/providers/provider-1');
    const fixture = await createComponent(providerIndex, creation);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    fixture.componentInstance.submitCreate();
    expect(creation.create).not.toHaveBeenCalled();
    fixture.componentInstance.createForm.controls.displayName.setValue(' BGC Courts ');
    fixture.componentInstance.toggleCategory('COURT', true);
    fixture.componentInstance.createForm.controls.serviceAreaCodes.setValue(' BGC \n MAKATI ');
    fixture.componentInstance.submitCreate();
    await Promise.resolve();

    expect(creation.create).toHaveBeenCalledWith({ displayName: 'BGC Courts', supportedCategories: ['COURT'], serviceAreaCodes: ['BGC', 'MAKATI'] });
    expect(providerIndex.refresh).toHaveBeenCalledTimes(2);
    expect(navigate).toHaveBeenCalledWith(['/providers', 'provider-1']);
    expect(getComputedStyle(fixture.nativeElement.querySelector('#provider-display-name')).minHeight).toBe('44px');
  });

  it('ends an in-flight creation when leaving the provider form', async () => {
    const providerIndex = fakeProviderIndex({ state: 'empty' });
    let resolveCreate: (result: { location: string } | null) => void = () => undefined;
    const creation = fakeProviderCreation();
    creation.create.mockImplementation(() => new Promise((resolve) => { resolveCreate = resolve; }));
    const fixture = await createComponent(providerIndex, creation);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture.componentInstance.createForm.controls.displayName.setValue('BGC Courts');
    fixture.componentInstance.toggleCategory('COURT', true);
    fixture.componentInstance.createForm.controls.serviceAreaCodes.setValue('BGC');

    fixture.componentInstance.submitCreate();
    fixture.destroy();
    resolveCreate({ location: '/api/v1/providers/provider-1' });
    await Promise.resolve();

    expect(creation.dismissError).toHaveBeenCalledOnce();
    expect(navigate).not.toHaveBeenCalled();
  });
});

async function createComponent(providerIndex: ReturnType<typeof fakeProviderIndex>, providerCreation = fakeProviderCreation()) {
  await TestBed.configureTestingModule({
    imports: [ProviderIndexComponent],
    providers: [provideRouter([]), { provide: ProviderIndexService, useValue: providerIndex }, { provide: ProviderCreationService, useValue: providerCreation }],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderIndexComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeProviderCreation(location: string | null = null) {
  return {
    state: signal<'error' | 'idle' | 'network-error' | 'submitting'>('idle'),
    correlationId: signal<string | null>(null),
    problemCode: signal<string | null>(null),
    violationFor: vi.fn((_field: string): string | null => null),
    create: vi.fn().mockResolvedValue(location === null ? null : { location }),
    retry: vi.fn().mockResolvedValue(null),
    dismissError: vi.fn(),
    reportMissingLocation: vi.fn(),
  };
}

function fakeProviderIndex(initial: {
  readonly providers?: readonly { readonly providerId: string; readonly displayName: string; readonly callerStaffRole: string; readonly verificationStatus: string; readonly supportedCategories: readonly string[]; readonly serviceAreaCodes: readonly string[] }[];
  readonly nextCursor?: string | null;
  readonly state: 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing';
}) {
  return {
    providers: signal(initial.providers ?? []), nextCursor: signal(initial.nextCursor ?? null), state: signal(initial.state), correlationId: signal<string | null>(null),
    refresh: vi.fn().mockResolvedValue(undefined), loadMore: vi.fn().mockResolvedValue(undefined),
  };
}

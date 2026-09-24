import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { ProviderIndexComponent } from './provider-index.component';
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
});

async function createComponent(providerIndex: ReturnType<typeof fakeProviderIndex>) {
  await TestBed.configureTestingModule({
    imports: [ProviderIndexComponent],
    providers: [provideRouter([]), { provide: ProviderIndexService, useValue: providerIndex }],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderIndexComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
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

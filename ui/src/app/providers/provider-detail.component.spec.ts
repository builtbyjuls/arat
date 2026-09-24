import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { signal } from '@angular/core';
import { ProviderDetailComponent } from './provider-detail.component';
import { ProviderDetailService } from './provider-detail.service';

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
});

async function createComponent(detail: ReturnType<typeof fakeDetail>) {
  await TestBed.configureTestingModule({
    imports: [ProviderDetailComponent],
    providers: [
      provideRouter([]),
      { provide: ProviderDetailService, useValue: detail },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ providerId: 'provider-1' })), snapshot: { paramMap: convertToParamMap({ providerId: 'provider-1' }) } } },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderDetailComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeDetail(state: 'not-found' | 'ready', verificationStatus: string) {
  return {
    provider: signal(state === 'ready' ? { providerId: 'provider-1', displayName: 'BGC Courts', callerStaffRole: 'ADMIN', verificationStatus, supportedCategories: ['COURT'], serviceAreaCodes: ['BGC'], version: 2 } : null),
    state: signal(state), correlationId: signal<string | null>(null), etag: signal<string | null>('"2"'), load: vi.fn().mockResolvedValue(undefined),
  };
}

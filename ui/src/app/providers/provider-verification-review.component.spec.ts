import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PendingProviderVerification } from './provider-operations-api.service';
import { ProviderVerificationReviewComponent } from './provider-verification-review.component';
import {
  ProviderVerificationReviewService,
  VerificationDecisionResult,
} from './provider-verification-review.service';

describe('ProviderVerificationReviewComponent', () => {
  it('renders the bounded provider summary, exact submission, version, and evidence as plain text', async () => {
    const reviews = fakeReviews();
    const fixture = await createComponent(reviews);
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('BGC Courts');
    expect(root.textContent).toContain('submission-1');
    expect(root.textContent).toContain('Current provider version');
    expect(root.textContent).toContain('7');
    expect(root.textContent).toContain('https://evidence.example/reference');
    expect(root.querySelector('.evidence-list a')).toBeNull();
    expect(root.querySelector('.evidence-list')?.innerHTML).not.toContain('href');
  });

  it('focuses distinct accept and reject confirmations and restores the trigger on cancel', async () => {
    const fixture = await createComponent(fakeReviews());
    const root = fixture.nativeElement as HTMLElement;
    const acceptTrigger = root.querySelector<HTMLButtonElement>('.accept-action');
    acceptTrigger?.focus();
    acceptTrigger?.click();
    await fixture.whenStable();

    expect(root.querySelector('[role="alertdialog"]')?.textContent).toContain('Accept this exact submission?');
    expect(document.activeElement?.textContent).toContain('Confirm accept');
    root.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button')[1]?.click();
    await fixture.whenStable();
    expect(document.activeElement).toBe(acceptTrigger);

    root.querySelector<HTMLButtonElement>('.reject-action')?.click();
    await fixture.whenStable();
    expect(root.querySelector('[role="alertdialog"]')?.textContent).toContain('Reject this exact submission?');
    expect(root.querySelector('[role="alertdialog"]')?.textContent).toContain('Accept and reject are separate decisions');
  });

  it('moves focus to the decision status after confirmation renders', async () => {
    const reviews = fakeReviews();
    reviews.decide.mockImplementation(() => {
      reviews.decisionState.set('submitting');
      return new Promise(() => undefined);
    });
    const fixture = await createComponent(reviews);
    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.accept-action')?.click();
    await fixture.whenStable();

    root.querySelector<HTMLButtonElement>('[role="alertdialog"] button[type="submit"]')?.click();
    await fixture.whenStable();

    expect(document.activeElement).toBe(root.querySelector('[data-decision-status]'));
  });

  it('submits the exact reviewed row, separate decision, and trimmed optional reason', async () => {
    const reviews = fakeReviews();
    const fixture = await createComponent(reviews);
    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.reject-action')?.click();
    fixture.detectChanges();
    fixture.componentInstance.noteForm.controls.note.setValue('  Registration did not match  ');

    fixture.componentInstance.confirmDecision();
    await Promise.resolve();

    expect(reviews.decide).toHaveBeenCalledWith(
      expect.objectContaining({ submissionId: 'submission-1', providerId: 'provider-1' }),
      'REJECT',
      'Registration did not match',
    );
  });

  it('suppresses duplicate confirmation while a decision is in flight', async () => {
    const reviews = fakeReviews();
    let finishDecision: (value: null) => void = () => undefined;
    reviews.decide.mockImplementation(() => {
      reviews.decisionState.set('submitting');
      return new Promise((resolve) => { finishDecision = resolve; });
    });
    const fixture = await createComponent(reviews);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.accept-action')?.click();
    fixture.detectChanges();

    fixture.componentInstance.confirmDecision();
    fixture.componentInstance.confirmDecision();

    expect(reviews.decide).toHaveBeenCalledOnce();
    finishDecision(null);
    await Promise.resolve();
  });

  it('ignores a delayed decision completion after route teardown', async () => {
    const reviews = fakeReviews();
    let finishDecision: (value: null) => void = () => undefined;
    reviews.decide.mockImplementation(() => new Promise((resolve) => { finishDecision = resolve; }));
    const fixture = await createComponent(reviews);
    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.accept-action')?.click();
    await fixture.whenStable();
    root.querySelector<HTMLButtonElement>('[role="alertdialog"] button[type="submit"]')?.click();

    fixture.destroy();
    finishDecision(null);
    await Promise.resolve();

    expect(reviews.decide).toHaveBeenCalledOnce();
  });

  it('ignores a delayed retry completion after route teardown', async () => {
    const reviews = fakeReviews();
    let finishRetry: (value: null) => void = () => undefined;
    reviews.decisionState.set('network-error');
    reviews.retryDecision.mockImplementation(() => new Promise((resolve) => { finishRetry = resolve; }));
    const fixture = await createComponent(reviews);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.decision-state button')?.click();

    fixture.destroy();
    finishRetry(null);
    await Promise.resolve();

    expect(reviews.retryDecision).toHaveBeenCalledOnce();
  });

  it('clears open confirmation and note state immediately on actor switch', async () => {
    const fixture = await createComponent(fakeReviews());
    const root = fixture.nativeElement as HTMLElement;
    root.querySelector<HTMLButtonElement>('.accept-action')?.click();
    fixture.detectChanges();
    fixture.componentInstance.noteForm.controls.note.setValue('Private operator note');

    TestBed.inject(ActorScopeResetService).reset();
    fixture.detectChanges();

    expect(root.querySelector('[role="alertdialog"]')).toBeNull();
    expect(fixture.componentInstance.noteForm.controls.note.value).toBe('');
  });

  it('shows non-operator access without queue content', async () => {
    const reviews = fakeReviews();
    reviews.queueState.set('forbidden');
    reviews.items.set([]);
    const fixture = await createComponent(reviews);

    expect(fixture.nativeElement.textContent).toContain('Operator access required');
    expect(fixture.nativeElement.textContent).not.toContain('registration-123');
  });

  it('labels exact replay metadata as a historical decision result', async () => {
    const reviews = fakeReviews();
    reviews.decisionState.set('succeeded');
    reviews.decisionResult.set({
      exactRetry: true,
      decision: {
        decisionId: 'decision-1',
        providerId: 'provider-1',
        submissionId: 'older-submission',
        decision: 'ACCEPT',
        providerVersion: 8,
        eligibilityVersion: 2,
        verificationStatus: 'VERIFIED',
      },
    });
    const fixture = await createComponent(reviews);

    expect(fixture.nativeElement.textContent).toContain('Status recorded by this decision: VERIFIED');
    expect(fixture.nativeElement.textContent).toContain('historical result');
    expect(fixture.nativeElement.textContent).toContain('not a fresh provider profile read');
    expect(fixture.nativeElement.textContent).not.toContain('Current provider status');
  });
});

async function createComponent(reviews: ReturnType<typeof fakeReviews>) {
  await TestBed.configureTestingModule({
    imports: [ProviderVerificationReviewComponent],
    providers: [{ provide: ProviderVerificationReviewService, useValue: reviews }],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProviderVerificationReviewComponent);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeReviews() {
  return {
    items: signal<readonly PendingProviderVerification[]>([submission()]),
    nextCursor: signal<string | null>(null),
    queueState: signal<'empty' | 'error' | 'forbidden' | 'loading' | 'loading-more' | 'ready' | 'refreshing'>('ready'),
    queueCorrelationId: signal<string | null>(null),
    decisionState: signal<'conflict' | 'error' | 'idle' | 'network-error' | 'submitting' | 'succeeded'>('idle'),
    decisionProblemCode: signal<string | null>(null),
    decisionCorrelationId: signal<string | null>(null),
    decisionResult: signal<VerificationDecisionResult | null>(null),
    refresh: vi.fn().mockResolvedValue(undefined),
    loadMore: vi.fn().mockResolvedValue(undefined),
    decide: vi.fn().mockResolvedValue(null),
    retryDecision: vi.fn().mockResolvedValue(null),
    dismissDecision: vi.fn(),
  };
}

function submission(): PendingProviderVerification {
  return {
    submissionId: 'submission-1',
    providerId: 'provider-1',
    providerVersion: 7,
    displayName: 'BGC Courts',
    verificationStatus: 'PENDING',
    supportedCategories: ['COURT'],
    serviceAreaCodes: ['BGC'],
    submittedAt: '2027-01-01T00:00:00Z',
    evidenceReferences: ['registration-123', 'https://evidence.example/reference'],
  };
}

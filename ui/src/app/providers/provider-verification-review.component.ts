import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnDestroy,
  OnInit,
  ViewChild,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { PendingProviderVerification } from './provider-operations-api.service';
import { ProviderVerificationReviewService } from './provider-verification-review.service';

interface VerificationReview {
  readonly decision: 'ACCEPT' | 'REJECT';
  readonly item: PendingProviderVerification;
}

@Component({
  imports: [ReactiveFormsModule],
  selector: 'app-provider-verification-review',
  styleUrl: './provider-verification-review.component.scss',
  templateUrl: './provider-verification-review.component.html',
})
export class ProviderVerificationReviewComponent implements OnDestroy, OnInit {
  readonly reviews = inject(ProviderVerificationReviewService);
  readonly review = signal<VerificationReview | null>(null);
  readonly noteForm = new FormGroup({
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] }),
  });
  readonly #scopeReset = inject(ActorScopeResetService);
  readonly #element = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly #injector = inject(Injector);
  readonly #destroyRef = inject(DestroyRef);
  @ViewChild('confirmDecisionButton') private confirmDecisionButton?: ElementRef<HTMLButtonElement>;
  #decisionTrigger: HTMLButtonElement | null = null;
  #unregisterScopeReset: (() => void) | null = null;

  ngOnInit(): void {
    this.#unregisterScopeReset = this.#scopeReset.register(() => this.clearReview(false));
    void this.reviews.refresh();
  }

  ngOnDestroy(): void {
    this.#unregisterScopeReset?.();
    this.#unregisterScopeReset = null;
    this.clearReview(false);
  }

  refresh(): void {
    this.clearReview(false);
    void this.reviews.refresh();
  }

  loadMore(): void {
    void this.reviews.loadMore();
  }

  openReview(
    item: PendingProviderVerification,
    decision: 'ACCEPT' | 'REJECT',
    event: Event,
  ): void {
    if (this.reviews.decisionState() !== 'idle') {
      return;
    }
    this.#decisionTrigger = event.currentTarget instanceof HTMLButtonElement
      ? event.currentTarget
      : null;
    this.noteForm.reset({ note: '' });
    this.review.set({ item, decision });
    this.afterRender(() => this.confirmDecisionButton?.nativeElement.focus());
  }

  cancelReview(): void {
    this.clearReview(true);
  }

  confirmDecision(): void {
    const review = this.review();
    if (review === null || this.noteForm.invalid || this.reviews.decisionState() !== 'idle') {
      this.noteForm.markAllAsTouched();
      return;
    }
    void this.reviews.decide(review.item, review.decision, optionalNote(this.noteForm.controls.note.value))
      .then((result) => this.handleDecisionResult(result !== null));
    this.afterRender(() => this.focusDecisionStatus());
  }

  retryDecision(): void {
    void this.reviews.retryDecision()
      .then((result) => this.handleDecisionResult(result !== null));
    this.afterRender(() => this.focusDecisionStatus());
  }

  dismissDecision(): void {
    const state = this.reviews.decisionState();
    const shouldRefresh = state === 'conflict' || state === 'error' || state === 'network-error';
    this.reviews.dismissDecision();
    this.clearReview(false);
    if (shouldRefresh) {
      void this.reviews.refresh();
    }
  }

  decisionLabel(decision: 'ACCEPT' | 'REJECT'): string {
    return decision === 'ACCEPT' ? 'Accept' : 'Reject';
  }

  private handleDecisionResult(succeeded: boolean): void {
    if (this.#destroyRef.destroyed) {
      return;
    }
    if (succeeded) {
      this.clearReview(false);
    } else if (this.reviews.decisionState() !== 'network-error') {
      this.clearReview(false);
    }
    this.afterRender(() => this.focusDecisionStatus());
  }

  private clearReview(restoreFocus: boolean): void {
    const trigger = this.#decisionTrigger;
    this.review.set(null);
    this.noteForm.reset({ note: '' });
    this.#decisionTrigger = null;
    if (restoreFocus) {
      this.afterRender(() => trigger?.focus());
    }
  }

  private afterRender(callback: () => void): void {
    if (this.#destroyRef.destroyed) {
      return;
    }
    afterNextRender(() => {
      if (!this.#destroyRef.destroyed) {
        callback();
      }
    }, { injector: this.#injector });
  }

  private focusDecisionStatus(): void {
    this.#element.nativeElement.querySelector<HTMLElement>('[data-decision-status]')?.focus();
  }
}

function optionalNote(value: string): string | undefined {
  const note = value.trim();
  return note.length === 0 ? undefined : note;
}

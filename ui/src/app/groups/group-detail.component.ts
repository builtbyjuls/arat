import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators, ValidatorFn } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GroupDetailService } from './group-detail.service';
import { GroupInvitationService } from './group-invitation.service';

const ACCOUNT_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const WHOLE_EXPIRY_HOURS: ValidatorFn = (control) => {
  const value = control.value;
  return value === null || (typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 168)
    ? null
    : { wholeExpiryHours: true };
};

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-group-detail',
  styleUrl: './group-detail.component.scss',
  templateUrl: './group-detail.component.html',
})
export class GroupDetailComponent implements OnDestroy, OnInit {
  readonly groupDetail = inject(GroupDetailService);
  readonly invitations = inject(GroupInvitationService);
  readonly #route = inject(ActivatedRoute);
  readonly #formBuilder = inject(FormBuilder);
  readonly copyState = signal<'idle' | 'success' | 'error'>('idle');
  readonly invitationForm = this.#formBuilder.group({
    inviteeAccountId: this.#formBuilder.nonNullable.control('', [Validators.required, Validators.pattern(ACCOUNT_ID_PATTERN)]),
    expiryHours: this.#formBuilder.control<number | null>(null, [WHOLE_EXPIRY_HOURS]),
  });

  ngOnInit(): void {
    this.#route.paramMap.subscribe((params) => {
      const groupId = params.get('groupId');
      if (groupId !== null) {
        this.invitations.clear();
        this.copyState.set('idle');
        void this.groupDetail.load(groupId);
      }
    });
  }

  ngOnDestroy(): void {
    this.invitations.clear();
  }

  reload(): void {
    const groupId = this.#route.snapshot.paramMap.get('groupId');
    if (groupId !== null) {
      void this.groupDetail.load(groupId);
    }
  }

  async createInvitation(): Promise<void> {
    if (this.invitationForm.invalid) {
      this.invitationForm.markAllAsTouched();
      return;
    }

    const groupId = this.#route.snapshot.paramMap.get('groupId');
    if (groupId === null) {
      return;
    }

    const { inviteeAccountId, expiryHours } = this.invitationForm.getRawValue();
    await this.invitations.create(groupId, {
      inviteeAccountId,
      ...(expiryHours === null ? {} : { expiryHours }),
    });
  }

  async copyInvitationToken(token: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(token);
      this.copyState.set('success');
    } catch {
      this.copyState.set('error');
    }
  }

  invitationErrorMessage(): string {
    switch (this.invitations.errorCode()) {
      case 'ALREADY_MEMBER':
        return 'This account is already an active member.';
      case 'IDEMPOTENCY_KEY_REUSED':
        return 'This invitation conflicts with an earlier request. Review the details and create a new invitation.';
      case 'INVITATION_ALREADY_PENDING':
        return 'This account already has a pending invitation.';
      case 'VALIDATION_FAILED':
        return this.hasInvitationViolation()
          ? 'The invitation was not accepted. Correct the highlighted fields and try again.'
          : 'The invitation was not accepted. Verify the account ID and expiry hours, then try again.';
      default:
        return 'The invitation could not be created. Try again.';
    }
  }

  private hasInvitationViolation(): boolean {
    return this.invitations.violationFor('inviteeAccountId') !== null
      || this.invitations.violationFor('expiryHours') !== null;
  }
}

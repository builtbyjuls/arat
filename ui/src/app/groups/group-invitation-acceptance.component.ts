import { Component, OnDestroy, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';
import { GroupInvitationAcceptanceService } from './group-invitation-acceptance.service';

@Component({
  imports: [ReactiveFormsModule],
  selector: 'app-group-invitation-acceptance',
  styleUrl: './group-invitation-acceptance.component.scss',
  templateUrl: './group-invitation-acceptance.component.html',
})
export class GroupInvitationAcceptanceComponent implements OnDestroy {
  readonly invitationAcceptance = inject(GroupInvitationAcceptanceService);
  readonly #router = inject(Router);
  readonly #unregisterScopeReset = inject(ActorScopeResetService).register(() => this.clearForm());
  readonly acceptanceForm = new FormGroup({
    token: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  ngOnDestroy(): void {
    this.#unregisterScopeReset();
    this.clearForm();
    this.invitationAcceptance.clear();
  }

  async accept(): Promise<void> {
    if (this.acceptanceForm.invalid) {
      this.acceptanceForm.markAllAsTouched();
      return;
    }

    const token = this.acceptanceForm.controls.token.value;
    try {
      const membership = await this.invitationAcceptance.accept(token);
      if (membership !== null && typeof membership.groupId === 'string' && membership.groupId.length > 0) {
        await this.#router.navigate(['/groups', membership.groupId]);
      }
    } finally {
      this.clearForm();
    }
  }

  clearForm(): void {
    this.acceptanceForm.reset({ token: '' });
  }

  startOver(): void {
    this.invitationAcceptance.clear();
    this.clearForm();
  }
}

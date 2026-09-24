import { Component, OnInit, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { CreateGroupRequest } from './group-api.service';
import { GroupCreationService } from './group-creation.service';
import { GroupIndexService } from './group-index.service';

@Component({
  imports: [ReactiveFormsModule, RouterLink],
  selector: 'app-group-index',
  styleUrl: './group-index.component.scss',
  templateUrl: './group-index.component.html',
})
export class GroupIndexComponent implements OnInit {
  readonly groupIndex = inject(GroupIndexService);
  readonly groupCreation = inject(GroupCreationService);
  readonly #router = inject(Router);
  readonly createForm = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [trimmedRequired(), Validators.maxLength(80)],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
  });

  ngOnInit(): void {
    void this.groupIndex.refresh();
  }

  refresh(): void {
    void this.groupIndex.refresh();
  }

  loadMore(): void {
    void this.groupIndex.loadMore();
  }

  submitCreate(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }

    const request = this.createRequest();
    void this.groupCreation.create(request).then((result) => this.handleCreateResult(result?.location, result !== null));
  }

  retryCreate(): void {
    void this.groupCreation.retry().then((result) => this.handleCreateResult(result?.location, result !== null));
  }

  dismissCreateError(): void {
    this.groupCreation.dismissError();
  }

  private createRequest(): CreateGroupRequest {
    const description = this.createForm.controls.description.value;
    return {
      name: this.createForm.controls.name.value.trim(),
      ...(description.length > 0 ? { description } : {}),
    };
  }

  private handleCreateResult(location: string | null | undefined, completed: boolean): void {
    const groupId = groupIdFromLocation(location);
    if (groupId !== null) {
      void this.#router.navigate(['/groups', groupId]);
    } else if (completed) {
      this.groupCreation.reportMissingLocation();
    }
  }
}

function trimmedRequired(): ValidatorFn {
  return (control) => control.value.trim().length > 0 ? null : { required: true };
}

function groupIdFromLocation(location: string | null | undefined): string | null {
  if (location === null || location === undefined) {
    return null;
  }

  try {
    const path = new URL(location, globalThis.location.origin).pathname;
    const match = /^\/api\/v1\/groups\/([^/]+)$/.exec(path);
    return match === null ? null : decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

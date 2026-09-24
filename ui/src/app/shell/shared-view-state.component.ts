import { AfterViewChecked, Component, ElementRef, ViewChild, input, output } from '@angular/core';

export type SharedViewState = 'loading' | 'empty' | 'error' | 'conflict' | 'confirmation';

interface ViewStateContent {
  readonly heading: string;
  readonly message: string;
  readonly live: 'assertive' | 'polite';
  readonly role: 'alert' | 'status';
}

const CONTENT: Readonly<Record<SharedViewState, ViewStateContent>> = {
  loading: {
    heading: 'Loading',
    message: 'Loading the latest information...',
    live: 'polite',
    role: 'status',
  },
  empty: {
    heading: 'Nothing to show yet',
    message: 'When information is available, it will appear here.',
    live: 'polite',
    role: 'status',
  },
  error: {
    heading: 'We could not load this information',
    message: 'Try again. If the problem continues, use the correlation ID from the response when contacting support.',
    live: 'assertive',
    role: 'alert',
  },
  conflict: {
    heading: 'This information changed',
    message: 'Refresh the latest information, then review and deliberately reapply your changes.',
    live: 'assertive',
    role: 'alert',
  },
  confirmation: {
    heading: 'Confirm this action',
    message: 'Review this action before continuing. You can cancel without making changes.',
    live: 'polite',
    role: 'status',
  },
};

@Component({
  selector: 'app-shared-view-state',
  styleUrl: './shared-view-state.component.scss',
  templateUrl: './shared-view-state.component.html',
})
export class SharedViewStateComponent implements AfterViewChecked {
  readonly correlationId = input<string | null>(null);
  readonly restoreFocusTarget = input<HTMLElement | null>(null);
  readonly state = input.required<SharedViewState>();
  readonly confirmed = output();
  readonly cancelled = output();
  @ViewChild('confirmButton') private confirmButton?: ElementRef<HTMLButtonElement>;
  #confirmationFocused = false;

  content(): ViewStateContent {
    return CONTENT[this.state()];
  }

  ngAfterViewChecked(): void {
    if (this.state() !== 'confirmation') {
      this.#confirmationFocused = false;
      return;
    }

    if (!this.#confirmationFocused) {
      this.#confirmationFocused = true;
      queueMicrotask(() => this.confirmButton?.nativeElement.focus());
    }
  }

  confirm(): void {
    this.confirmed.emit();
  }

  cancel(): void {
    this.cancelled.emit();
    this.restoreFocusTarget()?.focus();
  }
}

import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { SharedViewState, SharedViewStateComponent } from './shared-view-state.component';

describe('SharedViewStateComponent', () => {
  const expectedStates: ReadonlyArray<readonly [SharedViewState, string, string]> = [
    ['loading', 'status', 'Loading'],
    ['empty', 'status', 'Nothing to show yet'],
    ['error', 'alert', 'We could not load this information'],
    ['conflict', 'alert', 'This information changed'],
    ['confirmation', 'status', 'Confirm this action'],
  ];

  for (const [state, role, heading] of expectedStates) {
    it(`renders the ${state} state safely`, async () => {
      await TestBed.configureTestingModule({
        imports: [SharedViewStateComponent],
      }).compileComponents();

      const fixture = TestBed.createComponent(SharedViewStateComponent);
      fixture.componentRef.setInput('state', state);
      fixture.detectChanges();

      const stateElement = fixture.nativeElement.querySelector('[role]') as HTMLElement;
      expect(stateElement.getAttribute('role')).toBe(role);
      expect(stateElement.getAttribute('aria-live')).toBe(role === 'alert' ? 'assertive' : 'polite');
      expect(stateElement.textContent).toContain(heading);
      expect(stateElement.textContent).not.toContain('Exception');
    });
  }

  it('confirms explicitly and returns focus when cancelled', async () => {
    await TestBed.configureTestingModule({
      imports: [SharedViewStateComponent],
    }).compileComponents();

    const trigger = document.createElement('button');
    document.body.append(trigger);
    const fixture = TestBed.createComponent(SharedViewStateComponent);
    const confirmed = vi.fn();
    const cancelled = vi.fn();
    fixture.componentInstance.confirmed.subscribe(confirmed);
    fixture.componentInstance.cancelled.subscribe(cancelled);
    fixture.componentRef.setInput('state', 'confirmation');
    fixture.componentRef.setInput('restoreFocusTarget', trigger);
    fixture.detectChanges();
    await fixture.whenStable();

    const buttons = fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>;
    expect(buttons).toHaveLength(2);
    expect(document.activeElement).toBe(buttons[0]);
    buttons[0].click();
    buttons[1].click();
    expect(confirmed).toHaveBeenCalledOnce();
    expect(cancelled).toHaveBeenCalledOnce();
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });

  it('wraps a long correlation ID in a phone-width state pattern', async () => {
    await TestBed.configureTestingModule({
      imports: [SharedViewStateComponent],
    }).compileComponents();

    const fixture = TestBed.createComponent(SharedViewStateComponent);
    fixture.componentRef.setInput('state', 'error');
    fixture.componentRef.setInput('correlationId', 'a'.repeat(64));
    fixture.detectChanges();
    const stateElement = fixture.nativeElement.querySelector('.view-state') as HTMLElement;

    for (const width of [320, 360, 390]) {
      stateElement.style.width = `${width}px`;
      expect(getComputedStyle(stateElement).overflowWrap).toBe('anywhere');
      expect(stateElement.textContent).toContain('a'.repeat(64));
    }
  });
});

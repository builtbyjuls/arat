import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { GroupDetailComponent } from './group-detail.component';
import { GroupDetailService } from './group-detail.service';

describe('GroupDetailComponent', () => {
  it('renders a private unavailable state without raw problem details', async () => {
    const detail = fakeDetail({ state: 'not-found' });
    const fixture = await createComponent(detail);

    expect(fixture.nativeElement.textContent).toContain('This group is unavailable.');
    expect(fixture.nativeElement.textContent).not.toContain('Do not render');
  });

  it('renders members, caller role, and group-scoped plan navigation', async () => {
    const detail = fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
    } });
    const fixture = await createComponent(detail);

    expect(fixture.nativeElement.textContent).toContain('Your role: Organizer');
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.plans-link')?.getAttribute('href'))
      .toBe('/groups/group-1/plans');
  });
});

async function createComponent(detail: ReturnType<typeof fakeDetail>) {
  await TestBed.configureTestingModule({
    imports: [GroupDetailComponent],
    providers: [
      provideRouter([{ path: 'groups/:groupId', component: GroupDetailComponent }]),
      { provide: GroupDetailService, useValue: detail },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(GroupDetailComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeDetail(initial: {
  readonly state: 'error' | 'loading' | 'not-found' | 'ready';
  readonly group?: {
    readonly groupId: string;
    readonly name: string;
    readonly description?: string;
    readonly members: readonly { readonly accountId: string; readonly displayName: string; readonly role: string }[];
  };
}) {
  return {
    state: signal(initial.state),
    group: signal(initial.group ?? null),
    correlationId: signal<string | null>(null),
    callerRole: vi.fn(() => initial.group === undefined ? null : 'ORGANIZER'),
    load: vi.fn().mockResolvedValue(undefined),
  };
}

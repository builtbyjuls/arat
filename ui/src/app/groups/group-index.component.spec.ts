import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { GroupIndexComponent } from './group-index.component';
import { GroupIndexService } from './group-index.service';

describe('GroupIndexComponent', () => {
  it('renders role-aware group cards and refreshes on entry', async () => {
    const groupIndex = fakeGroupIndex({
      groups: [{
        groupId: 'group-1',
        name: 'Weekend crew',
        description: 'Saturday activities',
        role: 'ORGANIZER',
      }],
      state: 'ready',
    });
    const fixture = await createComponent(groupIndex);

    expect(groupIndex.refresh).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('Organizer');
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.group-card')?.getAttribute('href'))
      .toBe('/groups/group-1');
  });

  it('renders an empty state and safe failure state', async () => {
    const groupIndex = fakeGroupIndex({ state: 'empty' });
    const fixture = await createComponent(groupIndex);
    expect(fixture.nativeElement.textContent).toContain('No private groups yet');

    groupIndex.state.set('error');
    groupIndex.correlationId.set('correlation-1');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Groups are unavailable');
    expect(fixture.nativeElement.textContent).toContain('correlation-1');
    expect(fixture.nativeElement.textContent).not.toContain('server internals');
  });

  it('uses a practical touch target for refresh and requests the next page', async () => {
    const groupIndex = fakeGroupIndex({
      groups: [{ groupId: 'group-1', name: 'Weekend crew', role: 'MEMBER' }],
      nextCursor: 'next-page',
      state: 'ready',
    });
    const fixture = await createComponent(groupIndex);
    const loadMore = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.load-more');

    loadMore?.click();

    expect(getComputedStyle(fixture.nativeElement.querySelector('button')).minHeight).toBe('44px');
    expect(groupIndex.loadMore).toHaveBeenCalledOnce();
  });
});

async function createComponent(groupIndex: ReturnType<typeof fakeGroupIndex>) {
  await TestBed.configureTestingModule({
    imports: [GroupIndexComponent],
    providers: [
      provideRouter([]),
      { provide: GroupIndexService, useValue: groupIndex },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(GroupIndexComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeGroupIndex(initial: {
  readonly groups?: readonly {
    readonly groupId: string;
    readonly name: string;
    readonly description?: string;
    readonly role: string;
  }[];
  readonly nextCursor?: string | null;
  readonly state: 'empty' | 'error' | 'loading' | 'loading-more' | 'ready' | 'refreshing';
}) {
  return {
    groups: signal(initial.groups ?? []),
    nextCursor: signal(initial.nextCursor ?? null),
    state: signal(initial.state),
    correlationId: signal<string | null>(null),
    refresh: vi.fn().mockResolvedValue(undefined),
    loadMore: vi.fn().mockResolvedValue(undefined),
  };
}

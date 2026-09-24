import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { GroupIndexComponent } from './group-index.component';
import { GroupCreationService } from './group-creation.service';
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

  it('validates documented group field bounds before creating', async () => {
    const groupIndex = fakeGroupIndex({ state: 'empty' });
    const creation = fakeGroupCreation();
    const fixture = await createComponent(groupIndex, creation);

    fixture.componentInstance.submitCreate();
    fixture.detectChanges();

    expect(creation.create).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Enter a group name of at most 80 characters.');
    fixture.componentInstance.createForm.controls.name.setValue('x'.repeat(81));
    fixture.componentInstance.submitCreate();
    expect(creation.create).not.toHaveBeenCalled();
  });

  it('submits the group form once and navigates from the response Location', async () => {
    const groupIndex = fakeGroupIndex({ state: 'empty' });
    const creation = fakeGroupCreation({
      location: '/api/v1/groups/group-1',
    });
    const fixture = await createComponent(groupIndex, creation);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture.componentInstance.createForm.setValue({
      name: ' Weekend crew ',
      description: 'Saturday activities',
    });

    fixture.componentInstance.submitCreate();
    await Promise.resolve();

    expect(creation.create).toHaveBeenCalledWith({ name: 'Weekend crew', description: 'Saturday activities' });
    expect(navigate).toHaveBeenCalledWith(['/groups', 'group-1']);
  });

  it('renders safe validation and idempotency conflict states', async () => {
    const groupIndex = fakeGroupIndex({ state: 'empty' });
    const creation = fakeGroupCreation();
    creation.state.set('error');
    creation.problemCode.set('VALIDATION_FAILED');
    creation.violationFor.mockReturnValue('Name is required.');
    const fixture = await createComponent(groupIndex, creation);

    expect(fixture.nativeElement.textContent).toContain('Name is required.');
    expect(fixture.nativeElement.textContent).toContain('highlighted corrections');

    creation.problemCode.set('IDEMPOTENCY_KEY_REUSED');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('previously used idempotency key');
  });
});

async function createComponent(
  groupIndex: ReturnType<typeof fakeGroupIndex>,
  groupCreation = fakeGroupCreation(),
) {
  await TestBed.configureTestingModule({
    imports: [GroupIndexComponent],
    providers: [
      provideRouter([]),
      { provide: GroupIndexService, useValue: groupIndex },
      { provide: GroupCreationService, useValue: groupCreation },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(GroupIndexComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

function fakeGroupCreation(result: { readonly location: string | null } | null = null) {
  return {
    state: signal<'error' | 'idle' | 'network-error' | 'submitting'>('idle'),
    correlationId: signal<string | null>(null),
    problemCode: signal<string | null>(null),
    violationFor: vi.fn((_field: string): string | null => null),
    create: vi.fn().mockResolvedValue(result === null ? null : {
      body: { groupId: 'group-1', name: 'Weekend crew', version: 1 },
      status: 201,
      etag: '"1"',
      location: result.location,
      correlationId: null,
    }),
    retry: vi.fn().mockResolvedValue(null),
    dismissError: vi.fn(),
    reportMissingLocation: vi.fn(),
  };
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

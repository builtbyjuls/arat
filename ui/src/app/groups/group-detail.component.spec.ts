import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { GroupDetailComponent } from './group-detail.component';
import { GroupDetailService } from './group-detail.service';
import { GroupInvitationService } from './group-invitation.service';

describe('GroupDetailComponent', () => {
  it('renders a private unavailable state without raw problem details', async () => {
    const detail = fakeDetail({ state: 'not-found' });
    const fixture = await createComponent(detail);

    expect(fixture.nativeElement.textContent).toContain('This group is unavailable.');
    expect(fixture.nativeElement.textContent).not.toContain('Do not render');
    expect(fixture.nativeElement.textContent).not.toContain('Invite a member');
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

  it('shows keyboard-usable invitation controls only to organizers and validates expiry hours', async () => {
    const detail = fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
    } });
    const invitations = fakeInvitations();
    const fixture = await createComponent(detail, invitations);
    const root = fixture.nativeElement as HTMLElement;
    const expiry = root.querySelector<HTMLInputElement>('#invitation-expiry-hours');
    const form = root.querySelector<HTMLFormElement>('form');
    if (expiry === null || form === null) {
      throw new Error('Expected organizer invitation controls.');
    }

    expect(root.querySelector<HTMLButtonElement>('button[type="submit"]')?.textContent).toContain('Create invitation');
    expect(root.querySelector<HTMLInputElement>('#invitee-account-id')?.getAttribute('aria-describedby'))
      .toContain('invitee-account-id-error');
    expect(expiry?.getAttribute('min')).toBe('1');
    expect(expiry?.getAttribute('max')).toBe('168');

    expiry.value = '169';
    expiry.dispatchEvent(new Event('input'));
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(invitations.create).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Enter whole expiry hours from 1 to 168.');
  });

  it('does not render invitation controls for an active member', async () => {
    const detail = fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Bea Member', role: 'MEMBER' }],
    } }, 'MEMBER');
    const fixture = await createComponent(detail, fakeInvitations());

    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Invite a member');
  });

  it('omits a cleared optional expiry from the invitation command', async () => {
    const invitations = fakeInvitations();
    const fixture = await createComponent(fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
    } }), invitations);
    const root = fixture.nativeElement as HTMLElement;
    const accountId = root.querySelector<HTMLInputElement>('#invitee-account-id');
    const expiry = root.querySelector<HTMLInputElement>('#invitation-expiry-hours');
    const form = root.querySelector<HTMLFormElement>('form');
    if (accountId === null || expiry === null || form === null) {
      throw new Error('Expected organizer invitation controls.');
    }

    accountId.value = '10000000-0000-4000-8000-000000000002';
    accountId.dispatchEvent(new Event('input'));
    expiry.value = '24';
    expiry.dispatchEvent(new Event('input'));
    expect(fixture.componentInstance.invitationForm.controls.expiryHours.value).toBe(24);
    expiry.value = '';
    expiry.dispatchEvent(new Event('input'));
    expect(fixture.componentInstance.invitationForm.controls.expiryHours.value).toBeNull();
    form.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(invitations.create).toHaveBeenCalledWith('group-1', {
      inviteeAccountId: '10000000-0000-4000-8000-000000000002',
    });
  });

  it('clears the transient invitation token when the group route exits', async () => {
    const invitations = fakeInvitations();
    const fixture = await createComponent(fakeDetail({ state: 'loading' }), invitations);

    fixture.destroy();

    expect(invitations.clear).toHaveBeenCalled();
  });

  it('renders safe server validation feedback beside the matching invitation field', async () => {
    const invitations = fakeInvitations('error', 'VALIDATION_FAILED', [
      { field: 'expiryHours', message: 'must be at most 168' },
    ]);
    const fixture = await createComponent(fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
    } }), invitations);

    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('The invitation was not accepted. Correct the highlighted fields and try again.');
    expect((fixture.nativeElement as HTMLElement).querySelector('#invitation-expiry-hours-error')?.textContent)
      .toContain('must be at most 168');
  });

  it('gives actionable validation guidance when the server identifies no field', async () => {
    const fixture = await createComponent(fakeDetail({ state: 'ready', group: {
      groupId: 'group-1',
      name: 'Weekend crew',
      members: [{ accountId: 'actor-1', displayName: 'Ari Organizer', role: 'ORGANIZER' }],
    } }), fakeInvitations('error', 'VALIDATION_FAILED'));

    expect((fixture.nativeElement as HTMLElement).textContent)
      .toContain('The invitation was not accepted. Verify the account ID and expiry hours, then try again.');
  });
});

async function createComponent(
  detail: ReturnType<typeof fakeDetail>,
  invitations = fakeInvitations(),
) {
  await TestBed.configureTestingModule({
    imports: [GroupDetailComponent],
    providers: [
      provideRouter([{ path: 'groups/:groupId', component: GroupDetailComponent }]),
      {
        provide: ActivatedRoute,
        useValue: {
          paramMap: of(convertToParamMap({ groupId: 'group-1' })),
          snapshot: { paramMap: convertToParamMap({ groupId: 'group-1' }) },
        },
      },
      { provide: GroupDetailService, useValue: detail },
      { provide: GroupInvitationService, useValue: invitations },
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
}, role = 'ORGANIZER') {
  return {
    state: signal(initial.state),
    group: signal(initial.group ?? null),
    correlationId: signal<string | null>(null),
    callerRole: vi.fn(() => initial.group === undefined ? null : role),
    load: vi.fn().mockResolvedValue(undefined),
  };
}

function fakeInvitations(
  state: 'error' | 'idle' | 'retryable' | 'submitting' | 'success' = 'idle',
  errorCode: string | null = null,
  violations: readonly { readonly field: string; readonly message: string }[] = [],
) {
  return {
    state: signal(state),
    token: signal<string | null>(null),
    correlationId: signal<string | null>(null),
    errorCode: signal(errorCode),
    violationFor: vi.fn((field: string) => violations.find((violation) => violation.field === field) ?? null),
    clear: vi.fn(),
    create: vi.fn().mockResolvedValue(undefined),
    retry: vi.fn().mockResolvedValue(undefined),
  };
}

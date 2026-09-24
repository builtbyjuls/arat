import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import {
  ApiHttpClient,
  ApiHttpResult,
  IdempotentMutationIntent,
  InvitationAcceptanceIntent,
} from '../api/api-http-client';

export type CreateGroupRequest = components['schemas']['CreateGroupRequest'];
export type CreateInvitationRequest = components['schemas']['CreateInvitationRequest'];
export type GroupDetail = components['schemas']['GroupDetailRepresentation'];
export type GroupMembership = components['schemas']['GroupMembershipRepresentation'];
export type GroupPage = components['schemas']['GroupPageRepresentation'];
export type GroupRepresentation = components['schemas']['GroupRepresentation'];
export type GroupSummary = components['schemas']['GroupSummaryRepresentation'];
export type InvitationRepresentation = components['schemas']['InvitationRepresentation'];

const GROUP_PAGE_LIMIT = 20;

@Injectable({ providedIn: 'root' })
export class GroupApi {
  readonly #api = inject(ApiHttpClient);

  list(cursor: string | null = null): Observable<ApiHttpResult<GroupPage>> {
    let params = new HttpParams().set('limit', GROUP_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<GroupPage>('/groups', params);
  }

  createIntent(request: CreateGroupRequest): IdempotentMutationIntent<CreateGroupRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: '/groups',
      body: request,
    });
  }

  create(
    intent: IdempotentMutationIntent<CreateGroupRequest>,
  ): Observable<ApiHttpResult<GroupRepresentation>> {
    return this.#api.executeIdempotent(intent);
  }

  createInvitationIntent(
    groupId: string,
    request: CreateInvitationRequest,
  ): IdempotentMutationIntent<CreateInvitationRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/groups/${encodeURIComponent(groupId)}/invites`,
      body: request,
    });
  }

  createInvitation(
    intent: IdempotentMutationIntent<CreateInvitationRequest>,
  ): Observable<ApiHttpResult<InvitationRepresentation>> {
    return this.#api.executeIdempotent(intent);
  }

  beginInvitationAcceptance(token: string): Promise<InvitationAcceptanceIntent> {
    return this.#api.beginInvitationAcceptance(token);
  }

  acceptInvitation(
    intent: InvitationAcceptanceIntent,
    token: string,
  ): Observable<ApiHttpResult<GroupMembership>> {
    return this.#api.executeInvitationAcceptance(intent, token);
  }

  read(groupId: string): Observable<ApiHttpResult<GroupDetail>> {
    return this.#api.read<GroupDetail>(`/groups/${encodeURIComponent(groupId)}`);
  }
}

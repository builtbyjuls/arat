import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import {
  ApiHttpClient,
  ApiHttpResult,
  IdempotentMutationIntent,
  ifMatch,
  ifNoneMatch,
} from '../api/api-http-client';

export type CreatePlanRequest = components['schemas']['CreatePlanRequest'];
export type PlanDetail = components['schemas']['PlanDetailRepresentation'];
export type PlanPage = components['schemas']['PlanPageRepresentation'];
export type PlanRepresentation = components['schemas']['PlanRepresentation'];
export type PlanSummary = components['schemas']['PlanSummaryRepresentation'];
export type PlanRequirements = components['schemas']['PlanRequirements'];
export type RequirementReplacementRequest = components['schemas']['RequirementReplacementRequest'];
export type CreatePreferenceRequest = components['schemas']['CreatePreferenceRequest'];
export type PlanPreference = components['schemas']['PlanPreference'];
export type PreferenceCollection = components['schemas']['PreferenceCollectionRepresentation'];

const PLAN_PAGE_LIMIT = 20;

@Injectable({ providedIn: 'root' })
export class PlanApi {
  readonly #api = inject(ApiHttpClient);

  list(groupId: string, cursor: string | null = null): Observable<ApiHttpResult<PlanPage>> {
    let params = new HttpParams().set('limit', PLAN_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<PlanPage>(`/groups/${encodeURIComponent(groupId)}/plans`, params);
  }

  read(planId: string): Observable<ApiHttpResult<PlanDetail>> {
    return this.#api.read<PlanDetail>(`/plans/${encodeURIComponent(planId)}`);
  }

  replaceRequirements(
    planId: string,
    request: RequirementReplacementRequest,
    etag: string,
  ): Observable<ApiHttpResult<PlanRequirements>> {
    return this.#api.mutate<PlanRequirements, RequirementReplacementRequest>({
      method: 'PUT',
      path: `/plans/${encodeURIComponent(planId)}/requirements`,
      body: request,
      precondition: ifMatch(etag),
    });
  }

  readOwnPreference(planId: string): Observable<ApiHttpResult<PlanPreference>> {
    return this.#api.read<PlanPreference>(`/plans/${encodeURIComponent(planId)}/members/me/preference`);
  }

  listPreferences(planId: string): Observable<ApiHttpResult<PreferenceCollection>> {
    return this.#api.read<PreferenceCollection>(`/plans/${encodeURIComponent(planId)}/preferences`);
  }

  putPreference(
    planId: string,
    request: CreatePreferenceRequest,
    etag: string | null,
  ): Observable<ApiHttpResult<PlanPreference>> {
    return this.#api.mutate<PlanPreference, CreatePreferenceRequest>({
      method: 'PUT',
      path: `/plans/${encodeURIComponent(planId)}/members/me/preference`,
      body: request,
      precondition: etag === null ? ifNoneMatch() : ifMatch(etag),
    });
  }

  createIntent(
    groupId: string,
    request: CreatePlanRequest,
  ): IdempotentMutationIntent<CreatePlanRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/groups/${encodeURIComponent(groupId)}/plans`,
      body: request,
    });
  }

  create(
    intent: IdempotentMutationIntent<CreatePlanRequest>,
  ): Observable<ApiHttpResult<PlanRepresentation>> {
    return this.#api.executeIdempotent<PlanRepresentation, CreatePlanRequest>(intent);
  }
}

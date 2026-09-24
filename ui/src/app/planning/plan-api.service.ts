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
export type FinalizeRequirementsRequest = components['schemas']['FinalizeRequirementsRequest'];
export type RequirementFinalization = components['schemas']['RequirementFinalization'];
export type RequirementFinalizationPage = components['schemas']['RequirementFinalizationPageRepresentation'];
export type PublishRequest = components['schemas']['PublishRequest'];
export type PublishedRequest = components['schemas']['PublishedRequestRepresentation'];
export type GroupPublishedRequest = components['schemas']['GroupPublishedRequestRepresentation'];
export type PublishedRequestPage = components['schemas']['PublishedRequestPageRepresentation'];

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

  listFinalizations(
    planId: string,
    cursor: string | null = null,
  ): Observable<ApiHttpResult<RequirementFinalizationPage>> {
    let params = new HttpParams().set('limit', PLAN_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<RequirementFinalizationPage>(
      `/plans/${encodeURIComponent(planId)}/requirement-finalizations`,
      params,
    );
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

  createFinalizationIntent(
    planId: string,
    request: FinalizeRequirementsRequest,
    etag: string,
  ): IdempotentMutationIntent<FinalizeRequirementsRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/plans/${encodeURIComponent(planId)}/requirement-finalization`,
      body: request,
      precondition: ifMatch(etag),
    });
  }

  finalizeRequirements(
    intent: IdempotentMutationIntent<FinalizeRequirementsRequest>,
  ): Observable<ApiHttpResult<RequirementFinalization>> {
    return this.#api.executeIdempotent<RequirementFinalization, FinalizeRequirementsRequest>(intent);
  }

  createPublicationIntent(
    planId: string,
    request: PublishRequest,
    etag: string,
  ): IdempotentMutationIntent<PublishRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/plans/${encodeURIComponent(planId)}/published-requests`,
      body: request,
      precondition: ifMatch(etag),
    });
  }

  publishRequest(
    intent: IdempotentMutationIntent<PublishRequest>,
  ): Observable<ApiHttpResult<PublishedRequest>> {
    return this.#api.executeIdempotent<PublishedRequest, PublishRequest>(intent);
  }

  readCurrentPublishedRequest(planId: string): Observable<ApiHttpResult<GroupPublishedRequest>> {
    return this.#api.read<GroupPublishedRequest>(
      `/plans/${encodeURIComponent(planId)}/published-requests/current`,
    );
  }

  listPublishedRequestHistory(
    planId: string,
    cursor: string | null = null,
  ): Observable<ApiHttpResult<PublishedRequestPage>> {
    let params = new HttpParams().set('limit', PLAN_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<PublishedRequestPage>(
      `/plans/${encodeURIComponent(planId)}/published-requests`,
      params,
    );
  }

  readPublishedRequest(
    planId: string,
    requestId: string,
  ): Observable<ApiHttpResult<GroupPublishedRequest>> {
    return this.#api.read<GroupPublishedRequest>(
      `/plans/${encodeURIComponent(planId)}/published-requests/${encodeURIComponent(requestId)}`,
    );
  }

  createRequestClosureIntent(
    requestId: string,
    etag: string,
  ): IdempotentMutationIntent<undefined> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/published-requests/${encodeURIComponent(requestId)}/closure`,
      precondition: ifMatch(etag),
    });
  }

  closeRequest(
    intent: IdempotentMutationIntent<undefined>,
  ): Observable<ApiHttpResult<PublishedRequest>> {
    return this.#api.executeIdempotent<PublishedRequest, undefined>(intent);
  }

  createPlanCancellationIntent(
    planId: string,
    etag: string,
  ): IdempotentMutationIntent<undefined> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/plans/${encodeURIComponent(planId)}/cancellation`,
      precondition: ifMatch(etag),
    });
  }

  cancelPlan(
    intent: IdempotentMutationIntent<undefined>,
  ): Observable<ApiHttpResult<PlanRepresentation>> {
    return this.#api.executeIdempotent<PlanRepresentation, undefined>(intent);
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

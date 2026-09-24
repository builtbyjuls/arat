import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import {
  ApiHttpClient,
  ApiHttpResult,
  IdempotentMutationIntent,
} from '../api/api-http-client';

export type CreatePlanRequest = components['schemas']['CreatePlanRequest'];
export type PlanPage = components['schemas']['PlanPageRepresentation'];
export type PlanRepresentation = components['schemas']['PlanRepresentation'];
export type PlanSummary = components['schemas']['PlanSummaryRepresentation'];

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

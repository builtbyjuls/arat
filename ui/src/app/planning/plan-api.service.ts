import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import { ApiHttpClient, ApiHttpResult } from '../api/api-http-client';

export type PlanPage = components['schemas']['PlanPageRepresentation'];
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
}

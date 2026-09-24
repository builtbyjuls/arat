import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import { ApiHttpClient, ApiHttpResult } from '../api/api-http-client';

export type GroupPage = components['schemas']['GroupPageRepresentation'];
export type GroupSummary = components['schemas']['GroupSummaryRepresentation'];

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
}

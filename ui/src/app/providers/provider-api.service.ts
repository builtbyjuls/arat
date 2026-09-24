import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import { ApiHttpClient, ApiHttpResult } from '../api/api-http-client';

export type ProviderPage = components['schemas']['ProviderPageRepresentation'];
export type ProviderSummary = components['schemas']['ProviderSummaryRepresentation'];

const PROVIDER_PAGE_LIMIT = 20;

@Injectable({ providedIn: 'root' })
export class ProviderApi {
  readonly #api = inject(ApiHttpClient);

  list(cursor: string | null = null): Observable<ApiHttpResult<ProviderPage>> {
    let params = new HttpParams().set('limit', PROVIDER_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<ProviderPage>('/providers', params);
  }
}

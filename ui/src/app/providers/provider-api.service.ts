import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import { ApiHttpClient, ApiHttpResult, IdempotentMutationIntent, ifMatch } from '../api/api-http-client';

export type ProviderPage = components['schemas']['ProviderPageRepresentation'];
export type CreateProviderRequest = components['schemas']['CreateProviderRequest'];
export type ProviderDetail = components['schemas']['ProviderDetailRepresentation'];
export type ProviderRepresentation = components['schemas']['ProviderRepresentation'];
export type ProviderSummary = components['schemas']['ProviderSummaryRepresentation'];
export type SubmitProviderVerificationRequest = components['schemas']['SubmitProviderVerificationRequest'];
export type ProviderVerificationSubmission = components['schemas']['ProviderVerificationSubmissionRepresentation'];
export type ProviderPublishedRequest = components['schemas']['PublishedRequestRepresentation'];
export type ProviderRequestFeed = components['schemas']['ProviderRequestFeedRepresentation'];

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

  createIntent(request: CreateProviderRequest): IdempotentMutationIntent<CreateProviderRequest> {
    return this.#api.beginIdempotentMutation({ method: 'POST', path: '/providers', body: request });
  }

  create(intent: IdempotentMutationIntent<CreateProviderRequest>): Observable<ApiHttpResult<ProviderRepresentation>> {
    return this.#api.executeIdempotent(intent);
  }

  read(providerId: string): Observable<ApiHttpResult<ProviderDetail>> {
    return this.#api.read<ProviderDetail>(`/providers/${encodeURIComponent(providerId)}`);
  }

  listRequestFeed(
    providerId: string,
    cursor: string | null = null,
  ): Observable<ApiHttpResult<ProviderRequestFeed>> {
    let params = new HttpParams().set('limit', PROVIDER_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read<ProviderRequestFeed>(
      `/providers/${encodeURIComponent(providerId)}/request-feed`,
      params,
    );
  }

  readPublishedRequest(
    providerId: string,
    requestId: string,
  ): Observable<ApiHttpResult<ProviderPublishedRequest>> {
    return this.#api.read<ProviderPublishedRequest>(
      `/providers/${encodeURIComponent(providerId)}/published-requests/${encodeURIComponent(requestId)}`,
    );
  }

  replaceProfile(
    providerId: string,
    request: CreateProviderRequest,
    etag: string,
  ): Observable<ApiHttpResult<ProviderRepresentation>> {
    return this.#api.mutate({
      method: 'PUT',
      path: `/providers/${encodeURIComponent(providerId)}/profile`,
      body: request,
      precondition: ifMatch(etag),
    });
  }

  createVerificationSubmissionIntent(
    providerId: string,
    request: SubmitProviderVerificationRequest,
  ): IdempotentMutationIntent<SubmitProviderVerificationRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/providers/${encodeURIComponent(providerId)}/verification-submissions`,
      body: request,
    });
  }

  submitVerification(
    intent: IdempotentMutationIntent<SubmitProviderVerificationRequest>,
  ): Observable<ApiHttpResult<ProviderVerificationSubmission>> {
    return this.#api.executeIdempotent(intent);
  }
}

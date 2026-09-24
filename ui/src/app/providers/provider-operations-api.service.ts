import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { components } from '../api/generated/arat-api';
import {
  ApiHttpClient,
  ApiHttpResult,
  IdempotentMutationIntent,
} from '../api/api-http-client';

type PendingProviderVerificationSchema = components['schemas']['PendingProviderVerificationRepresentation'];
type ProviderVerificationDecisionSchema = components['schemas']['ProviderVerificationDecisionRepresentation'];

export type PendingProviderVerification = Readonly<Required<PendingProviderVerificationSchema>>;
export interface PendingProviderVerificationPage {
  readonly items: readonly PendingProviderVerification[];
  readonly nextCursor?: string;
}
export type ProviderVerificationDecisionRequest = components['schemas']['ProviderVerificationDecisionRequest'];
export type ProviderVerificationDecision = Readonly<
  Required<Omit<ProviderVerificationDecisionSchema, 'note'>>
  & Pick<ProviderVerificationDecisionSchema, 'note'>
>;

const VERIFICATION_PAGE_LIMIT = 20;

@Injectable({ providedIn: 'root' })
export class ProviderOperationsApi {
  readonly #api = inject(ApiHttpClient);

  listPending(
    cursor: string | null = null,
  ): Observable<ApiHttpResult<PendingProviderVerificationPage>> {
    let params = new HttpParams().set('limit', VERIFICATION_PAGE_LIMIT);
    if (cursor !== null) {
      params = params.set('cursor', cursor);
    }
    return this.#api.read('/operations/providers/pending-verifications', params);
  }

  createDecisionIntent(
    providerId: string,
    request: ProviderVerificationDecisionRequest,
  ): IdempotentMutationIntent<ProviderVerificationDecisionRequest> {
    return this.#api.beginIdempotentMutation({
      method: 'POST',
      path: `/operations/providers/${encodeURIComponent(providerId)}/verification-decisions`,
      body: request,
    });
  }

  decide(
    intent: IdempotentMutationIntent<ProviderVerificationDecisionRequest>,
  ): Observable<ApiHttpResult<ProviderVerificationDecision>> {
    return this.#api.executeIdempotent(intent);
  }
}

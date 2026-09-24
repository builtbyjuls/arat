import {
  HttpClient,
  HttpContext,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
  HttpResponse,
} from '@angular/common/http';
import { InjectionToken, inject, Injectable } from '@angular/core';
import {
  Observable,
  catchError,
  defer,
  from,
  map,
  mergeMap,
  of,
  retry,
  tap,
  throwError,
} from 'rxjs';
import { environment } from '../../environments/environment';
import { ACTOR_SCOPE_GENERATION } from '../identity/actor-scope-context';
import { ActorScopeResetService } from '../identity/actor-scope-reset.service';

const PROBLEM_CONTENT_TYPE = 'application/problem+json';
const UNKNOWN_ERROR_CODE = 'UNKNOWN_ERROR';
const UNKNOWN_ERROR_TITLE = 'Request failed';
const UNKNOWN_ERROR_DETAIL = 'The request could not be completed.';
const MAX_CODE_LENGTH = 128;
const MAX_TITLE_LENGTH = 200;
const MAX_DETAIL_LENGTH = 1000;
const MAX_CORRELATION_ID_LENGTH = 255;
const MAX_VIOLATIONS = 50;
const MAX_VIOLATION_FIELD_LENGTH = 256;
const MAX_VIOLATION_MESSAGE_LENGTH = 500;
const TRANSIENT_READ_STATUSES = new Set([0, 502, 503, 504]);
const INVITATION_ACCEPTANCE_PATH = /^\/group-invites\/[^/]+\/accept$/;

export interface ApiHttpResult<T> {
  readonly body: T | null;
  readonly status: number;
  readonly etag: string | null;
  readonly location: string | null;
  readonly correlationId: string | null;
}

export interface ApiProblemViolation {
  readonly field: string;
  readonly message: string;
}

export interface ApiProblem {
  readonly code: string;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  readonly violations: readonly ApiProblemViolation[];
  readonly correlationId: string | null;
}

export class ApiHttpError extends Error {
  override readonly name = 'ApiHttpError';

  constructor(
    readonly problem: ApiProblem,
    readonly etag: string | null,
    readonly location: string | null,
  ) {
    super(problem.detail);
  }

  get status(): number {
    return this.problem.status;
  }

  get correlationId(): string | null {
    return this.problem.correlationId;
  }
}

export type ApiMutationMethod = 'DELETE' | 'PATCH' | 'POST' | 'PUT';

export type ApiPrecondition =
  | Readonly<{ header: 'If-Match'; value: string }>
  | Readonly<{ header: 'If-None-Match'; value: '*' }>;

export interface ApiMutationRequest<TBody> {
  readonly method: ApiMutationMethod;
  readonly path: string;
  readonly body?: TBody;
  readonly precondition?: ApiPrecondition;
}

export interface IdempotentMutationIntent<TBody> {
  readonly idempotencyKey: string;
  readonly bodyType?: TBody;
}

export interface InvitationAcceptanceIntent {
  readonly idempotencyKey: string;
}

export const IDEMPOTENCY_KEY_GENERATOR = new InjectionToken<() => string>(
  'IDEMPOTENCY_KEY_GENERATOR',
  { factory: () => () => globalThis.crypto.randomUUID() },
);

type IntentState = 'ready' | 'in-flight' | 'retryable' | 'settled';

abstract class RetryableMutationIntent {
  private state: IntentState = 'ready';

  protected claimAttempt(): void {
    if (this.state !== 'ready' && this.state !== 'retryable') {
      throw new Error('This mutation intent cannot be sent again.');
    }

    this.state = 'in-flight';
  }

  recordResponse(): void {
    this.state = 'settled';
  }

  recordError(error: unknown): void {
    this.state = error instanceof HttpErrorResponse && error.status === 0
      ? 'retryable'
      : 'settled';
  }
}

class MutationIntent<TBody>
  extends RetryableMutationIntent
  implements IdempotentMutationIntent<TBody> {
  private readonly request: ApiMutationRequest<TBody>;

  constructor(
    readonly idempotencyKey: string,
    request: ApiMutationRequest<TBody>,
    readonly actorScopeGeneration: number,
  ) {
    super();
    this.request = snapshotMutation(request);
  }

  beginAttempt(): ApiMutationRequest<TBody> {
    this.claimAttempt();
    return this.request;
  }
}

class InvitationIntent
  extends RetryableMutationIntent
  implements InvitationAcceptanceIntent {
  readonly #tokenDigest: string;

  constructor(
    readonly idempotencyKey: string,
    tokenDigest: string,
    readonly actorScopeGeneration: number,
  ) {
    super();
    this.#tokenDigest = tokenDigest;
  }

  beginAttempt(token: string, tokenDigest: string): ApiMutationRequest<undefined> {
    if (tokenDigest !== this.#tokenDigest) {
      throw new Error('A changed invitation token requires a new intent.');
    }

    this.claimAttempt();
    return {
      method: 'POST',
      path: `/group-invites/${encodeURIComponent(token)}/accept`,
    };
  }
}

export function ifMatch(etag: string): ApiPrecondition {
  if (etag.length === 0) {
    throw new Error('If-Match requires a server ETag.');
  }

  return Object.freeze({ header: 'If-Match', value: etag });
}

export function ifNoneMatch(): ApiPrecondition {
  return Object.freeze({ header: 'If-None-Match', value: '*' });
}

@Injectable({ providedIn: 'root' })
export class ApiHttpClient {
  private readonly http = inject(HttpClient);
  private readonly generateIdempotencyKey = inject(IDEMPOTENCY_KEY_GENERATOR);
  private readonly scopeReset = inject(ActorScopeResetService);

  read<T>(path: string, params?: HttpParams): Observable<ApiHttpResult<T>> {
    return defer(() => {
      const generation = this.scopeReset.generation();
      return defer(() => {
        this.scopeReset.requireCurrent(generation);
        return this.http.get<T>(this.apiUrl(path), {
          context: actorScopeContext(generation),
          observe: 'response',
          params,
        });
      }).pipe(
      retry({
        count: environment.readRetryCount,
        delay: (error: unknown) => isTransientReadFailure(error)
          ? of(null)
          : throwError(() => error),
      }),
      map((response) => this.resultForCurrentScope(response, generation)),
      catchError((error: unknown) => throwError(() => toApiHttpError(error))),
      );
    });
  }

  mutate<TResponse, TBody>(
    request: ApiMutationRequest<TBody>,
  ): Observable<ApiHttpResult<TResponse>> {
    const generation = this.scopeReset.generation();
    return defer(() => this.sendMutation<TResponse, TBody>(
      request,
      undefined,
      true,
      generation,
    ));
  }

  beginIdempotentMutation<TBody>(
    request: ApiMutationRequest<TBody>,
  ): IdempotentMutationIntent<TBody> {
    if (INVITATION_ACCEPTANCE_PATH.test(request.path)) {
      throw new Error('Invitation acceptance requires its token-safe intent helper.');
    }

    return new MutationIntent(this.generateIdempotencyKey(), request, this.scopeReset.generation());
  }

  async beginInvitationAcceptance(
    token: string,
  ): Promise<InvitationAcceptanceIntent> {
    const generation = this.scopeReset.generation();
    return new InvitationIntent(
      this.generateIdempotencyKey(),
      await digestInvitationToken(token),
      generation,
    );
  }

  executeIdempotent<TResponse, TBody>(
    intent: IdempotentMutationIntent<TBody>,
  ): Observable<ApiHttpResult<TResponse>> {
    if (!(intent instanceof MutationIntent)) {
      return throwError(() => new Error('Unknown idempotent mutation intent.'));
    }

    return defer(() => {
      let request: ApiMutationRequest<TBody>;
      try {
        this.scopeReset.requireCurrent(intent.actorScopeGeneration);
        request = intent.beginAttempt();
      } catch (error: unknown) {
        return throwError(() => error);
      }

      return this.sendMutation<TResponse, TBody>(
        request,
        intent.idempotencyKey,
        false,
        intent.actorScopeGeneration,
      ).pipe(
        tap({
          next: () => intent.recordResponse(),
          error: (error: unknown) => intent.recordError(error),
        }),
        catchError((error: unknown) => throwError(() => toApiHttpError(error))),
      );
    });
  }

  executeInvitationAcceptance<TResponse>(
    intent: InvitationAcceptanceIntent,
    token: string,
  ): Observable<ApiHttpResult<TResponse>> {
    if (!(intent instanceof InvitationIntent)) {
      return throwError(() => new Error('Unknown invitation acceptance intent.'));
    }

    return from(digestInvitationToken(token)).pipe(
      mergeMap((tokenDigest: string) => {
        let request: ApiMutationRequest<undefined>;
        try {
          this.scopeReset.requireCurrent(intent.actorScopeGeneration);
          request = intent.beginAttempt(token, tokenDigest);
        } catch (error: unknown) {
          return throwError(() => error);
        }

        return this.sendMutation<TResponse, undefined>(
          request,
          intent.idempotencyKey,
          false,
          intent.actorScopeGeneration,
        ).pipe(
          tap({
            next: () => intent.recordResponse(),
            error: (error: unknown) => intent.recordError(error),
          }),
          catchError((error: unknown) => throwError(() => toApiHttpError(error))),
        );
      }),
    );
  }

  private sendMutation<TResponse, TBody>(
    request: ApiMutationRequest<TBody>,
    idempotencyKey?: string,
    mapErrors = true,
    actorScopeGeneration = this.scopeReset.generation(),
  ): Observable<ApiHttpResult<TResponse>> {
    let headers = new HttpHeaders();
    if (request.precondition !== undefined) {
      headers = headers.set(request.precondition.header, request.precondition.value);
    }
    if (idempotencyKey !== undefined) {
      headers = headers.set('Idempotency-Key', idempotencyKey);
    }

    const result = defer(() => {
      this.scopeReset.requireCurrent(actorScopeGeneration);
      return this.http.request<TResponse>(request.method, this.apiUrl(request.path), {
        body: request.body,
        context: actorScopeContext(actorScopeGeneration),
        headers,
        observe: 'response',
      });
    }).pipe(map((response) => this.resultForCurrentScope(response, actorScopeGeneration)));

    return mapErrors
      ? result.pipe(catchError((error: unknown) => throwError(() => toApiHttpError(error))))
      : result;
  }

  private apiUrl(path: string): string {
    if (!path.startsWith('/') || path.startsWith('//')) {
      throw new Error('API paths must start with one slash.');
    }

    return `${environment.apiBasePath}${path}`;
  }

  private resultForCurrentScope<T>(
    response: HttpResponse<T>,
    generation: number,
  ): ApiHttpResult<T> {
    this.scopeReset.requireCurrent(generation);
    return toResult(response);
  }
}

function actorScopeContext(generation: number): HttpContext {
  return new HttpContext().set(ACTOR_SCOPE_GENERATION, generation);
}

function snapshotMutation<TBody>(
  request: ApiMutationRequest<TBody>,
): ApiMutationRequest<TBody> {
  const body = request.body === undefined
    ? undefined
    : structuredClone(request.body);
  const precondition = request.precondition === undefined
    ? undefined
    : Object.freeze({ ...request.precondition });

  return Object.freeze({
    method: request.method,
    path: request.path,
    body,
    precondition,
  });
}

async function digestInvitationToken(token: string): Promise<string> {
  const bytes = new TextEncoder().encode(token);
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function toResult<T>(response: HttpResponse<T>): ApiHttpResult<T> {
  return Object.freeze({
    body: response.body,
    status: response.status,
    etag: response.headers.get('ETag'),
    location: response.headers.get('Location'),
    correlationId: boundedHeader(response.headers, 'X-Correlation-Id'),
  });
}

function isTransientReadFailure(error: unknown): boolean {
  return error instanceof HttpErrorResponse && TRANSIENT_READ_STATUSES.has(error.status);
}

function toApiHttpError(error: unknown): ApiHttpError {
  if (error instanceof ApiHttpError) {
    return error;
  }

  if (!(error instanceof HttpErrorResponse)) {
    return new ApiHttpError(unknownProblem(0, null), null, null);
  }

  const headerCorrelationId = boundedHeader(error.headers, 'X-Correlation-Id');
  const problem = parseProblem(error, headerCorrelationId)
    ?? unknownProblem(error.status, headerCorrelationId);

  return new ApiHttpError(
    problem,
    error.headers.get('ETag'),
    error.headers.get('Location'),
  );
}

function parseProblem(
  error: HttpErrorResponse,
  headerCorrelationId: string | null,
): ApiProblem | null {
  const contentType = error.headers.get('Content-Type')
    ?.split(';', 1)[0]
    ?.trim()
    .toLowerCase();

  if (contentType !== PROBLEM_CONTENT_TYPE || !isRecord(error.error)) {
    return null;
  }

  const code = boundedString(error.error['code'], MAX_CODE_LENGTH);
  const title = boundedString(error.error['title'], MAX_TITLE_LENGTH);
  const detail = boundedString(error.error['detail'], MAX_DETAIL_LENGTH);
  const status = error.error['status'];

  if (
    code === null
    || !/^[A-Z][A-Z0-9_]*$/.test(code)
    || title === null
    || detail === null
    || typeof status !== 'number'
    || !Number.isInteger(status)
    || status !== error.status
  ) {
    return null;
  }

  const bodyCorrelationId = boundedString(
    error.error['correlationId'],
    MAX_CORRELATION_ID_LENGTH,
  );

  return Object.freeze({
    code,
    status,
    title,
    detail,
    violations: parseViolations(error.error['violations']),
    correlationId: headerCorrelationId ?? bodyCorrelationId,
  });
}

function parseViolations(value: unknown): readonly ApiProblemViolation[] {
  if (!Array.isArray(value)) {
    return Object.freeze([]);
  }

  return Object.freeze(value
    .slice(0, MAX_VIOLATIONS)
    .flatMap((item: unknown) => {
      if (!isRecord(item)) {
        return [];
      }

      const field = boundedString(item['field'], MAX_VIOLATION_FIELD_LENGTH);
      const message = boundedString(item['message'], MAX_VIOLATION_MESSAGE_LENGTH);
      return field === null || message === null
        ? []
        : [Object.freeze({ field, message })];
    }));
}

function unknownProblem(status: number, correlationId: string | null): ApiProblem {
  return Object.freeze({
    code: UNKNOWN_ERROR_CODE,
    status,
    title: UNKNOWN_ERROR_TITLE,
    detail: UNKNOWN_ERROR_DETAIL,
    violations: Object.freeze([]),
    correlationId,
  });
}

function boundedHeader(headers: HttpHeaders, name: string): string | null {
  return boundedString(headers.get(name), MAX_CORRELATION_ID_LENGTH);
}

function boundedString(value: unknown, maximumLength: number): string | null {
  return typeof value === 'string' && value.length > 0
    ? value.slice(0, maximumLength)
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

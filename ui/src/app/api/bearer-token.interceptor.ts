import { HttpInterceptorFn } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { environment } from '../../environments/environment';

export type BearerTokenReader = () => string | null;

export const BEARER_TOKEN_READER = new InjectionToken<BearerTokenReader>(
  'BEARER_TOKEN_READER',
  { factory: () => () => null },
);

export const bearerTokenInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(BEARER_TOKEN_READER)();
  const isApiRequest = request.url === environment.apiBasePath
    || request.url.startsWith(`${environment.apiBasePath}/`);

  if (!isApiRequest || token === null || token.length === 0) {
    return next(request);
  }

  return next(request.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  }));
};

import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { tap } from 'rxjs';
import { ACTOR_SCOPE_GENERATION } from './actor-scope-context';
import { ActorScopeResetService } from './actor-scope-reset.service';

export const identityInvalidationInterceptor: HttpInterceptorFn = (request, next) => {
  const scopeReset = inject(ActorScopeResetService);
  const generation = request.context.get(ACTOR_SCOPE_GENERATION);

  return next(request).pipe(tap({
    error: (error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        scopeReset.invalidateAuthentication(generation);
      }
    },
  }));
};

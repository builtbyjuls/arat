import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { bearerTokenInterceptor } from './api/bearer-token.interceptor';
import { BEARER_TOKEN_READER } from './api/bearer-token.interceptor';
import { identityInvalidationInterceptor } from './identity/identity-invalidation.interceptor';
import { LocalActorSession, localActorTokenReader } from './identity/local-actor-session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })),
    provideHttpClient(withInterceptors([bearerTokenInterceptor, identityInvalidationInterceptor])),
    {
      provide: BEARER_TOKEN_READER,
      useFactory: localActorTokenReader,
      deps: [LocalActorSession],
    },
  ],
};

import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { LocalActorSession } from './local-actor-session.service';

export const localActorRouteGuard: CanActivateFn = async () => {
  const session = inject(LocalActorSession);
  const router = inject(Router);

  await session.restore();
  return session.isVerified() || router.createUrlTree(['/']);
};

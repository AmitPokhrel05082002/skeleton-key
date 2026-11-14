import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionService } from './session.service';

/** Requires the platform admin role; otherwise navigates to the NotAuthorised page. */
export const adminGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);
  return session.isPlatformAdmin() ? true : router.createUrlTree(['/not-authorised']);
};

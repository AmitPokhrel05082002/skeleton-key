import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { RuntimeConfigService } from '../config/runtime-config.service';
import { SessionService } from './session.service';

function isUnder(url: string, prefix: string): boolean {
  return url === prefix || url.startsWith(`${prefix}/`) || url.startsWith(`${prefix}?`);
}

/**
 * Adds the Bearer token only to API URLs outside `{apiBase}/public/`. On a 401 from any
 * other API call than login it clears the session and redirects to the login page.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const base = inject(RuntimeConfigService).apiBase();
  const session = inject(SessionService);
  const router = inject(Router);

  const underApi = isUnder(req.url, base);
  const isPublic = req.url.startsWith(`${base}/public/`);
  const isLogin = req.url === `${base}/auth/login`;
  const token = session.session()?.token;

  const outgoing = underApi && !isPublic && token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(outgoing).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && underApi && !isLogin) {
        session.clear();
        const current = router.url;
        if (!current.startsWith('/login')) {
          void router.navigate(['/login'], { queryParams: { returnUrl: current } });
        }
      }
      return throwError(() => err);
    }),
  );
};

import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Router, provideRouter, withDisabledInitialNavigation } from '@angular/router';
import { provideNzI18n, en_US } from 'ng-zorro-antd/i18n';
import { firstValueFrom } from 'rxjs';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { SessionService } from './core/auth/session.service';
import { RuntimeConfigService } from './core/config/runtime-config.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withDisabledInitialNavigation()),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideNzI18n(en_US),
    provideAppInitializer(async () => {
      const config = inject(RuntimeConfigService);
      const router = inject(Router);
      const session = inject(SessionService);
      // Initial navigation only after the config loaded; on failure App shows ConfigErrorPage.
      if (await config.load()) {
        await firstValueFrom(session.restore());
        router.initialNavigation();
      }
    }),
  ],
};

import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Router, provideRouter, withDisabledInitialNavigation } from '@angular/router';
import { provideNzNativeDateAdapter } from 'ng-zorro-antd/core/time';
import { provideNzI18n, en_US } from 'ng-zorro-antd/i18n';
import { firstValueFrom } from 'rxjs';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { SessionService } from './core/auth/session.service';
import { RuntimeConfigService } from './core/config/runtime-config.service';
import { ThemeService } from './core/theme/theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withDisabledInitialNavigation()),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideNzI18n(en_US),
    provideNzNativeDateAdapter(),
    provideAppInitializer(async () => {
      const config = inject(RuntimeConfigService);
      const router = inject(Router);
      const session = inject(SessionService);
      // Every page needs a theme stylesheet, including login, admin and error pages that never enter the
      // app shell. Follow the system preference until an app's branding overrides it. Wait at most 1.5 s for the
      // stylesheet so a slow or missing file (or an environment that never fires link events) never blocks startup.
      const themed = Promise.race([
        inject(ThemeService).apply('auto'),
        new Promise<void>((resolve) => setTimeout(resolve, 1500)),
      ]);
      // Initial navigation only after the config loaded; on failure App shows ConfigErrorPage.
      if (await config.load()) {
        await firstValueFrom(session.restore());
        router.initialNavigation();
      }
      await themed;
    }),
  ],
};

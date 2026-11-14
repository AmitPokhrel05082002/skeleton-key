import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { Router, provideRouter, withDisabledInitialNavigation } from '@angular/router';
import { provideNzI18n, en_US } from 'ng-zorro-antd/i18n';

import { routes } from './app.routes';
import { RuntimeConfigService } from './core/config/runtime-config.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withDisabledInitialNavigation()),
    provideNzI18n(en_US),
    provideAppInitializer(async () => {
      const config = inject(RuntimeConfigService);
      const router = inject(Router);
      // Initial navigation only after the config loaded; on failure App shows ConfigErrorPage.
      if (await config.load()) {
        router.initialNavigation();
      }
    }),
  ],
};

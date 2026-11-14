import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { resolveDefaultApp } from '../../core/config/host-resolver';
import { RuntimeConfigService } from '../../core/config/runtime-config.service';
import { NoAppPage } from './no-app.page';

/** Route `/`: redirects to `/app/<Default_App>`, or shows NoAppPage when the host is not mapped. */
@Component({
  selector: 'app-root-redirect-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NoAppPage],
  template: `
    @if (!defaultApp) {
      <app-no-app-page />
    }
  `,
})
export class RootRedirectPage {
  private readonly router = inject(Router);
  protected readonly defaultApp: string | null;

  constructor() {
    const hosts = inject(RuntimeConfigService).config()?.hosts ?? {};
    this.defaultApp = resolveDefaultApp(hosts, window.location.host);
    if (this.defaultApp) {
      void this.router.navigate(['/app', this.defaultApp], { replaceUrl: true });
    }
  }
}

import { ChangeDetectionStrategy, Component, computed, effect, inject, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { map } from 'rxjs';
import { SessionService } from '../../core/auth/session.service';
import { DefinitionStore, buildMenu } from '../../core/definition/definition.store';
import { safeLogoUrl } from '../../core/pure/safe-url';
import { ThemeService } from '../../core/theme/theme.service';

/** Branded app shell: header, navigation menu and the routed content of `/app/:app`. */
@Component({
  selector: 'sk-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NzLayoutModule, NzMenuModule, NzSpinModule, NzButtonModule],
  template: `
    @switch (store.status()) {
      @case ('ready') {
        <nz-layout class="sk-shell">
          <nz-header class="sk-header">
            <div class="sk-header-inner">
              @if (logo(); as src) {
                <img class="sk-logo" [src]="src" [alt]="appName() + ' logo'" height="32" />
              }
              <span class="sk-app-name">{{ appName() }}</span>
              <span class="sk-spacer"></span>
              <span class="sk-user">{{ session.session()?.displayName }}</span>
              <button nz-button type="button" (click)="signOut()">Sign out</button>
            </div>
          </nz-header>
          <nz-layout>
            <nz-sider nzTheme="dark" [nzWidth]="220">
              <ul nz-menu nzMode="inline" nzTheme="dark" aria-label="Navigation">
                @for (item of menu(); track item.key) {
                  <li nz-menu-item [routerLink]="item.link" routerLinkActive="ant-menu-item-selected">
                    {{ item.label }}
                  </li>
                }
              </ul>
            </nz-sider>
            <nz-content class="sk-content">
              <router-outlet />
            </nz-content>
          </nz-layout>
        </nz-layout>
      }
      @case ('unavailable') {
        <div class="sk-center" role="alert">
          <h2>App not available</h2>
          <p>This app does not exist or has not been published.</p>
          <button nz-button type="button" (click)="signOut()">Sign out</button>
        </div>
      }
      @case ('error') {
        <div class="sk-center" role="alert">
          <h2>Could not load the app</h2>
          <p>{{ store.error()?.message }}</p>
          <button nz-button nzType="primary" type="button" (click)="retry()">Retry</button>
        </div>
      }
      @default {
        <div class="sk-center" aria-live="polite">
          <nz-spin nzSimple nzSize="large" />
          <span class="sk-loading-text">Loading…</span>
        </div>
      }
    }
  `,
  styles: `
    .sk-shell {
      min-height: 100vh;
    }
    .sk-header-inner {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .sk-header {
      padding: 0 16px;
    }
    .sk-spacer {
      flex: 1;
    }
    .sk-app-name {
      font-weight: 600;
      font-size: 18px;
    }
    .sk-logo {
      max-height: 32px;
      width: auto;
    }
    .sk-content {
      padding: 16px;
    }
    .sk-center {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      min-height: 60vh;
    }
  `,
})
export class ShellComponent {
  protected readonly store = inject(DefinitionStore);
  protected readonly session = inject(SessionService);
  private readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly appCode = toSignal(this.route.paramMap.pipe(map((p) => p.get('app') ?? '')), {
    initialValue: this.route.snapshot.paramMap.get('app') ?? '',
  });

  protected readonly appName = computed(() => this.store.definition()?.app.name ?? '');
  protected readonly logo = computed(() => safeLogoUrl(this.store.definition()?.app.branding?.logo));
  protected readonly menu = computed(() => {
    const def = this.store.definition();
    return def ? buildMenu(def, this.session.roles()) : [];
  });

  constructor() {
    effect(() => {
      const app = this.appCode();
      if (app) {
        untracked(() => this.store.load(app));
      }
    });
    effect(() => {
      const def = this.store.definition();
      if (def) {
        void this.theme.apply(def.app.branding?.theme);
        this.theme.applyBranding(def.app.branding?.primaryColor);
      }
    });
  }

  protected retry(): void {
    this.store.load(this.appCode(), true);
  }

  protected signOut(): void {
    this.session.clear();
    this.store.reset();
    this.theme.applyBranding(null);
    void this.router.navigate(['/login']);
  }
}

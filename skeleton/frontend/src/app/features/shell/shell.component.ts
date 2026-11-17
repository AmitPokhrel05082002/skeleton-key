import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMenuModule } from 'ng-zorro-antd/menu';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { filter, map } from 'rxjs';
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
              <button
                nz-button
                type="button"
                class="sk-nav-toggle"
                aria-label="Toggle navigation"
                [attr.aria-expanded]="!collapsed()"
                [attr.aria-controls]="menuId"
                (click)="toggleNav()"
              >
                ☰
              </button>
              @if (logo(); as src) {
                <img class="sk-logo" [src]="src" [alt]="appName() + ' logo'" height="32" />
              }
              <span class="sk-app-name" [attr.title]="appName()">{{ appName() }}</span>
              <span class="sk-spacer"></span>
              <span class="sk-user">{{ session.session()?.displayName }}</span>
              <button nz-button type="button" class="sk-signout" (click)="signOut()">Sign out</button>
            </div>
          </nz-header>
          <nz-layout class="sk-main" [style.min-width.px]="0">
            <nz-sider
              nzTheme="dark"
              nzCollapsible
              nzBreakpoint="md"
              [nzTrigger]="null"
              [nzWidth]="220"
              [nzCollapsedWidth]="0"
              [(nzCollapsed)]="collapsed"
            >
              <ul nz-menu nzMode="inline" nzTheme="dark" aria-label="Navigation" [id]="menuId">
                @for (item of viewItems(); track item.key) {
                  <li nz-menu-item [routerLink]="item.link" routerLinkActive="ant-menu-item-selected">
                    {{ item.label }}
                  </li>
                }
                @if (newItems().length > 0) {
                  <li nz-menu-group nzTitle="New">
                    <ul>
                      @for (item of newItems(); track item.key) {
                        <li nz-menu-item [routerLink]="item.link" routerLinkActive="ant-menu-item-selected">
                          {{ item.label }}
                        </li>
                      }
                    </ul>
                  </li>
                }
                @for (item of adminItems(); track item.key) {
                  <li nz-menu-item [routerLink]="item.link" routerLinkActive="ant-menu-item-selected">
                    {{ item.label }}
                  </li>
                }
              </ul>
            </nz-sider>
            <nz-content class="sk-content" [style.min-width.px]="0">
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
    .sk-header-inner {
      min-width: 0;
    }
    .sk-spacer {
      flex: 1 1 0;
      min-width: 0;
    }
    .sk-nav-toggle,
    .sk-signout,
    .sk-logo {
      flex: 0 0 auto;
    }
    .sk-app-name {
      font-weight: 600;
      font-size: 18px;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .sk-user {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 30vw;
    }
    .sk-main {
      min-width: 0;
    }
    .sk-logo {
      max-height: 32px;
      width: auto;
    }
    .sk-content {
      min-width: 0;
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
  protected readonly viewItems = computed(() => this.menu().filter((i) => i.kind === 'view'));
  protected readonly newItems = computed(() => this.menu().filter((i) => i.kind === 'new'));
  protected readonly adminItems = computed(() => this.menu().filter((i) => i.kind === 'admin'));

  protected readonly menuId = 'sk-nav-menu';
  /** Side menu collapsed state. The sider also sets it when the viewport crosses the md breakpoint. */
  protected readonly collapsed = signal(false);

  constructor() {
    // Any navigation (menu click or keyboard) closes the menu again on small screens.
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.onNavigate());
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

  protected toggleNav(): void {
    this.collapsed.update((c) => !c);
  }

  /** On small screens (below md, 768 px) picking a menu entry closes the menu again. */
  protected onNavigate(): void {
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 767.98px)').matches) {
      this.collapsed.set(true);
    }
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

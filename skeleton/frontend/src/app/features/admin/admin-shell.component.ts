import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { SessionService } from '../../core/auth/session.service';

/** Header and outlet for the `/admin` area. */
@Component({
  selector: 'sk-admin-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, NzLayoutModule, NzButtonModule],
  template: `
    <nz-layout class="sk-admin-shell">
      <nz-header class="sk-header">
        <div class="sk-header-inner">
          <a class="sk-admin-title" routerLink="/admin">Administration</a>
          <span class="sk-spacer"></span>
          <a nz-button routerLink="/">Back to apps</a>
          <span class="sk-user">{{ session.session()?.displayName }}</span>
          <button nz-button type="button" (click)="signOut()">Sign out</button>
        </div>
      </nz-header>
      <nz-content class="sk-content">
        <router-outlet />
      </nz-content>
    </nz-layout>
  `,
  styles: `
    .sk-admin-shell {
      min-height: 100vh;
    }
    .sk-header {
      padding: 0 16px;
    }
    .sk-header-inner {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .sk-spacer {
      flex: 1;
    }
    .sk-admin-title {
      font-weight: 600;
      font-size: 18px;
    }
    .sk-content {
      padding: 16px;
    }
  `,
})
export class AdminShellComponent {
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected signOut(): void {
    this.session.clear();
    void this.router.navigate(['/login']);
  }
}

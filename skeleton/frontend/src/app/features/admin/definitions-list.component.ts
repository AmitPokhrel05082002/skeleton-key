import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { ApiService } from '../../core/api/api.service';
import { ApiError, VersionRef } from '../../core/api/api.types';

/** Lists app codes and the definition versions of the chosen app (Requirement 13). */
@Component({
  selector: 'sk-definitions-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NzAlertModule, NzButtonModule, NzSpinModule, NzTableModule, NzTagModule],
  template: `
    <div class="sk-toolbar">
      <h2>Definitions</h2>
      <button nz-button nzType="primary" type="button" (click)="newDefinition()">New definition</button>
    </div>

    @if (forbidden()) {
      <div class="sk-forbidden" role="alert">
        <nz-alert nzType="error" nzMessage="Not authorised" nzDescription="You are not authorised to manage definitions." />
      </div>
    } @else {
      @if (error(); as err) {
        <nz-alert nzType="error" [nzMessage]="err" role="alert" />
      }
      @if (loadingApps()) {
        <nz-spin nzSimple />
      }
      <ul class="sk-apps" aria-label="Apps">
        @for (code of apps(); track code) {
          <li>
            <button
              nz-button
              type="button"
              [nzType]="code === selected() ? 'primary' : 'default'"
              [attr.aria-pressed]="code === selected()"
              (click)="chooseApp(code)"
            >
              {{ code }}
            </button>
          </li>
        } @empty {
          @if (!loadingApps() && !error()) {
            <li class="sk-empty">No apps yet.</li>
          }
        }
      </ul>

      @if (selected(); as app) {
        <h3>Versions of {{ app }}</h3>
        @if (loadingVersions()) {
          <nz-spin nzSimple />
        }
        <nz-table #table [nzData]="versions()" nzSize="small" [nzShowPagination]="false" aria-label="Versions">
          <thead>
            <tr>
              <th>Version</th>
              <th>Status</th>
              <th>Content hash</th>
              <th>Id</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (v of table.data; track v.id) {
              <tr>
                <td class="sk-version">{{ v.version }}</td>
                <td class="sk-status">
                  <nz-tag [nzColor]="v.status === 'PUBLISHED' ? 'green' : 'orange'">{{ v.status }}</nz-tag>
                </td>
                <td class="sk-hash">
                  <code>{{ v.contentHash }}</code>
                </td>
                <td class="sk-id">
                  <code>{{ v.id }}</code>
                </td>
                <td>
                  <button nz-button type="button" (click)="open(v)">Open</button>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    }
  `,
  styles: `
    .sk-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .sk-apps {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      list-style: none;
      padding: 0;
    }
    .sk-forbidden {
      max-width: 40rem;
    }
  `,
})
export class DefinitionsListComponent {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  protected readonly apps = signal<string[]>([]);
  protected readonly selected = signal<string | null>(null);
  protected readonly versions = signal<VersionRef[]>([]);
  protected readonly loadingApps = signal(true);
  protected readonly loadingVersions = signal(false);
  protected readonly forbidden = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.api.definitionApps().subscribe({
      next: (codes) => {
        this.apps.set(codes);
        this.loadingApps.set(false);
      },
      error: (e: ApiError) => {
        this.loadingApps.set(false);
        this.fail(e);
      },
    });
  }

  protected chooseApp(code: string): void {
    this.selected.set(code);
    this.versions.set([]);
    this.loadingVersions.set(true);
    this.api.definitionVersions(code).subscribe({
      next: (list) => {
        if (this.selected() === code) {
          this.versions.set([...list].sort((a, b) => b.version - a.version));
          this.loadingVersions.set(false);
        }
      },
      error: (e: ApiError) => {
        this.loadingVersions.set(false);
        this.fail(e);
      },
    });
  }

  protected open(v: VersionRef): void {
    void this.router.navigate(['/admin/editor', v.id]);
  }

  protected newDefinition(): void {
    void this.router.navigate(['/admin/editor/new']);
  }

  private fail(e: ApiError): void {
    if (e.status === 403) {
      this.forbidden.set(true);
    } else {
      this.error.set(e.message || 'Could not load definitions.');
    }
  }
}

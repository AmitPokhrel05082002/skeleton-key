import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule, type NzTableSortOrder } from 'ng-zorro-antd/table';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { Subject, debounceTime } from 'rxjs';
import { ApiService } from '../../../core/api/api.service';
import type { ApiError, RoleOption, UserView } from '../../../core/api/api.types';
import { SessionService } from '../../../core/auth/session.service';
import { ResetPasswordModalComponent } from './reset-password-modal.component';
import { UserDrawerComponent } from './user-drawer.component';

export const DEACTIVATE_HINT = 'This user is signed out on their next request.';

type SortKey = 'username' | 'displayName' | 'active' | 'createdAt';
type ActiveFilter = 'all' | 'true' | 'false';

/** Admin users page: server-side paging, sorting and filters; create, edit, activate and reset password. */
@Component({
  selector: 'sk-users-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzInputModule,
    NzModalModule,
    NzSelectModule,
    NzSwitchModule,
    NzTableModule,
    NzTagModule,
    NzTooltipModule,
    ResetPasswordModalComponent,
    UserDrawerComponent,
  ],
  template: `
    <div class="sk-toolbar">
      <h2>Users</h2>
      <button nz-button nzType="primary" type="button" class="sk-new-user" (click)="newUser()">New user</button>
    </div>

    <div class="sk-filters">
      <input
        nz-input
        class="sk-search"
        type="search"
        placeholder="Search username or name"
        aria-label="Search users"
        [ngModel]="q()"
        (ngModelChange)="onSearch($event)"
      />
      <nz-select
        class="sk-role-filter"
        nzAllowClear
        nzPlaceHolder="Any role"
        aria-label="Role filter"
        [ngModel]="role()"
        (ngModelChange)="onRole($event)"
      >
        @for (o of roleOptions(); track o.code) {
          <nz-option [nzValue]="o.code" [nzLabel]="o.code + ' - ' + o.label" />
        }
      </nz-select>
      <nz-select class="sk-active-filter" aria-label="Active filter" [ngModel]="activeFilter()" (ngModelChange)="onActiveFilter($event)">
        <nz-option nzValue="all" nzLabel="All" />
        <nz-option nzValue="true" nzLabel="Active" />
        <nz-option nzValue="false" nzLabel="Inactive" />
      </nz-select>
    </div>

    @if (error(); as e) {
      <nz-alert nzType="error" [nzMessage]="e" role="alert" />
    }

    <nz-table
      #table
      nzSize="small"
      aria-label="Users"
      [nzData]="items()"
      [nzFrontPagination]="false"
      [nzLoading]="loading()"
      [nzTotal]="total()"
      [nzPageIndex]="page() + 1"
      [nzPageSize]="size()"
      [nzShowSizeChanger]="true"
      [nzPageSizeOptions]="[10, 20, 50]"
      (nzPageIndexChange)="onPage($event)"
      (nzPageSizeChange)="onSize($event)"
    >
      <thead>
        <tr>
          <th nzShowSort [nzSortOrder]="order('displayName')" (nzSortOrderChange)="onSort('displayName', $event)">Display name</th>
          <th nzShowSort [nzSortOrder]="order('username')" (nzSortOrderChange)="onSort('username', $event)">Username</th>
          <th>Roles</th>
          <th nzShowSort [nzSortOrder]="order('active')" (nzSortOrderChange)="onSort('active', $event)">Active</th>
          <th nzShowSort [nzSortOrder]="order('createdAt')" (nzSortOrderChange)="onSort('createdAt', $event)">Created</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (u of table.data; track u.id) {
          <tr>
            <td class="sk-name">{{ u.displayName }}</td>
            <td class="sk-username">{{ u.username }}</td>
            <td class="sk-roles">
              @for (r of u.roles; track r) {
                <nz-tag>{{ r }}</nz-tag>
              }
            </td>
            <td>
              <span nz-tooltip [nzTooltipTitle]="isSelf(u) ? 'You cannot deactivate your own account.' : null">
                <nz-switch
                  class="sk-active-switch"
                  [ngModel]="u.active"
                  [nzControl]="true"
                  [nzDisabled]="isSelf(u)"
                  [nzLoading]="busyId() === u.id"
                  (click)="toggleActive(u)"
                />
              </span>
            </td>
            <td class="sk-created">{{ u.createdAt | date: 'yyyy-MM-dd HH:mm' }}</td>
            <td class="sk-row-actions">
              <button nz-button nzSize="small" type="button" class="sk-edit" (click)="edit(u)">Edit</button>
              <button nz-button nzSize="small" type="button" class="sk-reset" (click)="resetTarget.set(u)">Reset password</button>
            </td>
          </tr>
        }
      </tbody>
    </nz-table>

    <sk-user-drawer
      [open]="drawerOpen()"
      [user]="editing()"
      [roleOptions]="roleOptions()"
      [selfId]="selfId()"
      (saved)="onSaved()"
      (closed)="closeDrawer()"
      (resetRequested)="onResetFromDrawer($event)"
    />
    <sk-reset-password-modal [user]="resetTarget()" (closed)="resetTarget.set(null)" (done)="onPasswordReset()" />
  `,
  styles: `
    .sk-toolbar {
      align-items: center;
      display: flex;
      justify-content: space-between;
    }
    .sk-filters {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 12px;
    }
    .sk-search {
      width: 260px;
    }
    .sk-role-filter,
    .sk-active-filter {
      min-width: 180px;
    }
    .sk-row-actions {
      display: flex;
      gap: 8px;
    }
  `,
})
export class UsersPageComponent {
  private readonly api = inject(ApiService);
  private readonly session = inject(SessionService);
  private readonly modal = inject(NzModalService);
  private readonly message = inject(NzMessageService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly items = signal<UserView[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(20);
  protected readonly sortKey = signal<SortKey>('createdAt');
  protected readonly sortDir = signal<'asc' | 'desc'>('desc');
  protected readonly q = signal('');
  protected readonly role = signal<string | null>(null);
  protected readonly activeFilter = signal<ActiveFilter>('all');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly roleOptions = signal<RoleOption[]>([]);
  protected readonly busyId = signal<string | null>(null);

  protected readonly drawerOpen = signal(false);
  protected readonly editing = signal<UserView | null>(null);
  protected readonly resetTarget = signal<UserView | null>(null);

  protected readonly selfId = computed(() => this.session.session()?.userId ?? null);

  private readonly search$ = new Subject<string>();
  private requestSeq = 0;

  constructor() {
    this.search$.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe((text) => {
      this.q.set(text);
      this.page.set(0);
      this.load();
    });
    this.api.roleOptions().subscribe({
      next: (o) => this.roleOptions.set(o),
      error: () => this.roleOptions.set([]),
    });
    this.load();
  }

  protected isSelf(u: UserView): boolean {
    return u.id === this.selfId();
  }

  protected order(key: SortKey): NzTableSortOrder {
    if (this.sortKey() !== key) {
      return null;
    }
    return this.sortDir() === 'asc' ? 'ascend' : 'descend';
  }

  protected onSort(key: SortKey, order: NzTableSortOrder): void {
    if (order === null) {
      if (this.sortKey() === key) {
        // The header cycles to "no sort": fall back to the default order.
        this.sortKey.set('createdAt');
        this.sortDir.set('desc');
        this.load();
      }
      return;
    }
    const dir = order === 'ascend' ? 'asc' : 'desc';
    if (this.sortKey() === key && this.sortDir() === dir) {
      return;
    }
    this.sortKey.set(key);
    this.sortDir.set(dir);
    this.load();
  }

  protected onSearch(text: string): void {
    this.search$.next(text);
  }

  protected onRole(code: string | null): void {
    this.role.set(code ?? null);
    this.page.set(0);
    this.load();
  }

  protected onActiveFilter(value: ActiveFilter): void {
    this.activeFilter.set(value);
    this.page.set(0);
    this.load();
  }

  protected onPage(pageIndex: number): void {
    if (pageIndex - 1 === this.page()) {
      return;
    }
    this.page.set(pageIndex - 1);
    this.load();
  }

  protected onSize(size: number): void {
    if (size === this.size()) {
      return;
    }
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  protected newUser(): void {
    this.editing.set(null);
    this.drawerOpen.set(true);
  }

  protected edit(u: UserView): void {
    this.editing.set(u);
    this.drawerOpen.set(true);
  }

  protected closeDrawer(): void {
    this.drawerOpen.set(false);
    this.editing.set(null);
  }

  protected onSaved(): void {
    this.closeDrawer();
    this.message.success('User saved.');
    this.load();
  }

  protected onResetFromDrawer(u: UserView): void {
    this.resetTarget.set(u);
  }

  protected onPasswordReset(): void {
    this.resetTarget.set(null);
    this.message.success('Password changed.');
  }

  /** The switch is controlled: its value only changes after the server accepted the PATCH, so a failure leaves it as it was. */
  protected toggleActive(u: UserView): void {
    if (this.isSelf(u) || this.busyId() !== null) {
      return;
    }
    const next = !u.active;
    if (!next) {
      this.modal.confirm({
        nzTitle: `Deactivate ${u.username}?`,
        nzContent: DEACTIVATE_HINT,
        nzOkText: 'Deactivate',
        nzOkDanger: true,
        nzOnOk: () => this.patchActive(u, false),
      });
    } else {
      this.patchActive(u, true);
    }
  }

  private patchActive(u: UserView, active: boolean): void {
    this.busyId.set(u.id);
    this.api.updateUser(u.id, { active }).subscribe({
      next: (saved) => {
        this.busyId.set(null);
        this.items.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      },
      error: (e: ApiError) => {
        this.busyId.set(null);
        this.message.error(e.message || 'The change could not be saved.');
      },
    });
  }

  private load(): void {
    const seq = ++this.requestSeq;
    this.loading.set(true);
    this.error.set(null);
    const filter = this.activeFilter();
    this.api
      .listUsers({
        q: this.q(),
        role: this.role() ?? undefined,
        active: filter === 'all' ? undefined : filter === 'true',
        page: this.page(),
        size: this.size(),
        sort: `${this.sortKey()},${this.sortDir()}`,
      })
      .subscribe({
        next: (res) => {
          if (seq !== this.requestSeq) {
            return;
          }
          this.items.set(res.items);
          this.total.set(res.total);
          this.loading.set(false);
        },
        error: (e: ApiError) => {
          if (seq !== this.requestSeq) {
            return;
          }
          this.loading.set(false);
          this.error.set(e.status === 403 ? 'You are not authorised to manage users.' : e.message || 'Could not load users.');
        },
      });
  }
}

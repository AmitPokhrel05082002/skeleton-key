import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDrawerModule } from 'ng-zorro-antd/drawer';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { ApiService } from '../../../core/api/api.service';
import type { ApiError, RoleOption, UserView } from '../../../core/api/api.types';
import { ADMIN_ROLE } from '../../../core/auth/session.service';
import {
  mapUserError,
  toCreateRequest,
  toPatch,
  validatePassword,
  validateUserForm,
  type UserFormValue,
} from './user-form-model';

/** Create (user = null) or edit (user set) drawer. The drawer content is destroyed on close, so the password never lingers. */
@Component({
  selector: 'sk-user-drawer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NzAlertModule, NzButtonModule, NzDrawerModule, NzInputModule, NzSelectModule, NzSwitchModule, NzTooltipModule],
  template: `
    <nz-drawer
      [nzVisible]="open()"
      [nzTitle]="isEdit() ? 'Edit user' : 'New user'"
      nzWidth="440"
      [nzMaskClosable]="false"
      (nzOnClose)="close()"
    >
      <ng-container *nzDrawerContent>
        @if (formError(); as fe) {
          <nz-alert class="sk-drawer-error" nzType="error" [nzMessage]="fe" role="alert" />
        }
        <div class="sk-row">
          <label for="sk-user-username">Username</label>
          <input
            id="sk-user-username"
            nz-input
            autocomplete="off"
            [ngModel]="username()"
            (ngModelChange)="username.set($event)"
            [readonly]="isEdit()"
          />
          @if (error('username'); as e) {
            <div class="sk-field-error" role="alert">{{ e }}</div>
          }
        </div>
        <div class="sk-row">
          <label for="sk-user-display">Display name</label>
          <input id="sk-user-display" nz-input [ngModel]="displayName()" (ngModelChange)="displayName.set($event)" />
          @if (error('displayName'); as e) {
            <div class="sk-field-error" role="alert">{{ e }}</div>
          }
        </div>
        @if (!isEdit()) {
          <div class="sk-row">
            <label for="sk-user-password">Initial password (at least 8 characters)</label>
            <div class="sk-password">
              <input
                id="sk-user-password"
                nz-input
                autocomplete="new-password"
                [type]="showPassword() ? 'text' : 'password'"
                [ngModel]="password()"
                (ngModelChange)="password.set($event)"
              />
              <button nz-button type="button" class="sk-toggle-password" (click)="showPassword.set(!showPassword())">
                {{ showPassword() ? 'Hide' : 'Show' }}
              </button>
            </div>
            @if (error('password'); as e) {
              <div class="sk-field-error" role="alert">{{ e }}</div>
            }
          </div>
        }
        <div class="sk-row">
          <label for="sk-user-roles">Roles</label>
          <nz-select
            id="sk-user-roles"
            nzMode="multiple"
            nzPlaceHolder="No roles"
            [ngModel]="roles()"
            (ngModelChange)="roles.set($event)"
          >
            @for (o of options(); track o.code) {
              <nz-option [nzValue]="o.code" [nzLabel]="o.code + ' - ' + o.label" [nzDisabled]="isSelfAdminOption(o.code)" />
            }
          </nz-select>
          @if (selfEdit()) {
            <div class="sk-hint">You cannot remove your own admin role.</div>
          }
          @if (error('roles'); as e) {
            <div class="sk-field-error" role="alert">{{ e }}</div>
          }
        </div>
        <div class="sk-row">
          <span
            nz-tooltip
            [nzTooltipTitle]="selfEdit() ? 'You cannot deactivate your own account.' : null"
          >
            <nz-switch
              class="sk-active-switch"
              [ngModel]="active()"
              (ngModelChange)="active.set($event)"
              [nzDisabled]="selfEdit()"
              nzCheckedChildren="Active"
              nzUnCheckedChildren="Inactive"
            />
          </span>
        </div>
        <div class="sk-actions">
          @if (isEdit()) {
            <button nz-button type="button" class="sk-drawer-reset" (click)="resetRequested.emit(user()!)">Reset password</button>
          }
          <span class="sk-spacer"></span>
          <button nz-button type="button" class="sk-drawer-cancel" (click)="close()">Cancel</button>
          <button
            nz-button
            nzType="primary"
            type="button"
            class="sk-drawer-save"
            [nzLoading]="saving()"
            [disabled]="saving() || !valid()"
            (click)="save()"
          >
            Save
          </button>
        </div>
      </ng-container>
    </nz-drawer>
  `,
  styles: `
    .sk-row {
      margin-bottom: 16px;
    }
    .sk-row label {
      display: block;
      margin-bottom: 4px;
    }
    .sk-password {
      display: flex;
      gap: 8px;
    }
    .sk-field-error {
      color: #ff4d4f;
      margin-top: 4px;
    }
    .sk-hint {
      margin-top: 4px;
      opacity: 0.7;
    }
    .sk-drawer-error {
      margin-bottom: 16px;
    }
    .sk-actions {
      align-items: center;
      display: flex;
      gap: 8px;
    }
    .sk-spacer {
      flex: 1;
    }
  `,
})
export class UserDrawerComponent {
  private readonly api = inject(ApiService);

  readonly open = input(false);
  /** The user to edit, or null to create. */
  readonly user = input<UserView | null>(null);
  readonly roleOptions = input<RoleOption[]>([]);
  /** Id of the signed-in user, for the lockout guard in the UI. */
  readonly selfId = input<string | null>(null);

  readonly saved = output<UserView>();
  readonly closed = output<void>();
  readonly resetRequested = output<UserView>();

  protected readonly username = signal('');
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly roles = signal<string[]>([]);
  protected readonly active = signal(true);
  protected readonly showPassword = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverErrors = signal<Record<string, string>>({});
  protected readonly formError = signal<string | null>(null);

  protected readonly isEdit = computed(() => this.user() !== null);
  protected readonly selfEdit = computed(() => {
    const u = this.user();
    return u !== null && this.selfId() !== null && u.id === this.selfId();
  });

  /** Role options, plus any role the user holds that is no longer offered, so it is shown and kept. */
  protected readonly options = computed<RoleOption[]>(() => {
    const base = this.roleOptions();
    const known = new Set(base.map((o) => o.code));
    const extra = (this.user()?.roles ?? [])
      .filter((c) => !known.has(c))
      .map((code) => ({ code, label: code, apps: [] as string[] }));
    return [...base, ...extra];
  });

  private readonly value = computed<UserFormValue>(() => ({
    username: this.username(),
    displayName: this.displayName(),
    password: this.password(),
    roles: this.roles(),
    active: this.active(),
  }));

  private readonly localErrors = computed(() => validateUserForm(this.value(), this.isEdit() ? 'edit' : 'create'));
  protected readonly valid = computed(() => Object.keys(this.localErrors()).length === 0);

  constructor() {
    // Reload the form whenever the drawer opens (or its target changes) and wipe it on close.
    effect(() => {
      const isOpen = this.open();
      const u = this.user();
      untracked(() => {
        this.serverErrors.set({});
        this.formError.set(null);
        this.showPassword.set(false);
        this.password.set('');
        if (isOpen && u) {
          this.username.set(u.username);
          this.displayName.set(u.displayName);
          this.roles.set([...u.roles]);
          this.active.set(u.active);
        } else {
          this.username.set('');
          this.displayName.set('');
          this.roles.set([]);
          this.active.set(true);
        }
      });
    });
  }

  protected isSelfAdminOption(code: string): boolean {
    return this.selfEdit() && code === ADMIN_ROLE;
  }

  /** Server message for a field, else the local rule message once the field has a value to judge. */
  protected error(field: 'username' | 'displayName' | 'password' | 'roles'): string | null {
    const server = this.serverErrors()[field];
    if (server) {
      return server;
    }
    if (field === 'roles') {
      return null;
    }
    const local = this.localErrors()[field];
    if (!local) {
      return null;
    }
    const touched = field === 'password' ? this.password() !== '' : field === 'username' ? this.username() !== '' : this.displayName() !== '' || this.isEdit();
    return touched ? local : null;
  }

  protected close(): void {
    if (this.saving()) {
      return;
    }
    this.password.set('');
    this.closed.emit();
  }

  protected save(): void {
    if (!this.valid() || this.saving()) {
      return;
    }
    const original = this.user();
    this.serverErrors.set({});
    this.formError.set(null);
    if (original) {
      const patch = toPatch(original, { ...this.value(), displayName: this.displayName().trim() });
      if (Object.keys(patch).length === 0) {
        this.close();
        return;
      }
      this.saving.set(true);
      this.api.updateUser(original.id, patch).subscribe({
        next: (u) => this.onSaved(u),
        error: (e: ApiError) => this.onFailed(e),
      });
    } else {
      if (validatePassword(this.password()) !== null) {
        return;
      }
      this.saving.set(true);
      this.api.createUser(toCreateRequest(this.value())).subscribe({
        next: (u) => this.onSaved(u),
        error: (e: ApiError) => this.onFailed(e),
      });
    }
  }

  private onSaved(u: UserView): void {
    this.saving.set(false);
    this.password.set('');
    this.saved.emit(u);
  }

  private onFailed(e: ApiError): void {
    this.saving.set(false);
    const mapped = mapUserError(e);
    this.serverErrors.set(mapped.fields);
    this.formError.set(mapped.form);
  }
}

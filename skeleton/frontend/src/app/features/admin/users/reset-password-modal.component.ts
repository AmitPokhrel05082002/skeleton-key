import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { ApiService } from '../../../core/api/api.service';
import type { ApiError, UserView } from '../../../core/api/api.types';
import { mapUserError, validatePassword } from './user-form-model';

/** One password field. The value lives only in this component and is cleared on close and after the call. */
@Component({
  selector: 'sk-reset-password-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NzAlertModule, NzButtonModule, NzInputModule, NzModalModule],
  template: `
    <nz-modal
      [nzVisible]="user() !== null"
      [nzTitle]="'Reset password for ' + (user()?.username ?? '')"
      [nzClosable]="!saving()"
      [nzMaskClosable]="false"
      (nzOnCancel)="close()"
    >
      <ng-container *nzModalContent>
        @if (error(); as e) {
          <nz-alert class="sk-reset-error" nzType="error" [nzMessage]="e" role="alert" />
        }
        <label for="sk-reset-password">New password (8 to 72 bytes)</label>
        <input
          id="sk-reset-password"
          nz-input
          type="password"
          autocomplete="new-password"
          [ngModel]="password()"
          (ngModelChange)="onInput($event)"
          [disabled]="saving()"
        />
        @if (hint(); as h) {
          <div class="sk-field-error" role="alert">{{ h }}</div>
        }
      </ng-container>
      <div *nzModalFooter>
        <button nz-button type="button" class="sk-reset-cancel" [disabled]="saving()" (click)="close()">Cancel</button>
        <button
          nz-button
          nzType="primary"
          type="button"
          class="sk-reset-save"
          [nzLoading]="saving()"
          [disabled]="saving() || invalid()"
          (click)="save()"
        >
          Reset password
        </button>
      </div>
    </nz-modal>
  `,
  styles: `
    .sk-field-error {
      color: #ff4d4f;
      margin-top: 4px;
    }
    .sk-reset-error {
      margin-bottom: 12px;
    }
  `,
})
export class ResetPasswordModalComponent {
  private readonly api = inject(ApiService);

  /** The user whose password is reset; null keeps the modal closed. */
  readonly user = input<UserView | null>(null);
  readonly closed = output<void>();
  readonly done = output<UserView>();

  protected readonly password = signal('');
  protected readonly touched = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected invalid(): boolean {
    return validatePassword(this.password()) !== null;
  }

  protected hint(): string | null {
    return this.touched() ? validatePassword(this.password()) : null;
  }

  protected onInput(value: string): void {
    this.password.set(value);
    this.touched.set(true);
  }

  protected save(): void {
    const target = this.user();
    if (!target || this.invalid() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.resetPassword(target.id, this.password()).subscribe({
      next: () => {
        this.saving.set(false);
        this.reset();
        this.done.emit(target);
      },
      error: (e: ApiError) => {
        this.saving.set(false);
        const mapped = mapUserError(e);
        this.error.set(mapped.fields['password'] ?? mapped.form ?? 'The password could not be changed.');
      },
    });
  }

  protected close(): void {
    if (this.saving()) {
      return;
    }
    this.reset();
    this.closed.emit();
  }

  private reset(): void {
    this.password.set('');
    this.touched.set(false);
    this.error.set(null);
  }
}

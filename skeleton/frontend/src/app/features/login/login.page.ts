import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import type { ApiError } from '../../core/api/api.types';
import { SessionService } from '../../core/auth/session.service';
import { resolveDefaultApp } from '../../core/config/host-resolver';
import { RuntimeConfigService } from '../../core/config/runtime-config.service';
import { errorText } from '../../core/pure/error-text';
import { mapErrors } from '../../core/pure/error-mapper';

export const LOGIN_FAILED_MESSAGE = 'Sign in failed. Check your details and try again.';
const CONTROLS = ['username', 'password'] as const;

/** Accepts only same-origin relative paths: one leading `/`, no `//`, no backslash, no control chars. */
export function safeReturnTarget(value: unknown): string | null {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) {
    return null;
  }
  // eslint-disable-next-line no-control-regex
  if (value.includes('\\') || /[\u0000-\u001f]/.test(value)) {
    return null;
  }
  return value;
}

function notBlank(c: AbstractControl): ValidationErrors | null {
  return typeof c.value === 'string' && c.value.trim() !== '' ? null : { required: true };
}

/** Route `/login`. */
@Component({
  selector: 'sk-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, NzFormModule, NzInputModule, NzButtonModule],
  template: `
    <main class="sk-login">
      <h1><span class="sk-mark" aria-hidden="true"></span> <span class="sk-name">Skeleton Key</span></h1>
      <form nz-form nzLayout="vertical" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <nz-form-item>
          <nz-form-label nzFor="sk-login-username">User name</nz-form-label>
          <nz-form-control [nzErrorTip]="fieldError('username')">
            <input
              id="sk-login-username"
              nz-input
              formControlName="username"
              autocomplete="username"
              [attr.aria-invalid]="fieldError('username') ? 'true' : null"
            />
          </nz-form-control>
        </nz-form-item>
        <nz-form-item>
          <nz-form-label nzFor="sk-login-password">Password</nz-form-label>
          <nz-form-control [nzErrorTip]="fieldError('password')">
            <input
              id="sk-login-password"
              nz-input
              type="password"
              formControlName="password"
              autocomplete="current-password"
              [attr.aria-invalid]="fieldError('password') ? 'true' : null"
            />
          </nz-form-control>
        </nz-form-item>
        @if (formError()) {
          <p class="sk-login-error" role="alert">{{ formError() }}</p>
        }
        <button nz-button nzType="primary" type="submit" [disabled]="pending()">Sign in</button>
      </form>
    </main>
  `,
  styles: `
    .sk-login {
      max-width: 22rem;
      margin: 4rem auto;
      padding: 0 1rem;
    }
    .sk-login-error {
      color: #cf1322;
    }
  `,
})
export class LoginPage {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly config = inject(RuntimeConfigService);

  protected readonly form = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [notBlank] }),
    password: new FormControl('', { nonNullable: true, validators: [notBlank] }),
  });
  protected readonly pending = signal(false);
  protected readonly formError = signal<string | null>(null);
  private readonly serverErrors = signal<Record<string, string>>({});
  private readonly touchTick = signal(0);

  protected fieldError(name: (typeof CONTROLS)[number]): string {
    this.touchTick();
    const server = this.serverErrors()[name];
    if (server) {
      return server;
    }
    const c = this.form.controls[name];
    return c.touched && c.hasError('required') ? 'This field is required' : '';
  }

  protected submit(): void {
    if (this.pending()) {
      return;
    }
    this.formError.set(null);
    this.serverErrors.set({});
    const { username, password } = this.form.getRawValue();
    const blank = username.trim() === '' || password.trim() === '';
    this.form.markAllAsTouched();
    if (blank) {
      // markAllAsTouched does not notify OnPush; bump a signal so the template updates.
      this.touchTick.update((n) => n + 1);
      return;
    }
    this.pending.set(true);
    this.session.login(username.trim(), password).subscribe({
      next: () => {
        this.pending.set(false);
        void this.navigateOn();
      },
      error: (e: unknown) => {
        this.pending.set(false);
        this.showError(e as ApiError);
      },
    });
  }

  private showError(err: ApiError): void {
    if (err?.status === 400 && err.violations?.length) {
      const mapped = mapErrors(err, CONTROLS);
      const errors: Record<string, string> = {};
      for (const name of CONTROLS) {
        if (mapped.byControl[name].length) {
          errors[name] = mapped.byControl[name].join(' ');
        }
      }
      this.serverErrors.set(errors);
      this.formError.set(mapped.unmatched.length ? mapped.unmatched.join(' ') : null);
      return;
    }
    this.formError.set(err?.status === 401 ? LOGIN_FAILED_MESSAGE : err ? errorText(err) : LOGIN_FAILED_MESSAGE);
  }

  private navigateOn(): Promise<boolean> {
    const target = safeReturnTarget(this.route.snapshot.queryParamMap.get('returnUrl'));
    if (target) {
      return this.router.navigateByUrl(target, { replaceUrl: true });
    }
    const hosts = this.config.config()?.hosts ?? {};
    const app = resolveDefaultApp(hosts, window.location.host);
    return app ? this.router.navigate(['/app', app], { replaceUrl: true }) : this.router.navigateByUrl('/', { replaceUrl: true });
  }
}

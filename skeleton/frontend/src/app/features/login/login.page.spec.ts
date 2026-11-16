import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiError } from '../../core/api/api.types';
import { SessionService } from '../../core/auth/session.service';
import { RuntimeConfigService } from '../../core/config/runtime-config.service';
import { LOGIN_FAILED_MESSAGE, LoginPage, safeReturnTarget } from './login.page';

function setup(returnUrl: string | null, hosts: Record<string, string> = {}) {
  const login = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: SessionService, useValue: { login } },
      { provide: RuntimeConfigService, useValue: { config: () => ({ hosts }) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => returnUrl } } } },
    ],
  });
  const router = TestBed.inject(Router);
  const navigateByUrl = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(LoginPage);
  fixture.detectChanges();
  const page = fixture.componentInstance as unknown as {
    form: { setValue(v: { username: string; password: string }): void; controls: Record<string, unknown> };
    submit(): void;
    formError(): string | null;
    fieldError(n: 'username' | 'password'): string;
    pending(): boolean;
  };
  return { login, navigateByUrl, navigate, page };
}

const apiError = (e: Partial<ApiError>): ApiError =>
  ({ status: 400, code: 'X', message: 'm', requestId: 'r', violations: [], ...e }) as ApiError;

describe('LoginPage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('sends nothing for blank values and shows field messages', () => {
    const { login, page } = setup(null);
    page.submit();
    expect(login).not.toHaveBeenCalled();
    expect(page.fieldError('username')).not.toBe('');
    expect(page.fieldError('password')).not.toBe('');
  });

  it('shows one neutral message on 401', () => {
    const { login, page } = setup(null);
    login.mockReturnValue(throwError(() => apiError({ status: 401 })));
    page.form.setValue({ username: 'a', password: 'b' });
    page.submit();
    expect(page.formError()).toBe(LOGIN_FAILED_MESSAGE);
    expect(page.pending()).toBe(false);
  });

  it('maps 400 violations onto the controls', () => {
    const { login, page } = setup(null);
    login.mockReturnValue(
      throwError(() => apiError({ violations: [{ field: 'username', rule: 'size', message: 'Too long' }] })),
    );
    page.form.setValue({ username: 'a', password: 'b' });
    page.submit();
    expect(page.fieldError('username')).toBe('Too long');
    expect(page.fieldError('password')).toBe('');
  });

  it('navigates to a same-origin return target', () => {
    const { login, navigateByUrl, page } = setup('/app/x/views/v');
    login.mockReturnValue(of({}));
    page.form.setValue({ username: 'a', password: 'b' });
    page.submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/app/x/views/v', { replaceUrl: true });
  });

  it('ignores an external return target and uses the default app', () => {
    const { login, navigate, page } = setup('//evil.example', { [window.location.host.replace(/:\d*$/, '')]: 'APP' });
    login.mockReturnValue(of({}));
    page.form.setValue({ username: 'a', password: 'b' });
    page.submit();
    expect(navigate).toHaveBeenCalledWith(['/app', 'APP'], { replaceUrl: true });
  });

  it('goes to / when there is no default app', () => {
    const { login, navigateByUrl, page } = setup(null);
    login.mockReturnValue(of({}));
    page.form.setValue({ username: 'a', password: 'b' });
    page.submit();
    expect(navigateByUrl).toHaveBeenCalledWith('/', { replaceUrl: true });
  });
});

describe('safeReturnTarget', () => {
  it('accepts only single-slash relative paths', () => {
    expect(safeReturnTarget('/app/x?a=1')).toBe('/app/x?a=1');
    for (const bad of ['//evil.com', 'https://evil.com', '/\\evil', 'app/x', '', null, undefined]) {
      expect(safeReturnTarget(bad)).toBeNull();
    }
  });
});

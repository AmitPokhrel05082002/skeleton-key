import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RuntimeConfigService } from '../config/runtime-config.service';
import { adminGuard } from './admin.guard';
import { authGuard } from './auth.guard';
import { authInterceptor } from './auth.interceptor';
import { SessionService } from './session.service';

function setup(signedIn: boolean, admin = false) {
  const session = {
    session: () => (signedIn ? { token: 'tok' } : null),
    isPlatformAdmin: () => admin,
    clear: vi.fn(),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: SessionService, useValue: session },
      { provide: RuntimeConfigService, useValue: { apiBase: () => '/api' } },
    ],
  });
  return session;
}

describe('authInterceptor', () => {
  let session: ReturnType<typeof setup>;
  beforeEach(() => {
    session = setup(true);
  });

  it('adds the Bearer header to API urls only', () => {
    const http = TestBed.inject(HttpClient);
    const ctl = TestBed.inject(HttpTestingController);
    http.get('/api/records').subscribe();
    http.get('/api/public/apps/x').subscribe();
    http.get('/other/thing').subscribe();
    expect(ctl.expectOne('/api/records').request.headers.get('Authorization')).toBe('Bearer tok');
    expect(ctl.expectOne('/api/public/apps/x').request.headers.has('Authorization')).toBe(false);
    expect(ctl.expectOne('/other/thing').request.headers.has('Authorization')).toBe(false);
  });

  it('clears the session and redirects on 401, except for the login call', () => {
    const http = TestBed.inject(HttpClient);
    const ctl = TestBed.inject(HttpTestingController);
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });
    ctl.expectOne('/api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(session.clear).not.toHaveBeenCalled();
    http.get('/api/records').subscribe({ error: () => undefined });
    ctl.expectOne('/api/records').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(session.clear).toHaveBeenCalledTimes(1);
    expect(nav).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: expect.any(String) } });
  });
});

describe('guards', () => {
  it('authGuard redirects to login with returnUrl when signed out', () => {
    setup(false);
    const result = TestBed.runInInjectionContext(() => authGuard({} as never, { url: '/app/x' } as never));
    expect(TestBed.inject(Router).serializeUrl(result as never)).toBe('/login?returnUrl=%2Fapp%2Fx');
  });

  it('authGuard allows a signed-in user', () => {
    setup(true);
    expect(TestBed.runInInjectionContext(() => authGuard({} as never, { url: '/app/x' } as never))).toBe(true);
  });

  it('adminGuard needs the admin role', () => {
    setup(true, false);
    const denied = TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never));
    expect(TestBed.inject(Router).serializeUrl(denied as never)).toBe('/not-authorised');
    TestBed.resetTestingModule();
    setup(true, true);
    expect(TestBed.runInInjectionContext(() => adminGuard({} as never, {} as never))).toBe(true);
  });
});

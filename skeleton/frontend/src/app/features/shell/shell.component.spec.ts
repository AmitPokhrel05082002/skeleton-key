import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { NzSiderComponent } from 'ng-zorro-antd/layout';
import { RouterTestingHarness } from '@angular/router/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import { RestrictedDefinition } from '../../core/api/api.types';
import { ADMIN_ROLE, SessionService } from '../../core/auth/session.service';
import { DefinitionStore, buildMenu } from '../../core/definition/definition.store';
import { ThemeService } from '../../core/theme/theme.service';
import { ShellComponent } from './shell.component';
import { shellChildren } from './shell.routes';

@Component({ selector: 'sk-dummy', template: 'dummy' })
class DummyComponent {}

function def(partial: Partial<RestrictedDefinition> = {}): RestrictedDefinition {
  return {
    app: { code: 'DEMO', name: 'Demo App', branding: { primaryColor: '#336699', theme: 'light' } },
    roles: [],
    entities: [],
    forms: [
      { code: 'F_PUB', entity: 'E', public: true, sections: [] },
      { code: 'F_STAFF', label: 'Staff form', entity: 'E', roles: ['STAFF'], sections: [] },
      { code: 'F_OTHER', entity: 'E', roles: ['OTHER'], sections: [] },
    ],
    views: [
      { code: 'V_ONE', label: 'First', type: 'TABLE', entity: 'E' },
      { code: 'V_TWO', type: 'KANBAN', entity: 'E' },
    ],
    workflows: [],
    ...partial,
  };
}

describe('buildMenu', () => {
  it('lists views in order, labelled by label or code', () => {
    const items = buildMenu(def(), ['STAFF']).filter((i) => i.kind === 'view');
    expect(items.map((i) => i.label)).toEqual(['First', 'V_TWO']);
    expect(items[0].link).toEqual(['/app', 'DEMO', 'views', 'V_ONE']);
  });

  it('adds New entries only for forms the user can submit', () => {
    const labels = buildMenu(def(), ['STAFF'])
      .filter((i) => i.kind === 'new')
      .map((i) => i.label);
    expect(labels).toEqual(['F_PUB', 'Staff form']);
  });

  it('keeps the new-form links', () => {
    const items = buildMenu(def(), ['STAFF']).filter((i) => i.kind === 'new');
    expect(items[1].link).toEqual(['/app', 'DEMO', 'forms', 'F_STAFF', 'new']);
  });

  it('adds Admin only for the platform admin', () => {
    expect(buildMenu(def(), ['STAFF']).some((i) => i.kind === 'admin')).toBe(false);
    const admin = buildMenu(def(), [ADMIN_ROLE]);
    expect(admin[admin.length - 1]).toMatchObject({ kind: 'admin', label: 'Admin' });
  });
});

describe('ShellComponent', () => {
  let definition$: Subject<RestrictedDefinition>;
  let api: { definition: ReturnType<typeof vi.fn>; login: ReturnType<typeof vi.fn>; me: ReturnType<typeof vi.fn> };
  let theme: { apply: ReturnType<typeof vi.fn>; applyBranding: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    definition$ = new Subject<RestrictedDefinition>();
    api = {
      definition: vi.fn(() => definition$),
      login: vi.fn(() => of({ token: 't', expiresInSeconds: 60, userId: 'u1', displayName: 'Una', roles: ['STAFF'] })),
      me: vi.fn(() => of({ userId: 'u1', displayName: 'Una', roles: ['STAFF'] })),
    };
    theme = { apply: vi.fn(() => Promise.resolve()), applyBranding: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'app/:app',
            component: ShellComponent,
            children: [
              ...shellChildren.filter((r) => r.path !== 'views/:view'),
              { path: 'views/:view', component: DummyComponent },
            ],
          },
          { path: 'login', component: DummyComponent },
        ]),
        { provide: ApiService, useValue: api },
        { provide: ThemeService, useValue: theme },
      ],
    });
  });

  afterEach(() => {
    sessionStorage.clear();
    TestBed.inject(DefinitionStore).reset();
  });

  async function open(): Promise<{ el: HTMLElement; harness: RouterTestingHarness }> {
    await new Promise<void>((resolve, reject) =>
      TestBed.inject(SessionService)
        .login('una', 'pw')
        .subscribe({ next: () => resolve(), error: reject }),
    );
    const harness = await RouterTestingHarness.create('/app/DEMO');
    return { el: harness.routeNativeElement as HTMLElement, harness };
  }

  it('shows a loading indicator while the definition is pending', async () => {
    const { el } = await open();
    expect(el.textContent).toContain('Loading');
    expect(el.querySelector('.sk-header')).toBeNull();
  });

  it('shows the header with the app name, user and sign out when loaded, and applies branding', async () => {
    const { el, harness } = await open();
    definition$.next(def());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const header = el.querySelector('.sk-header') as HTMLElement;
    expect(header).not.toBeNull();
    expect(header.textContent).toContain('Demo App');
    expect(header.textContent).toContain('Una');
    expect(header.textContent).toContain('Sign out');
    expect(theme.apply).toHaveBeenCalledWith('light');
    expect(theme.applyBranding).toHaveBeenCalledWith('#336699');
    expect(el.querySelectorAll('li[nz-menu-item]').length).toBe(4);
  });

  it('groups the submittable forms under "New" without a "New " prefix', async () => {
    const { el, harness } = await open();
    definition$.next(def());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const items = Array.from(el.querySelectorAll('li[nz-menu-item]')).map((i) => (i.textContent ?? '').trim());
    expect(items.some((t) => t.startsWith('New '))).toBe(false);
    const group = el.querySelector('li[nz-menu-group]') as HTMLElement;
    expect(group).not.toBeNull();
    expect(group.querySelector('.ant-menu-item-group-title')?.textContent?.trim()).toBe('New');
    const inGroup = Array.from(group.querySelectorAll('li[nz-menu-item]')).map((i) => (i.textContent ?? '').trim());
    expect(inGroup).toEqual(['F_PUB', 'Staff form']);
  });

  it('has a responsive sider, a toggle button and a shrinkable content area', async () => {
    const { el, harness } = await open();
    definition$.next(def());
    await harness.fixture.whenStable();
    harness.detectChanges();
    const siderDebug = harness.fixture.debugElement.query(By.directive(NzSiderComponent));
    const sc = siderDebug.componentInstance as NzSiderComponent;
    expect(sc.nzBreakpoint).toBe('md');
    expect(sc.nzCollapsedWidth).toBe(0);
    expect(sc.nzCollapsible).toBe(true);
    expect(sc.nzTrigger).toBeNull();

    const btn = el.querySelector('button.sk-nav-toggle') as HTMLButtonElement;
    expect(btn.getAttribute('aria-label')).toBe('Toggle navigation');
    expect(btn.getAttribute('aria-controls')).toBe('sk-nav-menu');
    expect(el.querySelector('#sk-nav-menu')).not.toBeNull();
    // The initial state depends on the (jsdom) viewport; assert the flip relative to it.
    const initial = sc.nzCollapsed;
    expect(btn.getAttribute('aria-expanded')).toBe(String(!initial));
    btn.click();
    harness.detectChanges();
    expect(sc.nzCollapsed).toBe(!initial);
    expect(btn.getAttribute('aria-expanded')).toBe(String(initial));
    btn.click();
    harness.detectChanges();
    expect(sc.nzCollapsed).toBe(initial);
    expect(btn.getAttribute('aria-expanded')).toBe(String(!initial));

    expect((el.querySelector('nz-content.sk-content') as HTMLElement).style.minWidth).toBe('0px');
    expect((el.querySelector('nz-layout.sk-main') as HTMLElement).style.minWidth).toBe('0px');
  });

  it('shows "app not available" and no menu on 404', async () => {
    api.definition.mockReturnValue(throwError(() => ({ status: 404, error: {} })));
    const { el, harness } = await open();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(el.textContent).toContain('App not available');
    expect(el.querySelector('ul[nz-menu]')).toBeNull();
  });

  it('shows "app not available" on 409', async () => {
    api.definition.mockReturnValue(throwError(() => ({ status: 409, error: {} })));
    const { el, harness } = await open();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(el.textContent).toContain('App not available');
  });

  it('Sign out clears the session and goes to /login', async () => {
    const { el, harness } = await open();
    definition$.next(def());
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(TestBed.inject(SessionService).session()).not.toBeNull();
    const button = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Sign out'));
    expect(button).toBeDefined();
    button?.click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(SessionService).session()).toBeNull();
    expect(TestBed.inject(Router).url).toBe('/login');
  });

  it('home redirects to the first view', async () => {
    const { harness } = await open();
    definition$.next(def());
    await harness.fixture.whenStable();
    harness.detectChanges();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/app/DEMO/views/V_ONE');
  });

  it('home shows an empty state when there are no views', async () => {
    const { el, harness } = await open();
    definition$.next(def({ views: [] }));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(el.textContent).toContain('no views');
  });
});

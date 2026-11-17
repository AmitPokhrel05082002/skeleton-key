import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../../app.routes';
import { ApiService } from '../../core/api/api.service';
import { VersionRef } from '../../core/api/api.types';
import { adminGuard } from '../../core/auth/admin.guard';
import { authGuard } from '../../core/auth/auth.guard';
import { adminChildren } from './admin.routes';
import { DefinitionsListComponent } from './definitions-list.component';

const VERSIONS: VersionRef[] = [
  { id: 'id-1', version: 1, status: 'PUBLISHED', contentHash: 'hash-one' },
  { id: 'id-2', version: 2, status: 'DRAFT', contentHash: 'hash-two' },
];

describe('admin route config', () => {
  it('guards /admin with authGuard and adminGuard and lazy-loads shell and children', () => {
    const route = routes.find((r) => r.path === 'admin');
    expect(route).toBeDefined();
    expect(route?.canActivate).toEqual([authGuard, adminGuard]);
    expect(route?.loadComponent).toBeTypeOf('function');
    expect(route?.loadChildren).toBeTypeOf('function');
    expect(routes.findIndex((r) => r.path === 'admin')).toBeLessThan(routes.findIndex((r) => r.path === '**'));
  });

  it('serves the definitions list at the empty child path', () => {
    expect(adminChildren[0].path).toBe('');
  });
});

describe('DefinitionsListComponent', () => {
  let api: {
    definitionApps: ReturnType<typeof vi.fn>;
    definitionVersions: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      definitionApps: vi.fn(() => of(['GRIEVANCE', 'ASSETS'])),
      definitionVersions: vi.fn(() => of(VERSIONS)),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'admin', component: DefinitionsListComponent },
          { path: 'admin/editor/new', component: DefinitionsListComponent },
          { path: 'admin/editor/:id', component: DefinitionsListComponent },
        ]),
        { provide: ApiService, useValue: api },
      ],
    });
  });

  async function open(): Promise<{ el: HTMLElement; harness: RouterTestingHarness }> {
    const harness = await RouterTestingHarness.create('/admin');
    await harness.fixture.whenStable();
    harness.detectChanges();
    return { el: harness.routeNativeElement as HTMLElement, harness };
  }

  function button(el: HTMLElement, text: string): HTMLButtonElement {
    const b = Array.from(el.querySelectorAll('button')).find((x) => x.textContent?.trim() === text);
    expect(b, `button ${text}`).toBeDefined();
    return b as HTMLButtonElement;
  }

  it('lists app codes and renders version, status, hash and id of the chosen app', async () => {
    const { el, harness } = await open();
    expect(el.textContent).toContain('GRIEVANCE');
    expect(el.textContent).toContain('ASSETS');
    button(el, 'GRIEVANCE').click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(api.definitionVersions).toHaveBeenCalledWith('GRIEVANCE');
    const rows = Array.from(el.querySelectorAll('tbody tr'));
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('2');
    expect(rows[0].textContent).toContain('DRAFT');
    expect(rows[0].textContent).toContain('hash-two');
    expect(rows[0].textContent).toContain('id-2');
    expect(rows[1].textContent).toContain('PUBLISHED');
    expect(rows[1].textContent).toContain('hash-one');
    expect(rows[1].textContent).toContain('id-1');
  });

  it('Open navigates to the editor route of that version id', async () => {
    const { el, harness } = await open();
    button(el, 'ASSETS').click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    el.querySelector<HTMLButtonElement>('tbody tr button')?.click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/admin/editor/id-2');
  });

  it('New definition navigates to the new editor route', async () => {
    const { el, harness } = await open();
    button(el, 'New definition').click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/admin/editor/new');
  });

  it('shows the not authorised message on 403', async () => {
    api.definitionApps.mockReturnValue(throwError(() => ({ status: 403, code: 'FORBIDDEN', message: 'no', violations: [] })));
    const { el } = await open();
    expect(el.textContent).toContain('Not authorised');
    expect(el.querySelector('ul[aria-label="Apps"]')).toBeNull();
  });

  it('shows the not authorised message when the versions call returns 403', async () => {
    api.definitionVersions.mockReturnValue(throwError(() => ({ status: 403, code: 'FORBIDDEN', message: 'no', violations: [] })));
    const { el, harness } = await open();
    button(el, 'GRIEVANCE').click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(el.textContent).toContain('Not authorised');
  });
});

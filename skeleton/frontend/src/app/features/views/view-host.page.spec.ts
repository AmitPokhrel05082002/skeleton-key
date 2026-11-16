import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { RestrictedDefinition } from '../../core/api/api.types';
import { DefinitionStore, type DefinitionStatus } from '../../core/definition/definition.store';
import { ViewHostPage } from './view-host.page';

const def: RestrictedDefinition = {
  app: { code: 'APP', name: 'App' },
  roles: [],
  entities: [{ code: 'E', label: 'E', fields: [{ code: 'a', label: 'A', type: 'TEXT' }] }],
  forms: [],
  views: [
    { code: 'T', type: 'TABLE', entity: 'E' },
    { code: 'K', type: 'KANBAN', entity: 'E' },
    { code: 'M', type: 'MY_TASKS', entity: 'E' },
    { code: 'ORPHAN', type: 'TABLE', entity: 'GONE' },
  ],
  workflows: [],
} as unknown as RestrictedDefinition;

function setup(initial: string, status: DefinitionStatus = 'ready') {
  const params = new BehaviorSubject(convertToParamMap({ view: initial }));
  const statusSig = signal<DefinitionStatus>(status);
  const listRecords = vi.fn().mockReturnValue(of({ items: [], page: 1, size: 20, total: 0 }));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: { listRecords } },
      {
        provide: DefinitionStore,
        useValue: { status: statusSig, definition: signal(status === 'ready' ? def : null) },
      },
      { provide: ActivatedRoute, useValue: { paramMap: params.asObservable(), snapshot: { paramMap: params.value } } },
    ],
  });
  const fixture = TestBed.createComponent(ViewHostPage);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, params };
}

describe('ViewHostPage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('renders sk-dynamic-table for TABLE', () => {
    expect(setup('T').el.querySelector('sk-dynamic-table')).not.toBeNull();
  });

  it('renders sk-kanban for KANBAN', () => {
    expect(setup('K').el.querySelector('sk-kanban')).not.toBeNull();
  });

  it('renders sk-my-tasks for MY_TASKS', () => {
    expect(setup('M').el.querySelector('sk-my-tasks')).not.toBeNull();
  });

  it('shows "view not available" for an unknown view code or a missing entity', () => {
    expect(setup('NOPE').el.textContent).toContain('View not available');
    TestBed.resetTestingModule();
    const { el } = setup('ORPHAN');
    expect(el.textContent).toContain('View not available');
    expect(el.querySelector('sk-dynamic-table')).toBeNull();
  });

  it('shows no message while the definition is loading', () => {
    const { el } = setup('T', 'loading');
    expect(el.textContent).not.toContain('View not available');
  });

  it('re-renders when the route param changes', () => {
    const { fixture, el, params } = setup('T');
    params.next(convertToParamMap({ view: 'K' }));
    fixture.detectChanges();
    expect(el.querySelector('sk-kanban')).not.toBeNull();
    expect(el.querySelector('sk-dynamic-table')).toBeNull();
  });
});

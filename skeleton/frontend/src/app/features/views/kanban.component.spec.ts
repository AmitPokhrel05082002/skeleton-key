import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { EntityDef, Page, RecordSummary, ViewDef, WorkflowPresentation } from '../../core/api/api.types';
import { KanbanComponent } from './kanban.component';

const view: ViewDef = { code: 'BOARD', type: 'KANBAN', entity: 'GRIEVANCE' };
const entity: EntityDef = {
  code: 'GRIEVANCE',
  label: 'Grievance',
  titleField: 'subject',
  fields: [{ code: 'subject', label: 'Subject', type: 'TEXT' }],
};
const workflows: WorkflowPresentation[] = [
  { code: 'OTHER_WF', entity: 'OTHER', statuses: [{ label: 'Nope', category: 'OPEN' }], steps: [] },
  {
    code: 'WF',
    entity: 'GRIEVANCE',
    statuses: [
      { label: 'New', category: 'OPEN', color: '#ff0000' },
      { label: 'Closed', category: 'DONE' },
    ],
    steps: [],
  },
];

const rec = (id: string, status: string | null, extra: Partial<RecordSummary> = {}): RecordSummary => ({
  id,
  data: { subject: `S-${id}` },
  referenceNo: `GRV-${id}`,
  instanceId: null,
  status,
  ...extra,
});

function page(items: RecordSummary[], total: number, p = 1): Page<RecordSummary> {
  return { items, page: p, size: 50, total };
}

function setup(pages: Page<RecordSummary>[]) {
  const listRecords = vi.fn();
  pages.forEach((p) => listRecords.mockReturnValueOnce(of(p)));
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ApiService, useValue: { listRecords } }],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(KanbanComponent);
  fixture.componentRef.setInput('view', view);
  fixture.componentRef.setInput('entity', entity);
  fixture.componentRef.setInput('workflows', workflows);
  fixture.componentRef.setInput('app', 'GRIEVANCE_APP');
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, el, listRecords, navigate };
}

const headers = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLElement>('header.sk-kanban-header'));

describe('KanbanComponent', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('builds columns from the statuses of the workflow of the view entity and requests page 1 size 50', () => {
    const { el, listRecords } = setup([page([rec('1', 'New')], 1)]);
    expect(headers(el).map((h) => h.textContent?.trim())).toEqual(['New (1)', 'Closed (0)']);
    expect(listRecords).toHaveBeenCalledWith('GRIEVANCE_APP', 'GRIEVANCE', {
      page: 1,
      size: 50,
      view: 'BOARD',
      filters: {},
    });
  });

  it('shows the Other column only when a record matches no status', () => {
    expect(headers(setup([page([rec('1', 'New')], 1)]).el)).toHaveLength(2);
    TestBed.resetTestingModule();
    const { el } = setup([page([rec('1', 'New'), rec('2', 'Weird'), rec('3', null)], 3)]);
    expect(headers(el).map((h) => h.textContent?.trim())).toEqual(['New (1)', 'Closed (0)', 'Other (2)']);
  });

  it('keeps the header class and applies status colours inline only when set', () => {
    const { el } = setup([page([], 0)]);
    const [withColor, without] = headers(el);
    expect(withColor.classList.contains('sk-kanban-header')).toBe(true);
    expect(withColor.style.backgroundColor).not.toBe('');
    expect(withColor.style.color).not.toBe('');
    expect(without.classList.contains('sk-kanban-header')).toBe(true);
    expect(without.style.backgroundColor).toBe('');
    expect(without.style.color).toBe('');
  });

  it('shows reference, title, assignee name with id fallback and status chip', () => {
    const { el } = setup([
      page([rec('1', 'New', { assigneeName: 'Ana', assignee: 'u1' }), rec('2', 'New', { assignee: 'u2' })], 2),
    ]);
    const text = el.textContent ?? '';
    expect(text).toContain('GRV-1');
    expect(text).toContain('S-1');
    expect(text).toContain('Ana');
    expect(text).toContain('u2');
    expect(el.querySelectorAll('sk-status-chip').length).toBe(2);
  });

  it('shows Load more while total exceeds loaded and appends the next page', () => {
    const { fixture, el, listRecords } = setup([
      page([rec('1', 'New')], 2),
      page([rec('2', 'Closed')], 2, 2),
    ]);
    const more = () => Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Load more'));
    expect(more()).toBeTruthy();
    more()?.click();
    fixture.detectChanges();
    expect(listRecords).toHaveBeenLastCalledWith('GRIEVANCE_APP', 'GRIEVANCE', {
      page: 2,
      size: 50,
      view: 'BOARD',
      filters: {},
    });
    expect(el.querySelectorAll('.card').length).toBe(2);
    expect(more()).toBeUndefined();
  });

  it('navigates to the record detail on card click and has no drag behaviour', () => {
    const { el, navigate } = setup([page([rec('abc', 'New')], 1)]);
    const card = el.querySelector<HTMLElement>('.card');
    expect(card?.getAttribute('draggable')).toBeNull();
    card?.click();
    expect(navigate).toHaveBeenCalledWith(['/app', 'GRIEVANCE_APP', 'records', 'GRIEVANCE', 'abc']);
  });
});

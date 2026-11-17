import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { ApiError, EntityDef, Page, RecordSummary, ViewDef } from '../../core/api/api.types';
import { DynamicTableComponent } from './dynamic-table.component';

const entity: EntityDef = {
  code: 'GRIEVANCE',
  label: 'Grievance',
  titleField: 'subject',
  fields: [
    { code: 'subject', label: 'Subject', type: 'TEXT' },
    {
      code: 'category',
      label: 'Category',
      type: 'SELECT',
      options: [
        { value: 'A', label: 'Alpha' },
        { value: 'B', label: 'Beta' },
      ],
    },
  ],
};
const view: ViewDef = {
  code: 'ALL',
  type: 'TABLE',
  entity: 'GRIEVANCE',
  columns: ['subject', 'category', '$status', '$assignee'],
  filters: ['subject'],
  defaultSort: { field: '$createdAt', direction: 'DESC' },
};

const rec = (id: string, extra: Partial<RecordSummary> = {}): RecordSummary => ({
  id,
  data: { subject: `S-${id}`, category: 'A' },
  referenceNo: `GRV-${id}`,
  instanceId: null,
  status: 'New',
  statusColor: '#ff0000',
  assignee: 'u1',
  assigneeName: 'Ana',
  ...extra,
});

const page = (items: RecordSummary[], total = items.length): Page<RecordSummary> => ({
  items,
  page: 1,
  size: 20,
  total,
});

type Component = DynamicTableComponent & {
  onSort(key: string, order: string | null): void;
  setFilter(key: string, value: string): void;
  onPage(page: number): void;
  onSize(size: number): void;
};

function setup(listRecords = vi.fn().mockReturnValue(of(page([rec('1')])))) {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ApiService, useValue: { listRecords } }],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(DynamicTableComponent);
  fixture.componentRef.setInput('app', 'APP');
  fixture.componentRef.setInput('view', view);
  fixture.componentRef.setInput('entity', entity);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  const cmp = fixture.componentInstance as Component;
  const tick = () => {
    fixture.detectChanges();
    fixture.detectChanges();
  };
  return { fixture, el, listRecords, navigate, cmp, tick };
}

describe('DynamicTableComponent', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('requests page 1, size 20 and the default sort, and renders the rows', () => {
    const { el, listRecords } = setup();
    expect(listRecords).toHaveBeenCalledTimes(1);
    expect(listRecords).toHaveBeenCalledWith('APP', 'GRIEVANCE', {
      page: 1,
      size: 20,
      view: 'ALL',
      filters: {},
      sort: '$createdAt,desc',
    });
    const text = el.textContent ?? '';
    expect(text).toContain('S-1');
    expect(text).toContain('Alpha');
    expect(text).toContain('Ana');
    expect(el.querySelectorAll('sk-status-chip').length).toBe(1);
  });

  it('renders the table inside a horizontally scrollable container', () => {
    const { el } = setup();
    const content = el.querySelector('.ant-table-content') as HTMLElement;
    const table = el.querySelector('table') as HTMLElement;
    expect(content).not.toBeNull();
    expect(content.style.overflowX).toBe('auto');
    expect(table.style.width).toBe('max-content');
  });

  it('sort resets to page 1 and sends the user sort instead of the default', () => {
    const { cmp, listRecords, tick } = setup();
    cmp.onPage(3);
    tick();
    expect(listRecords).toHaveBeenLastCalledWith('APP', 'GRIEVANCE', expect.objectContaining({ page: 3 }));
    cmp.onSort('subject', 'ascend');
    tick();
    expect(listRecords).toHaveBeenLastCalledWith(
      'APP',
      'GRIEVANCE',
      expect.objectContaining({ page: 1, sort: 'subject,asc' }),
    );
  });

  it('filter change resets to page 1 and sends only non-empty filters', () => {
    const { cmp, listRecords, tick } = setup();
    cmp.onPage(2);
    tick();
    cmp.setFilter('subject', 'water');
    tick();
    expect(listRecords).toHaveBeenLastCalledWith(
      'APP',
      'GRIEVANCE',
      expect.objectContaining({ page: 1, filters: { subject: 'water' } }),
    );
    cmp.setFilter('subject', '');
    tick();
    expect(listRecords).toHaveBeenLastCalledWith('APP', 'GRIEVANCE', expect.objectContaining({ filters: {} }));
  });

  it('page size change requests the new size', () => {
    const { cmp, listRecords, tick } = setup();
    cmp.onSize(50);
    tick();
    expect(listRecords).toHaveBeenLastCalledWith('APP', 'GRIEVANCE', expect.objectContaining({ page: 1, size: 50 }));
  });

  it('keeps the previous rows and shows violations on a 400', () => {
    const err: ApiError = {
      status: 400,
      code: 'VALIDATION',
      message: 'bad',
      violations: [{ field: 'sort', rule: 'unknown', message: 'Cannot sort by that column' }],
    };
    const listRecords = vi
      .fn()
      .mockReturnValueOnce(of(page([rec('1')])))
      .mockReturnValueOnce(throwError(() => err));
    const { el, cmp, tick } = setup(listRecords);
    cmp.setFilter('subject', 'x');
    tick();
    const text = el.textContent ?? '';
    expect(text).toContain('Cannot sort by that column');
    expect(text).toContain('S-1');
    expect(el.querySelector('sk-error-alert')).toBeTruthy();
  });

  it('shows an empty state when there are no items', () => {
    const { el } = setup(vi.fn().mockReturnValue(of(page([]))));
    expect(el.querySelector('sk-empty-state')).toBeTruthy();
    expect(el.querySelector('tbody tr.row')).toBeNull();
  });

  it('navigates to the record detail on row click', () => {
    const { el, navigate } = setup(vi.fn().mockReturnValue(of(page([rec('abc')]))));
    el.querySelector<HTMLElement>('tr.row')?.click();
    expect(navigate).toHaveBeenCalledWith(['/app', 'APP', 'records', 'GRIEVANCE', 'abc']);
  });
});

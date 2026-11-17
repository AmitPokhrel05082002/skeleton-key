import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api/api.service';
import type { EntityDef, Page, RecordSummary, ViewDef } from '../../core/api/api.types';
import { MyTasksComponent } from './my-tasks.component';

const entity: EntityDef = {
  code: 'GRIEVANCE',
  label: 'Grievance',
  titleField: 'subject',
  fields: [{ code: 'subject', label: 'Subject', type: 'TEXT' }],
};

const rec = (id: string, extra: Partial<RecordSummary> = {}): RecordSummary => ({
  id,
  data: { subject: `S-${id}` },
  referenceNo: `GRV-${id}`,
  instanceId: 'i' + id,
  status: 'New',
  stepLabel: 'Review',
  ...extra,
});

const page = (items: RecordSummary[]): Page<RecordSummary> => ({ items, page: 1, size: 50, total: items.length });

function setup(view: ViewDef, items: RecordSummary[]) {
  const listRecords = vi.fn().mockReturnValue(of(page(items)));
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: ApiService, useValue: { listRecords } }],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(MyTasksComponent);
  fixture.componentRef.setInput('app', 'APP');
  fixture.componentRef.setInput('view', view);
  fixture.componentRef.setInput('entity', entity);
  fixture.detectChanges();
  return { el: fixture.nativeElement as HTMLElement, listRecords, navigate };
}

const view: ViewDef = { code: 'TASKS', type: 'MY_TASKS', entity: 'GRIEVANCE' };

describe('MyTasksComponent', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('sorts by $dueAt ascending by default and uses the view code', () => {
    const { listRecords } = setup(view, [rec('1')]);
    expect(listRecords).toHaveBeenCalledWith('APP', 'GRIEVANCE', {
      page: 1,
      size: 50,
      view: 'TASKS',
      filters: {},
      sort: '$dueAt,asc',
    });
  });

  it('uses the view defaultSort when declared', () => {
    const { listRecords } = setup({ ...view, defaultSort: { field: '$createdAt', direction: 'DESC' } }, [rec('1')]);
    expect(listRecords.mock.calls[0][2].sort).toBe('$createdAt,desc');
  });

  it('shows reference, title, step label, status chip and SLA badge', () => {
    const { el } = setup(view, [rec('1', { dueAt: '2020-01-01T00:00:00Z' })]);
    const text = el.textContent ?? '';
    expect(text).toContain('GRV-1');
    expect(text).toContain('S-1');
    expect(text).toContain('Review');
    expect(el.querySelectorAll('sk-status-chip').length).toBe(1);
    expect(el.querySelectorAll('sk-sla-badge').length).toBe(1);
  });

  it('renders the table inside a horizontally scrollable container', () => {
    const { el } = setup(view, [rec('1')]);
    const content = el.querySelector('.ant-table-content') as HTMLElement;
    expect(content).not.toBeNull();
    expect(content.style.overflowX).toBe('auto');
    expect((el.querySelector('table') as HTMLElement).style.width).toBe('max-content');
  });

  it('shows an empty state when there are no tasks', () => {
    const { el } = setup(view, []);
    expect(el.textContent).toContain('No tasks');
    expect(el.querySelector('table')).toBeNull();
  });

  it('navigates to the record detail on row click', () => {
    const { el, navigate } = setup(view, [rec('abc')]);
    el.querySelector<HTMLElement>('tr.row')?.click();
    expect(navigate).toHaveBeenCalledWith(['/app', 'APP', 'records', 'GRIEVANCE', 'abc']);
  });
});

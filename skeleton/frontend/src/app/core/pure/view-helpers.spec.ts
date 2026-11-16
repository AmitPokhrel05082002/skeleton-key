import { describe, expect, it } from 'vitest';
import type { EntityDef, FieldDef, RecordSummary, StatusDef, ViewDef } from '../api/api.types';
import {
  buildListQuery,
  formatCell,
  groupByStatus,
  resolveColumns,
  withFilters,
  withSort,
  type ListState,
} from './view-helpers';

const field = (f: Partial<FieldDef> & Pick<FieldDef, 'type'>): FieldDef => ({ code: 'f', label: 'F', ...f });

describe('formatCell', () => {
  const opts = [
    { value: 'a', label: 'Alpha' },
    { value: 'b', label: 'Beta' },
  ];
  it('maps option labels, falling back to the raw value', () => {
    expect(formatCell(field({ type: 'SELECT', options: opts }), 'a')).toBe('Alpha');
    expect(formatCell(field({ type: 'SELECT', options: opts }), 'zz')).toBe('zz');
    expect(formatCell(field({ type: 'MULTISELECT', options: opts }), ['a', 'b', 'q'])).toBe('Alpha, Beta, q');
  });
  it('shows Yes or No for booleans', () => {
    expect(formatCell(field({ type: 'BOOLEAN' }), true)).toBe('Yes');
    expect(formatCell(field({ type: 'BOOLEAN' }), false)).toBe('No');
  });
  it('localises dates without shifting the day and keeps unparseable text', () => {
    expect(formatCell(field({ type: 'DATE' }), '2026-03-05')).toBe(new Date(2026, 2, 5).toLocaleDateString());
    expect(formatCell(field({ type: 'DATETIME' }), '2026-03-05T10:00:00Z')).toBe(
      new Date('2026-03-05T10:00:00Z').toLocaleString(),
    );
    expect(formatCell(field({ type: 'DATE' }), 'not a date')).toBe('not a date');
  });
  it('returns plain text and never throws', () => {
    expect(formatCell(field({ type: 'NUMBER' }), 42)).toBe('42');
    expect(formatCell(undefined, 'x')).toBe('x');
    expect(formatCell(field({ type: 'TEXT' }), null)).toBe('');
    expect(formatCell(field({ type: 'TEXT' }), { a: 1 })).toBe('{"a":1}');
    const cyclic: Record<string, unknown> = {};
    cyclic['self'] = cyclic;
    expect(() => formatCell(field({ type: 'TEXT' }), cyclic)).not.toThrow();
  });
});

describe('resolveColumns', () => {
  const entity: EntityDef = {
    code: 'E',
    label: 'E',
    titleField: 'title',
    fields: [
      { code: 'title', label: 'Title', type: 'TEXT' },
      { code: 'city', label: 'City', type: 'TEXT' },
    ],
  };
  const view = (columns?: string[]): ViewDef => ({ code: 'V', type: 'TABLE', entity: 'E', columns });
  it('uses declared columns in order with field and system labels', () => {
    const cols = resolveColumns(view(['city', '$status', 'title']), entity);
    expect(cols.map((c) => [c.key, c.label])).toEqual([
      ['city', 'City'],
      ['$status', 'Status'],
      ['title', 'Title'],
    ]);
    expect(cols[0].field?.code).toBe('city');
  });
  it('defaults to titleField plus $reference', () => {
    expect(resolveColumns(view(), entity).map((c) => c.key)).toEqual(['title', '$reference']);
    expect(resolveColumns(view([]), { ...entity, titleField: undefined }).map((c) => c.key)).toEqual(['$reference']);
  });
});

describe('buildListQuery', () => {
  const base: ListState = { view: 'V', page: 3, size: 20, filters: {} };
  it('keeps only non-empty filters', () => {
    const q = buildListQuery({ ...base, filters: { a: 'x', b: '', c: '  ', d: null, e: undefined } });
    expect(q.filters).toEqual({ a: 'x' });
    expect(q).toMatchObject({ view: 'V', page: 3, size: 20 });
  });
  it('uses defaultSort only when the user has not sorted', () => {
    const defaultSort = { field: '$createdAt', direction: 'DESC' as const };
    expect(buildListQuery({ ...base, defaultSort }).sort).toBe('$createdAt,desc');
    expect(buildListQuery({ ...base, defaultSort, sort: { field: 'city', direction: 'ASC' } }).sort).toBe('city,asc');
    expect(buildListQuery(base).sort).toBeUndefined();
  });
  it('resets to page 1 on sort or filter change', () => {
    expect(withSort(base, { field: 'city', direction: 'ASC' }).page).toBe(1);
    expect(withFilters(base, { a: 'x' }).page).toBe(1);
  });
});

describe('groupByStatus', () => {
  const statuses: StatusDef[] = [
    { label: 'Open', category: 'OPEN' },
    { label: 'Done', category: 'DONE' },
  ];
  const rec = (id: string, status?: string | null): RecordSummary => ({
    id,
    data: {},
    referenceNo: id,
    instanceId: null,
    status,
  });
  it('groups in declaration order keeping input order, with Other last when non-empty', () => {
    const cols = groupByStatus([rec('1', 'Done'), rec('2', 'Open'), rec('3', 'Weird'), rec('4', 'Done'), rec('5')], statuses);
    expect(cols.map((c) => c.label)).toEqual(['Open', 'Done', 'Other']);
    expect(cols.map((c) => c.records.map((r) => r.id))).toEqual([['2'], ['1', '4'], ['3', '5']]);
  });
  it('omits Other when empty and keeps empty declared columns', () => {
    const cols = groupByStatus([rec('1', 'Open')], statuses);
    expect(cols.map((c) => c.label)).toEqual(['Open', 'Done']);
    expect(cols[1].records).toEqual([]);
  });
});

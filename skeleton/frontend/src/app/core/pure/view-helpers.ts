import type { EntityDef, FieldDef, ListQuery, RecordSummary, StatusDef, ViewDef } from '../api/api.types';

/** Fixed labels of the `$` system columns. */
export const SYSTEM_COLUMN_LABELS: Readonly<Record<string, string>> = Object.freeze({
  $reference: 'Reference',
  $status: 'Status',
  $step: 'Step',
  $assignee: 'Assignee',
  $createdAt: 'Created',
  $updatedAt: 'Updated',
  $dueAt: 'Due',
  $createdBy: 'Created by',
});

export interface ColumnSpec {
  /** Field code or `$` system column. */
  key: string;
  label: string;
  /** The field definition for field columns, undefined for `$` columns or unknown codes. */
  field?: FieldDef;
}

function text(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') return String(value);
  try {
    return JSON.stringify(value) ?? '';
  } catch {
    return '';
  }
}

function optionLabel(field: FieldDef, value: unknown): string {
  const raw = text(value);
  return field.options?.find((o) => o.value === raw)?.label ?? raw;
}

function formatDate(value: unknown, withTime: boolean): string {
  const raw = text(value);
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(raw);
  // A yyyy-MM-dd value is a calendar date: build it in local time so the day never shifts.
  const d = dateOnly ? new Date(+dateOnly[1], +dateOnly[2] - 1, +dateOnly[3]) : new Date(raw);
  if (Number.isNaN(d.getTime())) return raw;
  return withTime ? d.toLocaleString() : d.toLocaleDateString();
}

/** Formats a cell by field type. Total: never throws, empty values give an empty string. */
export function formatCell(field: FieldDef | undefined, value: unknown): string {
  try {
    if (value === null || value === undefined || value === '') return '';
    switch (field?.type) {
      case 'SELECT':
        return optionLabel(field, value);
      case 'MULTISELECT':
        return Array.isArray(value) ? value.map((v) => optionLabel(field, v)).join(', ') : optionLabel(field, value);
      case 'BOOLEAN':
        return value === true || value === 'true' ? 'Yes' : 'No';
      case 'DATE':
        return formatDate(value, false);
      case 'DATETIME':
        return formatDate(value, true);
      default:
        return text(value);
    }
  } catch {
    return '';
  }
}

/** Columns of a view: `view.columns`, or `[titleField?, '$reference']` when none are declared. */
export function resolveColumns(view: ViewDef, entity: EntityDef): ColumnSpec[] {
  const keys =
    view.columns && view.columns.length > 0
      ? view.columns
      : [...(entity.titleField ? [entity.titleField] : []), '$reference'];
  return keys.map((key) => {
    if (key.startsWith('$')) return { key, label: SYSTEM_COLUMN_LABELS[key] ?? key };
    const field = entity.fields.find((f) => f.code === key);
    return { key, label: field?.label ?? key, field };
  });
}

export interface ListState {
  view: string;
  page: number;
  size: number;
  /** The user's chosen sort. Absent until the user sorts. */
  sort?: { field: string; direction: 'ASC' | 'DESC' };
  defaultSort?: { field: string; direction?: 'ASC' | 'DESC' };
  filters: Record<string, string | null | undefined>;
}

function sortParam(s: { field: string; direction?: 'ASC' | 'DESC' }): string {
  return `${s.field},${s.direction === 'ASC' ? 'asc' : 'desc'}`;
}

/** Builds the list request: only non-empty filters, `defaultSort` only when the user has not sorted. */
export function buildListQuery(state: ListState): ListQuery {
  const filters: Record<string, string> = {};
  for (const [k, v] of Object.entries(state.filters)) {
    if (typeof v === 'string' && v.trim() !== '') filters[k] = v;
  }
  const chosen = state.sort ?? state.defaultSort;
  const query: ListQuery = { page: state.page, size: state.size, view: state.view, filters };
  if (chosen) query.sort = sortParam(chosen);
  return query;
}

/** New state after the user sorts: page resets to 1. */
export function withSort(state: ListState, sort: ListState['sort']): ListState {
  return { ...state, sort, page: 1 };
}

/** New state after a filter change: page resets to 1. */
export function withFilters(state: ListState, filters: ListState['filters']): ListState {
  return { ...state, filters, page: 1 };
}

export const OTHER_COLUMN = 'Other';

export interface KanbanColumn {
  label: string;
  status?: StatusDef;
  records: RecordSummary[];
}

/**
 * Places every record in exactly one column: the status whose label equals `record.status`, else "Other".
 * Columns follow declaration order; "Other" is last and only present when non-empty. Input order is kept.
 */
export function groupByStatus(records: readonly RecordSummary[], statuses: readonly StatusDef[]): KanbanColumn[] {
  const columns: KanbanColumn[] = statuses.map((status) => ({ label: status.label, status, records: [] }));
  const byLabel = new Map<string, KanbanColumn>();
  for (const c of columns) if (!byLabel.has(c.label)) byLabel.set(c.label, c);
  const other: KanbanColumn = { label: OTHER_COLUMN, records: [] };
  for (const r of records) {
    const col = r.status != null ? byLabel.get(r.status) : undefined;
    (col ?? other).records.push(r);
  }
  return other.records.length > 0 ? [...columns, other] : columns;
}

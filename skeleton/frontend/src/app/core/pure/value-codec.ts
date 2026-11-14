import type { FieldType } from '../api/api.types';

function pad(n: number, width = 2): string {
  return String(Math.abs(Math.trunc(n))).padStart(width, '0');
}

function toDate(ui: unknown): Date | null {
  if (ui instanceof Date) {
    return Number.isNaN(ui.getTime()) ? null : ui;
  }
  if (typeof ui === 'string' || typeof ui === 'number') {
    const d = new Date(ui);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  return null;
}

function toNumber(ui: unknown): number | null {
  if (typeof ui === 'number') {
    return Number.isFinite(ui) ? ui : null;
  }
  if (typeof ui === 'string' && ui.trim() !== '') {
    const n = Number(ui);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function isBlank(v: unknown): boolean {
  return v === undefined || v === null || v === '';
}

/** `yyyy-MM-dd` from the local calendar date. */
function formatDate(d: Date): string {
  return `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** ISO 8601 local date-time with the local UTC offset (`Z` when the offset is zero). */
function formatDateTime(d: Date): string {
  const base =
    `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` +
    (d.getMilliseconds() !== 0 ? `.${pad(d.getMilliseconds(), 3)}` : '');
  const offsetMin = -Math.round(d.getTimezoneOffset());
  if (offsetMin === 0) {
    return `${base}Z`;
  }
  const sign = offsetMin > 0 ? '+' : '-';
  const abs = Math.abs(offsetMin);
  return `${base}${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/** Converts a UI control value to the JSON type the backend expects. Blank values become `null`. */
export function toJsonValue(type: FieldType, ui: unknown): unknown {
  if (isBlank(ui)) {
    return null;
  }
  switch (type) {
    case 'NUMBER':
    case 'DECIMAL':
      return toNumber(ui);
    case 'DATE': {
      if (typeof ui === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(ui)) {
        return ui;
      }
      const d = toDate(ui);
      return d ? formatDate(d) : null;
    }
    case 'DATETIME': {
      const d = toDate(ui);
      return d ? formatDateTime(d) : null;
    }
    case 'BOOLEAN':
      return ui === true || ui === 'true';
    case 'MULTISELECT':
      return Array.isArray(ui) ? ui.map((x) => String(x)) : [String(ui)];
    case 'LOCATION': {
      const loc = ui as { lat?: unknown; lng?: unknown };
      return { lat: toNumber(loc.lat), lng: toNumber(loc.lng) };
    }
    default:
      return ui;
  }
}

/** Inverse of `toJsonValue`: converts a JSON value to what the UI controls bind to. */
export function fromJsonValue(type: FieldType, json: unknown): unknown {
  if (json === undefined || json === null) {
    return null;
  }
  switch (type) {
    case 'NUMBER':
    case 'DECIMAL':
      return toNumber(json);
    case 'DATE': {
      if (typeof json === 'string') {
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(json);
        if (m) {
          return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
        }
      }
      return toDate(json);
    }
    case 'DATETIME':
      return toDate(json);
    case 'BOOLEAN':
      return json === true || json === 'true';
    case 'MULTISELECT':
      return Array.isArray(json) ? json.map((x) => String(x)) : [String(json)];
    case 'LOCATION': {
      const loc = json as { lat?: unknown; lng?: unknown };
      return { lat: toNumber(loc.lat), lng: toNumber(loc.lng) };
    }
    default:
      return json;
  }
}

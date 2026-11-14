import type { FieldDef, FieldRule, FieldType, SectionDef } from '../../core/api/api.types';
import { effectiveRule } from '../../core/pure/field-rules';
import { fromJsonValue } from '../../core/pure/value-codec';

export type { FieldDef, FieldRule, FieldType, SectionDef };

export type ValidatorKind = 'required' | 'maxLength' | 'min' | 'max' | 'pattern' | 'email';

export interface ControlPlan {
  code: string;
  field: FieldDef;
  rule: FieldRule;
  /** rule is not `hidden`. */
  rendered: boolean;
  /** rule is `readonly`, or the type is FILE. */
  disabled: boolean;
  validators: ValidatorKind[];
  /** rule is optional or required, and the type is not FILE. */
  submitted: boolean;
}

export interface LayoutSection {
  title?: string;
  columns: 1 | 2 | 3;
  fields: string[];
}

/** One plan per field, in field order. `accept` and `maxFiles` are ignored. */
export function planControls(fields: FieldDef[], rules?: Record<string, FieldRule>): ControlPlan[] {
  return fields.map((field) => {
    const rule = effectiveRule(field, rules);
    const isFile = field.type === 'FILE';
    const validators: ValidatorKind[] = [];
    if (rule === 'required' && !isFile) {
      validators.push('required');
    }
    if (field.maxLength !== undefined && field.maxLength !== null) {
      validators.push('maxLength');
    }
    if (field.min !== undefined && field.min !== null) {
      validators.push('min');
    }
    if (field.max !== undefined && field.max !== null) {
      validators.push('max');
    }
    if (typeof field.pattern === 'string' && field.pattern !== '') {
      validators.push('pattern');
    }
    if (field.type === 'EMAIL') {
      validators.push('email');
    }
    return {
      code: field.code,
      field,
      rule,
      rendered: rule !== 'hidden',
      disabled: rule === 'readonly' || isFile,
      validators,
      submitted: (rule === 'optional' || rule === 'required') && !isFile,
    };
  });
}

/** True for undefined, null, '', [] and a location whose lat and lng are both unset. */
export function isEmptyValue(v: unknown): boolean {
  if (v === undefined || v === null || v === '') {
    return true;
  }
  if (Array.isArray(v)) {
    return v.length === 0;
  }
  if (typeof v === 'object' && !(v instanceof Date)) {
    const o = v as Record<string, unknown>;
    const keys = Object.keys(o);
    if (keys.length === 2 && 'lat' in o && 'lng' in o) {
      return (o['lat'] === null || o['lat'] === undefined) && (o['lng'] === null || o['lng'] === undefined);
    }
  }
  return false;
}

/** Keeps a key only for submitted controls whose value is not empty. Values are passed through unchanged. */
export function collectValues(plan: ControlPlan[], raw: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of plan) {
    if (!p.submitted || !Object.prototype.hasOwnProperty.call(raw, p.code)) {
      continue;
    }
    const v = raw[p.code];
    if (!isEmptyValue(v)) {
      out[p.code] = v;
    }
  }
  return out;
}

/**
 * Initial UI values for the rendered controls. A JSON value in `initial` wins, otherwise the field
 * `default` is used. Fields with neither get no key.
 */
export function initialValues(plan: ControlPlan[], initial?: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const p of plan) {
    if (!p.rendered) {
      continue;
    }
    let json: unknown;
    if (initial && Object.prototype.hasOwnProperty.call(initial, p.code) && initial[p.code] !== undefined) {
      json = initial[p.code];
    } else {
      json = p.field.default;
    }
    if (json !== undefined && json !== null) {
      out[p.code] = fromJsonValue(p.field.type, json);
    }
  }
  return out;
}

/**
 * Sections to render. Without sections: one untitled single-column section with every rendered field
 * in field order. With sections: each section keeps its title and columns (default 1) and lists its
 * rendered fields in the section's order; sections left with no rendered field are dropped.
 */
export function layoutSections(plan: ControlPlan[], sections?: SectionDef[]): LayoutSection[] {
  const rendered = new Set(plan.filter((p) => p.rendered).map((p) => p.code));
  if (!sections || sections.length === 0) {
    return [{ columns: 1, fields: plan.filter((p) => p.rendered).map((p) => p.code) }];
  }
  const out: LayoutSection[] = [];
  for (const s of sections) {
    const fields = s.fields.filter((c) => rendered.has(c));
    if (fields.length === 0) {
      continue;
    }
    const sec: LayoutSection = { columns: s.columns ?? 1, fields };
    if (s.title !== undefined) {
      sec.title = s.title;
    }
    out.push(sec);
  }
  return out;
}

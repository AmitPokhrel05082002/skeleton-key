import type { FieldDef, FieldType, SectionDef } from '../../core/api/api.types';

/**
 * Pure model of the form builder (Requirement 15). It reads and writes one app definition
 * document, touching only the fields of the selected entity and the sections of the selected form.
 */

/** A whole app definition as parsed JSON. Only the parts the builder edits are looked at. */
export type AppDefinitionJson = Record<string, unknown>;

export interface BuilderModel {
  entityCode: string;
  /** null means a new form: only the entity fields are written. */
  formCode: string | null;
  /** Entity fields, order preserved. */
  fields: FieldDef[];
  /** Layout of the selected form. */
  sections: SectionDef[];
}

export const FIELD_CODE_PATTERN = /^[a-z][a-zA-Z0-9_]{0,63}$/;

export const FIELD_TYPES: readonly FieldType[] = [
  'TEXT', 'TEXTAREA', 'NUMBER', 'DECIMAL', 'DATE', 'DATETIME', 'BOOLEAN',
  'SELECT', 'MULTISELECT', 'FILE', 'USER', 'REFERENCE', 'EMAIL', 'PHONE', 'LOCATION',
];

/** Properties of `$defs.field` in schema/app-definition.schema.json. */
const FIELD_PROPS = [
  'code', 'label', 'type', 'required', 'help', 'default', 'options', 'ref', 'min', 'max',
  'maxLength', 'pattern', 'accept', 'maxFiles', 'widget',
] as const;

/** Properties of the form `sections` items in the schema. */
const SECTION_PROPS = ['title', 'columns', 'fields'] as const;

/** Properties of a field option in the schema. */
const OPTION_PROPS = ['value', 'label'] as const;

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Deep copy of JSON-like data. `undefined` values are dropped by the callers that need it. */
function copy<T>(v: T): T {
  return structuredClone(v);
}

/** Keeps only the listed properties, drops `undefined` values and deep-copies the rest. */
function pick(src: unknown, props: readonly string[]): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (!isRecord(src)) return out;
  for (const p of props) {
    const v = src[p];
    if (v !== undefined) out[p] = copy(v);
  }
  return out;
}

function cleanField(f: FieldDef): FieldDef {
  const out = pick(f, FIELD_PROPS);
  if (Array.isArray(out['options'])) {
    out['options'] = (out['options'] as unknown[]).map((o) => pick(o, OPTION_PROPS));
  }
  return out as unknown as FieldDef;
}

function cleanSection(s: SectionDef): SectionDef {
  return pick(s, SECTION_PROPS) as unknown as SectionDef;
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

/** Builds the model for one entity and one form (null selects "new form"). Throws if the entity is unknown. */
export function extractModel(
  def: AppDefinitionJson,
  entityCode: string,
  formCode: string | null,
): BuilderModel {
  const entity = asArray(def['entities']).find((e) => isRecord(e) && e['code'] === entityCode);
  if (!isRecord(entity)) throw new Error(`Unknown entity ${entityCode}`);
  const fields = asArray(entity['fields']).map((f) => cleanField(f as FieldDef));
  let sections: SectionDef[] = [];
  if (formCode !== null) {
    const form = asArray(def['forms']).find((f) => isRecord(f) && f['code'] === formCode);
    if (isRecord(form)) sections = asArray(form['sections']).map((s) => cleanSection(s as SectionDef));
  }
  return { entityCode, formCode, fields, sections };
}

/**
 * Returns a new definition with the model written into it. Only the selected entity's `fields` and
 * the selected form's `sections` change; everything else is deep-equal to the input. The input is
 * not mutated. A model whose entity or form does not exist leaves the definition unchanged.
 */
export function applyModel(def: AppDefinitionJson, model: BuilderModel): AppDefinitionJson {
  const out = copy(def);
  const fields = model.fields.map(cleanField);
  const sections = model.sections.map(cleanSection);

  const entity = asArray(out['entities']).find((e) => isRecord(e) && e['code'] === model.entityCode);
  if (isRecord(entity)) entity['fields'] = fields;

  if (model.formCode !== null) {
    const form = asArray(out['forms']).find((f) => isRecord(f) && f['code'] === model.formCode);
    if (isRecord(form)) form['sections'] = sections;
  }
  return out;
}

/**
 * Returns a field code that matches the schema pattern and is not in `existing`.
 * With a `base` the (sanitised) base is tried first; otherwise `field1`, `field2`, ...
 */
export function generateFieldCode(existing: string[], base?: string): string {
  const taken = new Set(existing);
  let stem = (base ?? 'field').replace(/[^a-zA-Z0-9_]/g, '');
  stem = stem.replace(/^[^a-zA-Z]+/, '');
  if (stem === '') stem = 'field';
  stem = stem.charAt(0).toLowerCase() + stem.slice(1);
  stem = stem.slice(0, 56);
  if (base !== undefined && FIELD_CODE_PATTERN.test(stem) && !taken.has(stem)) return stem;
  for (let n = 1; ; n++) {
    const candidate = `${stem}${n}`;
    if (!taken.has(candidate)) return candidate;
  }
}

/** Error text for a field code, or null when it is valid and unique. `selfCode` is the code being edited. */
export function validateFieldCode(code: string, existing: string[], selfCode?: string): string | null {
  if (!FIELD_CODE_PATTERN.test(code)) {
    return 'Code must start with a lowercase letter and use only letters, digits and underscores (max 64).';
  }
  if (code !== selfCode && existing.includes(code)) return 'Code is already used by another field.';
  return null;
}

/** Error texts for one field (empty when valid). `entityCodes` are the entities a REFERENCE may target. */
export function validateField(f: FieldDef, entityCodes: string[]): string[] {
  const errors: string[] = [];
  if (!FIELD_TYPES.includes(f.type)) errors.push('Type is not a schema field type.');
  if (typeof f.label !== 'string' || f.label.length < 1 || f.label.length > 120) {
    errors.push('Label must be 1 to 120 characters.');
  }
  if (f.type === 'SELECT' || f.type === 'MULTISELECT') {
    const options = f.options ?? [];
    if (options.length === 0) {
      errors.push('At least one option is required.');
    } else if (options.some((o) => !o.value || !o.label)) {
      errors.push('Every option needs a value and a label.');
    }
  }
  if (f.type === 'REFERENCE') {
    if (!f.ref) errors.push('A target entity is required.');
    else if (!entityCodes.includes(f.ref)) errors.push('Target entity does not exist.');
  }
  if (f.min !== undefined && f.max !== undefined && f.min > f.max) {
    errors.push('Min must not be greater than max.');
  }
  if (f.maxLength !== undefined && !(Number.isInteger(f.maxLength) && f.maxLength >= 1)) {
    errors.push('Max length must be a whole number of at least 1.');
  }
  if (f.maxFiles !== undefined && !(Number.isInteger(f.maxFiles) && f.maxFiles >= 1)) {
    errors.push('Max files must be a whole number of at least 1.');
  }
  if (f.pattern !== undefined) {
    try {
      new RegExp(f.pattern);
    } catch {
      errors.push('Pattern is not a valid regular expression.');
    }
  }
  return errors;
}

/** Returns a new section with the field at `from` moved to index `to`. Out-of-range indices change nothing. */
export function moveField(section: SectionDef, from: number, to: number): SectionDef {
  const fields = [...section.fields];
  const inRange = (i: number) => Number.isInteger(i) && i >= 0 && i < fields.length;
  if (inRange(from) && inRange(to) && from !== to) {
    const [item] = fields.splice(from, 1);
    fields.splice(to, 0, item);
  }
  return { ...section, fields };
}

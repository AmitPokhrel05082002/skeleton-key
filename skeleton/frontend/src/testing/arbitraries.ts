import * as fc from 'fast-check';
import type {
  ApiError,
  EntityDef,
  FieldDef,
  FieldRule,
  FieldType,
  FormDef,
  RestrictedDefinition,
  Violation,
} from '../app/core/api/api.types';

/** Shared fast-check generators. Feature: skeleton-frontend, see design "Testing strategy". */

export const FIELD_TYPES: readonly FieldType[] = [
  'TEXT',
  'TEXTAREA',
  'NUMBER',
  'DECIMAL',
  'DATE',
  'DATETIME',
  'BOOLEAN',
  'SELECT',
  'MULTISELECT',
  'FILE',
  'USER',
  'REFERENCE',
  'EMAIL',
  'PHONE',
  'LOCATION',
];

export const FIELD_RULES: readonly FieldRule[] = ['hidden', 'readonly', 'optional', 'required'];

const arbFieldCode = fc.stringMatching(/^[a-z][a-zA-Z0-9_]{0,15}$/);
const arbCode = fc.stringMatching(/^[A-Z][A-Z0-9_]{0,15}$/);
const arbLabel = fc.stringMatching(/^[A-Za-z0-9][A-Za-z0-9 ]{0,29}$/);
const arbText = fc.string({ minLength: 1, maxLength: 40 });

const arbOption = fc.record({ value: fc.stringMatching(/^[a-z0-9_]{1,10}$/), label: arbLabel });
const arbOptions = fc.uniqueArray(arbOption, {
  minLength: 1,
  maxLength: 5,
  selector: (o) => o.value,
});

/** A field definition of any of the 15 types, with optional constraints. */
export const arbFieldDef: fc.Arbitrary<FieldDef> = fc
  .record({
    code: arbFieldCode,
    label: arbLabel,
    type: fc.constantFrom(...FIELD_TYPES),
    required: fc.option(fc.boolean(), { nil: undefined }),
    help: fc.option(arbText, { nil: undefined }),
    options: arbOptions,
    ref: arbCode,
    min: fc.option(fc.integer({ min: -1000, max: 1000 }), { nil: undefined }),
    max: fc.option(fc.integer({ min: -1000, max: 1000 }), { nil: undefined }),
    maxLength: fc.option(fc.integer({ min: 1, max: 500 }), { nil: undefined }),
    pattern: fc.option(fc.constantFrom('^[a-z]+$', '^\\d{3}$', '^[A-Z]{2}-\\d+$'), {
      nil: undefined,
    }),
    accept: fc.option(fc.constantFrom('image/*', 'application/pdf', 'image/*,application/pdf'), {
      nil: undefined,
    }),
    maxFiles: fc.option(fc.integer({ min: 1, max: 10 }), { nil: undefined }),
    widget: fc.option(fc.stringMatching(/^[a-zA-Z][a-zA-Z0-9_-]{0,15}$/), { nil: undefined }),
  })
  .map((r) => {
    const def: FieldDef = { code: r.code, label: r.label, type: r.type };
    if (r.required !== undefined) def.required = r.required;
    if (r.help !== undefined) def.help = r.help;
    if (r.type === 'SELECT' || r.type === 'MULTISELECT') def.options = r.options;
    if (r.type === 'REFERENCE') def.ref = r.ref;
    if (r.min !== undefined) def.min = r.min;
    if (r.max !== undefined) def.max = r.max;
    if (r.maxLength !== undefined) def.maxLength = r.maxLength;
    if (r.pattern !== undefined) def.pattern = r.pattern;
    if (r.accept !== undefined) def.accept = r.accept;
    if (r.maxFiles !== undefined) def.maxFiles = r.maxFiles;
    if (r.widget !== undefined) def.widget = r.widget;
    return def;
  });

/** A list of field definitions with unique codes. */
export const arbFieldDefs: fc.Arbitrary<FieldDef[]> = fc.uniqueArray(arbFieldDef, {
  minLength: 1,
  maxLength: 8,
  selector: (f) => f.code,
});

/** A map of field code to Field_Rule. Keys come from the same code space as arbFieldDef. */
export const arbRuleMap: fc.Arbitrary<Record<string, FieldRule>> = fc
  .uniqueArray(fc.tuple(arbFieldCode, fc.constantFrom(...FIELD_RULES)), {
    maxLength: 8,
    selector: ([code]) => code,
  })
  .map((pairs) => Object.fromEntries(pairs) as Record<string, FieldRule>);

export const arbViolation: fc.Arbitrary<Violation> = fc
  .record({
    field: fc.oneof(arbFieldCode, fc.constant('')),
    rule: fc.constantFrom('required', 'maxLength', 'min', 'max', 'pattern', 'readonly', 'unknown'),
    message: fc.option(arbText, { nil: undefined }),
  })
  .map((v) => (v.message === undefined ? { field: v.field, rule: v.rule } : v));

export const arbApiError: fc.Arbitrary<ApiError> = fc
  .record({
    status: fc.constantFrom(0, 400, 401, 403, 404, 409, 422, 500, 502, 503),
    code: arbCode,
    message: arbText,
    requestId: fc.option(fc.uuid(), { nil: undefined }),
    violations: fc.array(arbViolation, { maxLength: 5 }),
    line: fc.option(fc.integer({ min: 1, max: 200 }), { nil: undefined }),
    column: fc.option(fc.integer({ min: 1, max: 200 }), { nil: undefined }),
  })
  .map((r) => {
    const e: ApiError = {
      status: r.status,
      code: r.code,
      message: r.message,
      violations: r.violations,
    };
    if (r.requestId !== undefined) e.requestId = r.requestId;
    if (r.line !== undefined) e.line = r.line;
    if (r.column !== undefined) e.column = r.column;
    return e;
  });

/**
 * A restricted definition with one app, one role, one entity, a form listing every entity field,
 * and one workflow. Cross references are consistent.
 */
export const arbDefinition: fc.Arbitrary<RestrictedDefinition> = fc
  .record({
    appCode: arbCode,
    appName: arbLabel,
    roleCode: arbCode,
    entityCode: arbCode,
    entityLabel: arbLabel,
    fields: arbFieldDefs,
    formCode: arbCode,
    isPublic: fc.boolean(),
    workflowCode: arbCode,
    theme: fc.option(fc.constantFrom('light' as const, 'dark' as const, 'auto' as const), {
      nil: undefined,
    }),
    primaryColor: fc.option(fc.stringMatching(/^#[0-9a-f]{6}$/), { nil: undefined }),
  })
  .map((r): RestrictedDefinition => {
    const entity: EntityDef = {
      code: r.entityCode,
      label: r.entityLabel,
      titleField: r.fields[0].code,
      fields: r.fields,
    };
    const form: FormDef = {
      code: r.formCode,
      label: r.entityLabel,
      entity: r.entityCode,
      public: r.isPublic,
      startsWorkflow: r.workflowCode,
      sections: [{ columns: 1, fields: r.fields.map((f) => f.code) }],
    };
    if (!r.isPublic) form.roles = [r.roleCode];
    const branding: { primaryColor?: string; theme?: 'light' | 'dark' | 'auto' } = {};
    if (r.primaryColor !== undefined) branding.primaryColor = r.primaryColor;
    if (r.theme !== undefined) branding.theme = r.theme;
    return {
      app: { code: r.appCode, name: r.appName, branding },
      roles: [{ code: r.roleCode, label: r.roleCode }],
      entities: [entity],
      forms: [form],
      views: [{ code: 'ALL', type: 'TABLE', entity: r.entityCode, scope: 'ALL' }],
      workflows: [
        {
          code: r.workflowCode,
          entity: r.entityCode,
          statuses: [{ label: 'Open', category: 'OPEN' }],
          steps: [
            { code: 'START', type: 'TASK' },
            { code: 'DONE', type: 'END' },
          ],
        },
      ],
    };
  });

const arbHostLabel = fc.stringMatching(/^[a-z][a-z0-9]{0,9}$/);

/** A host name as `window.location.host` reports it: lower-case labels, optional `:port`. */
export const arbHost: fc.Arbitrary<string> = fc
  .tuple(
    fc.array(arbHostLabel, { minLength: 1, maxLength: 3 }),
    fc.option(fc.integer({ min: 1, max: 65535 }), { nil: undefined }),
  )
  .map(([labels, port]) => labels.join('.') + (port === undefined ? '' : `:${port}`));

/** A RuntimeConfig `hosts` map (host name to app code) with prototype-safe keys. */
export const arbHostsMap: fc.Arbitrary<Record<string, string>> = fc
  .uniqueArray(fc.tuple(arbHost.map((h) => h.replace(/:\d*$/, '')), arbCode), {
    maxLength: 5,
    selector: ([h]) => h,
  })
  .map((pairs) => Object.fromEntries(pairs));

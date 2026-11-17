import { describe, expect, it } from 'vitest';
import type { FieldDef } from '../../core/api/api.types';
import {
  applyModel,
  extractModel,
  generateFieldCode,
  moveField,
  validateField,
  validateFieldCode,
  type AppDefinitionJson,
} from './builder-model';

const def = (): AppDefinitionJson => ({
  schemaVersion: 1,
  app: { code: 'A', name: 'A', extra: { keep: true } },
  roles: [{ code: 'R', label: 'R' }],
  entities: [
    { code: 'E1', label: 'E1', custom: 1, fields: [{ code: 'name', label: 'Name', type: 'TEXT', required: true }, { code: 'age', label: 'Age', type: 'NUMBER' }] },
    { code: 'E2', label: 'E2', fields: [{ code: 'x', label: 'X', type: 'TEXT' }] },
  ],
  forms: [
    { code: 'F1', entity: 'E1', note: 'n', sections: [{ title: 'S', columns: 2, fields: ['name', 'age'] }] },
    { code: 'F2', entity: 'E2', sections: [{ fields: ['x'] }] },
  ],
  workflows: [{ code: 'W' }],
});

describe('builder-model', () => {
  it('extracts fields and sections of the selected entity and form', () => {
    const m = extractModel(def(), 'E1', 'F1');
    expect(m.fields.map((f) => f.code)).toEqual(['name', 'age']);
    expect(m.sections).toEqual([{ title: 'S', columns: 2, fields: ['name', 'age'] }]);
    expect(extractModel(def(), 'E1', null).sections).toEqual([]);
    expect(() => extractModel(def(), 'NOPE', null)).toThrow();
  });

  it('applyModel of an unchanged model equals the definition', () => {
    const d = def();
    expect(applyModel(d, extractModel(d, 'E1', 'F1'))).toEqual(d);
  });

  it('applyModel changes only the selected fields and sections, without mutating the input', () => {
    const d = def();
    const snapshot = structuredClone(d);
    const m = extractModel(d, 'E1', 'F1');
    m.fields.push({ code: 'city', label: 'City', type: 'TEXT' });
    m.sections[0].fields.push('city');
    const out = applyModel(d, m);
    expect(d).toEqual(snapshot);
    const o = out as { entities: { fields: unknown[] }[]; forms: { sections: { fields: string[] }[] }[] };
    expect(o.entities[0].fields).toHaveLength(3);
    expect(o.forms[0].sections[0].fields).toEqual(['name', 'age', 'city']);
    const s = snapshot as unknown as typeof o;
    expect(o.entities[1]).toEqual(s.entities[1]);
    expect(o.forms[1]).toEqual(s.forms[1]);
    expect(out['app']).toEqual(snapshot['app']);
    expect(out['workflows']).toEqual(snapshot['workflows']);
    expect((out['entities'] as { custom?: number }[])[0].custom).toBe(1);
    expect((out['forms'] as { note?: string }[])[0].note).toBe('n');
  });

  it('writes only schema properties and drops undefined ones', () => {
    const m = extractModel(def(), 'E1', 'F1');
    m.fields[0] = { code: 'name', label: 'Name', type: 'TEXT', help: undefined, bogus: 1 } as unknown as FieldDef;
    m.fields[1] = { code: 'k', label: 'K', type: 'SELECT', options: [{ value: 'a', label: 'A', x: 1 }] } as unknown as FieldDef;
    const out = applyModel(def(), m) as { entities: { fields: Record<string, unknown>[] }[] };
    expect(Object.keys(out.entities[0].fields[0]).sort()).toEqual(['code', 'label', 'type']);
    expect(out.entities[0].fields[1]['options']).toEqual([{ value: 'a', label: 'A' }]);
  });

  it('new form writes only the entity fields', () => {
    const d = def();
    const m = extractModel(d, 'E1', null);
    m.fields.pop();
    const out = applyModel(d, m) as { entities: { fields: unknown[] }[]; forms: unknown[] };
    expect(out.entities[0].fields).toHaveLength(1);
    expect(out.forms).toEqual(d['forms']);
  });

  it('generateFieldCode returns a valid unused code', () => {
    expect(generateFieldCode([])).toBe('field1');
    expect(generateFieldCode(['field1', 'field2'])).toBe('field3');
    expect(generateFieldCode(['name'], 'name')).toBe('name1');
    expect(generateFieldCode([], 'Full Name')).toBe('fullName');
    expect(generateFieldCode([], '9 !')).toBe('field');
  });

  it('validateFieldCode checks pattern and uniqueness', () => {
    expect(validateFieldCode('abc', ['x'])).toBeNull();
    expect(validateFieldCode('Abc', [])).not.toBeNull();
    expect(validateFieldCode('a-b', [])).not.toBeNull();
    expect(validateFieldCode('a'.repeat(65), [])).not.toBeNull();
    expect(validateFieldCode('x', ['x', 'y'])).not.toBeNull();
    expect(validateFieldCode('x', ['x', 'y'], 'x')).toBeNull();
  });

  it('validateField enforces options, ref, min/max, maxLength and pattern', () => {
    const base: FieldDef = { code: 'a', label: 'A', type: 'TEXT' };
    expect(validateField(base, [])).toEqual([]);
    expect(validateField({ ...base, type: 'SELECT' }, [])).not.toEqual([]);
    expect(validateField({ ...base, type: 'MULTISELECT', options: [] }, [])).not.toEqual([]);
    expect(validateField({ ...base, type: 'SELECT', options: [{ value: 'v', label: 'L' }] }, [])).toEqual([]);
    expect(validateField({ ...base, type: 'REFERENCE' }, ['E1'])).not.toEqual([]);
    expect(validateField({ ...base, type: 'REFERENCE', ref: 'E9' }, ['E1'])).not.toEqual([]);
    expect(validateField({ ...base, type: 'REFERENCE', ref: 'E1' }, ['E1'])).toEqual([]);
    expect(validateField({ ...base, min: 5, max: 1 }, [])).not.toEqual([]);
    expect(validateField({ ...base, min: 1, max: 1 }, [])).toEqual([]);
    expect(validateField({ ...base, maxLength: 0 }, [])).not.toEqual([]);
    expect(validateField({ ...base, pattern: '(' }, [])).not.toEqual([]);
    expect(validateField({ ...base, pattern: '^a+$' }, [])).toEqual([]);
  });

  it('moveField returns a permutation without mutating', () => {
    const s = { title: 'T', fields: ['a', 'b', 'c'] };
    expect(moveField(s, 0, 2)).toEqual({ title: 'T', fields: ['b', 'c', 'a'] });
    expect(moveField(s, 2, 1).fields).toEqual(['a', 'c', 'b']);
    expect(moveField(s, 0, 5).fields).toEqual(['a', 'b', 'c']);
    expect(s.fields).toEqual(['a', 'b', 'c']);
  });
});

import { describe, expect, it } from 'vitest';
import { effectiveRule } from '../../core/pure/field-rules';
import { fromJsonValue, toJsonValue } from '../../core/pure/value-codec';
import {
  collectValues,
  initialValues,
  isEmptyValue,
  layoutSections,
  planControls,
  type FieldDef,
} from './form-model';

const f = (code: string, extra: Partial<FieldDef> = {}): FieldDef => ({
  code,
  label: code,
  type: 'TEXT',
  ...extra,
});

describe('effectiveRule', () => {
  it('uses the map when it holds the code, else required/optional', () => {
    expect(effectiveRule(f('a', { required: true }))).toBe('required');
    expect(effectiveRule(f('a'))).toBe('optional');
    expect(effectiveRule(f('a', { required: true }), { a: 'readonly' })).toBe('readonly');
    expect(effectiveRule(f('a', { required: true }), { b: 'hidden' })).toBe('required');
    expect(effectiveRule(f('constructor'), {})).toBe('optional');
  });
});

describe('planControls', () => {
  it('plans rendering, disabling, validators and submission per rule', () => {
    const plan = planControls(
      [
        f('h'),
        f('r'),
        f('o', { required: true, maxLength: 5, min: 0, max: 9, pattern: '^a' }),
        f('e', { type: 'EMAIL' }),
        f('file', { type: 'FILE', required: true, accept: 'image/*', maxFiles: 2 }),
      ],
      { h: 'hidden', r: 'readonly', o: 'required', file: 'required' },
    );
    const by = Object.fromEntries(plan.map((p) => [p.code, p]));
    expect(by['h']).toMatchObject({ rendered: false, submitted: false });
    expect(by['r']).toMatchObject({ rendered: true, disabled: true, submitted: false, validators: [] });
    expect(by['o'].validators).toEqual(['required', 'maxLength', 'min', 'max', 'pattern']);
    expect(by['o']).toMatchObject({ disabled: false, submitted: true });
    expect(by['e'].validators).toEqual(['email']);
    expect(by['file']).toMatchObject({ rendered: true, disabled: true, submitted: false, validators: [] });
  });
});

describe('collectValues / isEmptyValue', () => {
  it('drops hidden, readonly, file and empty values', () => {
    const plan = planControls([f('a'), f('b'), f('c'), f('d'), f('n')], { b: 'hidden', c: 'readonly' });
    const out = collectValues(plan, { a: 'x', b: 'y', c: 'z', d: '', n: 0 });
    expect(out).toEqual({ a: 'x', n: 0 });
  });

  it('recognises empty values', () => {
    for (const v of [undefined, null, '', [], { lat: null, lng: null }]) {
      expect(isEmptyValue(v)).toBe(true);
    }
    for (const v of [0, false, 'a', ['a'], { lat: 0, lng: null }]) {
      expect(isEmptyValue(v)).toBe(false);
    }
  });
});

describe('initialValues', () => {
  it('prefers initial over default and skips hidden fields', () => {
    const plan = planControls([f('a', { default: 'd' }), f('b', { default: 'd' }), f('c', { default: 'd' }), f('n')], {
      c: 'hidden',
    });
    expect(initialValues(plan, { a: 'init' })).toEqual({ a: 'init', b: 'd' });
  });
});

describe('layoutSections', () => {
  const plan = planControls([f('a'), f('b'), f('c')], { b: 'hidden' });
  it('defaults to one single-column section', () => {
    expect(layoutSections(plan)).toEqual([{ columns: 1, fields: ['a', 'c'] }]);
  });
  it('keeps titles and columns and drops hidden fields', () => {
    expect(layoutSections(plan, [{ title: 'T', columns: 2, fields: ['c', 'b'] }, { fields: ['b'] }])).toEqual([
      { title: 'T', columns: 2, fields: ['c'] },
    ]);
  });
});

describe('value codec', () => {
  it('converts UI values to JSON types', () => {
    expect(toJsonValue('NUMBER', '12')).toBe(12);
    expect(toJsonValue('DECIMAL', 1.5)).toBe(1.5);
    expect(toJsonValue('DATE', new Date(2026, 2, 5))).toBe('2026-03-05');
    expect(toJsonValue('BOOLEAN', true)).toBe(true);
    expect(toJsonValue('MULTISELECT', ['a', 'b'])).toEqual(['a', 'b']);
    expect(toJsonValue('LOCATION', { lat: 1, lng: 2 })).toEqual({ lat: 1, lng: 2 });
    expect(toJsonValue('TEXT', '')).toBeNull();
  });

  it('writes DATETIME with the local offset and preserves the instant', () => {
    const d = new Date(2026, 2, 5, 14, 30, 0);
    const s = toJsonValue('DATETIME', d) as string;
    expect(s).toMatch(/^2026-03-05T14:30:00(Z|[+-]\d{2}:\d{2})$/);
    expect(new Date(s).getTime()).toBe(d.getTime());
    expect(fromJsonValue('DATETIME', s)).toEqual(d);
    const offset = -d.getTimezoneOffset();
    expect(s.endsWith('Z')).toBe(offset === 0);
  });

  it('round trips DATE', () => {
    const d = fromJsonValue('DATE', '2026-03-05') as Date;
    expect(toJsonValue('DATE', d)).toBe('2026-03-05');
  });
});

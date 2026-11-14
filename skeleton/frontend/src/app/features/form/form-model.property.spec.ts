import * as fc from 'fast-check';
import { describe, it } from 'vitest';
import { arbFieldDefs, arbRuleMap, FIELD_RULES } from '../../../testing/arbitraries';
import type { FieldRule } from '../../core/api/api.types';
import { effectiveRule } from '../../core/pure/field-rules';
import { collectValues, planControls } from './form-model';

const EMPTY = Symbol('empty');

const arbEmpty = fc.constantFrom<unknown>(undefined, null, '', [], { lat: null, lng: null }, { lat: undefined, lng: undefined });
const arbNonEmpty = fc.oneof(
  fc.string({ minLength: 1 }),
  fc.integer(),
  fc.boolean(),
  fc.array(fc.string(), { minLength: 1, maxLength: 3 }),
  fc.record({ lat: fc.double({ noNaN: true }), lng: fc.double({ noNaN: true }) }),
);
const arbValue = fc.oneof(arbEmpty, arbNonEmpty);

function isEmptyOracle(v: unknown): boolean {
  if (v === undefined || v === null || v === '') return true;
  if (Array.isArray(v)) return v.length === 0;
  if (typeof v === 'object') {
    const o = v as { lat?: unknown; lng?: unknown };
    return (o.lat ?? EMPTY) === EMPTY && (o.lng ?? EMPTY) === EMPTY;
  }
  return false;
}

// Feature: skeleton-frontend, Property 3: Submitted values contain exactly the editable non-empty fields
// **Validates: Requirements 7.4, 7.8, 7.9, 18.6**
describe('collectValues (property)', () => {
  it('returns exactly the optional/required, non-FILE, non-empty fields', () => {
    const arbCase = arbFieldDefs.chain((fields) =>
      fc.record({
        fields: fc.constant(fields),
        // Rules for real field codes, merged with unrelated random keys.
        rules: fc
          .tuple(
            fc.array(fc.option(fc.constantFrom<FieldRule>(...FIELD_RULES), { nil: undefined }), {
              minLength: fields.length,
              maxLength: fields.length,
            }),
            arbRuleMap,
            fc.boolean(),
          )
          .map(([per, extra, useMap]) => {
            if (!useMap) return undefined;
            const m: Record<string, FieldRule> = { ...extra };
            fields.forEach((f, i) => {
              const r = per[i];
              if (r !== undefined) m[f.code] = r;
            });
            return m;
          }),
        raw: fc
          .tuple(
            fc.array(fc.option(arbValue, { nil: EMPTY as unknown }), {
              minLength: fields.length,
              maxLength: fields.length,
            }),
            fc.dictionary(fc.stringMatching(/^x[a-z]{0,6}$/), arbValue, { maxKeys: 3 }),
          )
          .map(([vals, extra]) => {
            const r: Record<string, unknown> = { ...extra };
            fields.forEach((f, i) => {
              if (vals[i] !== EMPTY) r[f.code] = vals[i];
            });
            return r;
          }),
      }),
    );

    fc.assert(
      fc.property(arbCase, ({ fields, rules, raw }) => {
        const out = collectValues(planControls(fields, rules), raw);
        const expected: Record<string, unknown> = {};
        for (const f of fields) {
          const rule = effectiveRule(f, rules);
          if ((rule === 'optional' || rule === 'required') && f.type !== 'FILE' && Object.prototype.hasOwnProperty.call(raw, f.code) && !isEmptyOracle(raw[f.code])) {
            expected[f.code] = raw[f.code];
          }
        }
        if (JSON.stringify(Object.keys(out).sort()) !== JSON.stringify(Object.keys(expected).sort())) return false;
        return Object.keys(expected).every((k) => out[k] === expected[k]);
      }),
      { numRuns: 200 },
    );
  });
});

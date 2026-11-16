import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { arbApiError } from '../../../testing/arbitraries';
import { mapErrors, violationMessage } from './error-mapper';
import { RULE_MESSAGES } from './rule-messages';

// Feature: skeleton-frontend, Property 6: Error mapping preserves order, yields non-empty messages and is deterministic
// Validates: Requirements 12.3, 12.4, 19.5, 19.6, 19.9

const blank = (s: string | undefined): boolean => s == null || s.trim() === '';

function expected(v: { rule: string; message?: string }): string {
  if (!blank(v.message)) return v.message as string;
  if (!blank(v.rule)) return Object.hasOwn(RULE_MESSAGES, v.rule) ? RULE_MESSAGES[v.rule] : v.rule;
  return 'Invalid value';
}

describe('error-mapper property 6', () => {
  it('preserves per-field order, yields non-empty expected messages and is deterministic', () => {
    fc.assert(
      fc.property(
        arbApiError,
        fc.array(fc.stringMatching(/^[a-z][a-zA-Z0-9_]{0,15}$/), { maxLength: 6 }),
        fc.boolean(),
        (err, extraNames, includeAllFields) => {
          const fields = err.violations.map((v) => v.field).filter((f) => f !== '');
          const names = [...new Set([...extraNames, ...(includeAllFields ? fields : fields.slice(0, 1))])];

          const first = mapErrors(err, names);
          const second = mapErrors(structuredClone(err), [...names]);
          expect(second).toEqual(first);

          for (const v of err.violations) {
            const m = violationMessage(v);
            expect(m.length).toBeGreaterThan(0);
            expect(m).toBe(expected(v));
          }

          // Per-field relative order, and unmatched order.
          for (const name of names) {
            const want = err.violations.filter((v) => v.field === name).map(violationMessage);
            expect(first.byControl[name]).toEqual(want);
          }
          const nameSet = new Set(names);
          const wantUnmatched = err.violations.filter((v) => !nameSet.has(v.field)).map(violationMessage);
          expect(first.unmatched).toEqual(wantUnmatched);

          const all = [...Object.values(first.byControl).flat(), ...first.unmatched];
          expect(all.length).toBe(err.violations.length);
          for (const m of all) expect(m.length).toBeGreaterThan(0);
        },
      ),
      { numRuns: 200 },
    );
  });
});

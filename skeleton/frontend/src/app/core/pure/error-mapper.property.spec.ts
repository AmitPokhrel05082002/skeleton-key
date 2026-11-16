import { describe, it, expect } from 'vitest';
import * as fc from 'fast-check';
import { arbApiError, arbViolation } from '../../../testing/arbitraries';
import { mapErrors, violationMessage } from './error-mapper';

const HOSTILE = ['constructor', '__proto__', 'toString', 'hasOwnProperty', 'valueOf', 'prototype'];

const arbName = fc.oneof(
  fc.constantFrom(...HOSTILE),
  fc.stringMatching(/^[a-z][a-zA-Z0-9_]{0,10}$/),
);

describe('error-mapper properties', () => {
  // Feature: skeleton-frontend, Property 5: Error mapping assigns every violation exactly once
  // **Validates: Requirements 12.1, 12.2, 12.5, 12.6, 19.2, 19.3, 19.4**
  it('assigns every violation exactly once', () => {
    fc.assert(
      fc.property(
        arbApiError,
        fc.uniqueArray(arbName, { maxLength: 8 }),
        fc.array(fc.tuple(arbViolation, arbName, fc.boolean()), { maxLength: 20 }),
        (base, names, items) => {
          // Mix matching fields (taken from names or hostile) with arbitrary ones.
          const violations = items.map(([v, n, useName]) => ({
            ...v,
            field: useName ? n : v.field,
          }));
          const err = { ...base, violations };
          const out = mapErrors(err, names);

          const nameSet = new Set(names);
          const expectedUnmatched: string[] = [];
          const expectedBy = new Map<string, string[]>(names.map((n) => [n, []]));
          for (const v of violations) {
            const msg = violationMessage(v);
            expect(msg.trim()).not.toBe('');
            if (nameSet.has(v.field)) expectedBy.get(v.field)!.push(msg);
            else expectedUnmatched.push(msg);
          }

          expect(Object.keys(out.byControl).sort()).toEqual([...names].sort());
          for (const n of names) {
            expect(out.byControl[n]).toEqual(expectedBy.get(n));
          }
          expect(out.unmatched).toEqual(expectedUnmatched);

          const assigned = names.reduce((s, n) => s + out.byControl[n].length, 0);
          expect(assigned + out.unmatched.length).toBe(violations.length);
        },
      ),
      { numRuns: 200 },
    );
  });

  it('puts hostile field names in unmatched when no such control exists', () => {
    const out = mapErrors(
      {
        status: 422,
        code: 'VALIDATION',
        message: 'x',
        violations: HOSTILE.map((field) => ({ field, rule: 'required', message: field })),
      } as never,
      ['name'],
    );
    expect(out.byControl['name']).toEqual([]);
    expect(out.unmatched).toEqual(HOSTILE);
  });
});

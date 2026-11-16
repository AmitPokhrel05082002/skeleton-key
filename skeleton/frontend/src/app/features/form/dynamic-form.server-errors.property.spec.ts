import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import type { ApiError, Violation } from '../../core/api/api.types';
import { violationMessage } from '../../core/pure/error-mapper';
import { arbApiError, arbViolation } from '../../../testing/arbitraries';
import { DynamicFormComponent } from './dynamic-form.component';
import type { FieldDef } from './form-model';

// Feature: skeleton-frontend, Property 7: Applied server errors reach the right controls
// **Validates: Requirements 12.7, 12.8, 19.7, 19.8**

// Rendered control codes must match the schema's fieldCode pattern (`__proto__` cannot), but
// violation fields from the server may be anything, including `__proto__`.
const HOSTILE = ['constructor', 'toString', 'hasOwnProperty', 'valueOf'];
const HOSTILE_VIOLATION_FIELDS = ['__proto__', 'constructor', 'toString'];
const arbCode = fc.oneof(
  fc.constantFrom(...HOSTILE),
  fc.stringMatching(/^[a-z][a-zA-Z0-9_]{0,7}$/),
);

const arbCase = fc
  .record({
    codes: fc.uniqueArray(arbCode, { minLength: 1, maxLength: 4 }),
    error: arbApiError,
    extra: fc.array(
      fc.oneof(
        arbViolation,
        fc.record({ field: fc.constantFrom(...HOSTILE_VIOLATION_FIELDS), rule: fc.constant('x') }),
      ),
      { maxLength: 4 },
    ),
    pickers: fc.array(fc.nat(), { maxLength: 6 }),
    messages: fc.array(fc.string({ minLength: 1, maxLength: 12 }), { maxLength: 6 }),
  })
  .map(({ codes, error, extra, pickers, messages }) => {
    // Aim some violations at the rendered codes (including hostile ones), keep the rest as generated.
    const aimed: Violation[] = pickers.map((n, i) => ({
      field: codes[n % codes.length],
      rule: 'x',
      message: messages[i % Math.max(messages.length, 1)],
    }));
    const violations = [...error.violations, ...extra, ...aimed];
    return { codes, error: { ...error, violations } as ApiError };
  });

describe('Property 7: applied server errors reach the right controls', () => {
  it(
    'puts each matched violation on its control, the rest in unmatchedErrors, and editing clears only that control',
    { timeout: 120_000 },
    async () => {
      await fc.assert(
        fc.asyncProperty(arbCase, async ({ codes, error }) => {
          const fields: FieldDef[] = codes.map((code) => ({ code, label: 'L' + code.length, type: 'TEXT' }));
          const fixture = TestBed.createComponent(DynamicFormComponent);
          try {
            fixture.componentRef.setInput('fields', fields);
            fixture.detectChanges();
            fixture.componentRef.setInput('serverError', error);
            fixture.detectChanges();
            const cmp = fixture.componentInstance;
            const controls = cmp.form().controls;

            const expectedBy = new Map<string, string[]>(codes.map((c) => [c, []]));
            const expectedUnmatched: string[] = [];
            for (const v of error.violations) {
              const list = expectedBy.get(v.field);
              if (list) list.push(violationMessage(v));
              else expectedUnmatched.push(violationMessage(v));
            }

            expect(cmp.unmatchedErrors()).toEqual(expectedUnmatched);
            let total = expectedUnmatched.length;
            for (const code of codes) {
              const control = Object.getOwnPropertyDescriptor(controls, code)?.value;
              expect(control).toBeDefined();
              const expected = expectedBy.get(code) as string[];
              total += expected.length;
              if (expected.length > 0) {
                expect(control.errors?.['server']).toEqual(expected);
                expect(control.invalid).toBe(true);
                expect(control.touched).toBe(true);
              } else {
                expect(control.errors?.['server']).toBeUndefined();
              }
            }
            expect(total).toBe(error.violations.length);

            // Editing one control clears only its server error.
            const withErrors = codes.filter((c) => (expectedBy.get(c) as string[]).length > 0);
            if (withErrors.length > 0) {
              const edited = withErrors[0];
              Object.getOwnPropertyDescriptor(controls, edited)?.value.setValue('edited');
              for (const code of codes) {
                const control = Object.getOwnPropertyDescriptor(controls, code)?.value;
                const expected = expectedBy.get(code) as string[];
                if (code === edited || expected.length === 0) {
                  expect(control.errors?.['server']).toBeUndefined();
                } else {
                  expect(control.errors?.['server']).toEqual(expected);
                }
              }
            }
          } finally {
            fixture.destroy();
          }
        }),
        { numRuns: 40 },
      );
    },
  );
});

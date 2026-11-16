import * as fc from 'fast-check';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { DynamicFormComponent } from './dynamic-form.component';
import { planControls, type FieldDef } from './form-model';

const f = (code: string, extra: Partial<FieldDef> = {}): FieldDef => ({ code, label: code, type: 'TEXT', ...extra });

function build(fields: FieldDef[]): DynamicFormComponent {
  const fixture = TestBed.createComponent(DynamicFormComponent);
  fixture.componentRef.setInput('fields', fields);
  fixture.detectChanges();
  return fixture.componentInstance;
}

const arbBounds = fc
  .tuple(fc.integer({ min: -1000, max: 1000 }), fc.integer({ min: 0, max: 1000 }))
  .map(([lo, span]) => ({ lo, hi: lo + span }));
const arbAccept = fc.option(fc.constantFrom('image/*', 'application/pdf', 'image/*,application/pdf'), { nil: undefined });
const arbMaxFiles = fc.option(fc.integer({ min: 1, max: 10 }), { nil: undefined });
const arbLocal = fc.stringMatching(/^[a-z0-9]{1,10}$/);
const arbWord = fc.stringMatching(/^[a-z]{1,8}$/);

// Feature: skeleton-frontend, Property 2: Limit validators match declared constraints
// **Validates: Requirements 7.13, 7.14, 18.5**
describe('DynamicForm limit validators (Property 2)', () => {
  it('controls reject values violating limits and accept values within them', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: 20 }),
        arbBounds,
        arbWord,
        arbLocal,
        fc.string({ minLength: 21, maxLength: 30 }),
        fc.nat({ max: 20 }),
        (maxLength, { lo, hi }, word, local, longText, shortLen) => {
          const cmp = build([
            f('text', { maxLength }),
            f('num', { type: 'NUMBER', min: lo, max: hi }),
            f('minOnly', { type: 'NUMBER', min: lo }),
            f('maxOnly', { type: 'NUMBER', max: hi }),
            f('code', { pattern: '[a-z]+' }),
            f('mail', { type: 'EMAIL' }),
          ]);
          const c = cmp.form().controls;
          const check = (code: string, value: unknown, valid: boolean) => {
            c[code].setValue(value);
            expect(c[code].valid, `${code}=${String(value)}`).toBe(valid);
          };

          check('text', 'a'.repeat(Math.min(shortLen, maxLength)), true);
          check('text', longText.slice(0, maxLength), true);
          check('text', 'a'.repeat(maxLength + 1), false);

          check('num', lo, true);
          check('num', hi, true);
          check('num', lo - 1, false);
          check('num', hi + 1, false);
          check('minOnly', lo, true);
          check('minOnly', lo - 1, false);
          check('maxOnly', hi, true);
          check('maxOnly', hi + 1, false);

          check('code', word, true);
          check('code', word + '1', false);
          check('code', word.toUpperCase() + '!', false);

          check('mail', `${local}@example.com`, true);
          check('mail', local, false);
        },
      ),
      { numRuns: 30 },
    );
  }, 30000);

  it('accept and maxFiles add no validators and never invalidate a control', () => {
    fc.assert(
      fc.property(arbAccept, arbMaxFiles, fc.constantFrom('FILE', 'TEXT'), (accept, maxFiles, type) => {
        const extra = { ...(accept !== undefined ? { accept } : {}), ...(maxFiles !== undefined ? { maxFiles } : {}) };
        const withExtra = f('x', { type: type as FieldDef['type'], ...extra });
        const plain = f('x', { type: type as FieldDef['type'] });
        expect(planControls([withExtra])[0].validators).toEqual(planControls([plain])[0].validators);
        expect(planControls([withExtra])[0].validators).toEqual([]);
      }),
      { numRuns: 100 },
    );
    const cmp = build([f('file', { type: 'FILE', accept: 'image/*', maxFiles: 1 }), f('t', { accept: 'image/*', maxFiles: 1 })]);
    cmp.form().controls['t'].setValue('anything at all');
    expect(cmp.form().controls['file'].errors).toBeNull();
    expect(cmp.form().controls['t'].errors).toBeNull();
    expect(cmp.valid()).toBe(true);
  });
});

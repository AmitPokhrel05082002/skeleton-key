import { TestBed } from '@angular/core/testing';
import * as fc from 'fast-check';
import { provideNzNativeDateAdapter } from 'ng-zorro-antd/core/time';
import { en_US, provideNzI18n } from 'ng-zorro-antd/i18n';
import { beforeEach, describe, expect, it } from 'vitest';
import { arbFieldDefs, arbRuleMap, FIELD_RULES } from '../../../testing/arbitraries';
import { effectiveRule } from '../../core/pure/field-rules';
import { DynamicFormComponent } from './dynamic-form.component';
import { planControls, type FieldDef, type FieldRule } from './form-model';

// Feature: skeleton-frontend, Property 1: Controls follow effective rules
// Validates: Requirements 7.8, 7.9, 7.10, 7.11, 7.12, 18.2, 18.3, 18.4

/** Fields, plus a rule map that mixes arbitrary extra keys with entries for real field codes. */
const arbCase = fc
  .tuple(
    arbFieldDefs,
    arbRuleMap,
    fc.array(fc.option(fc.constantFrom(...FIELD_RULES), { nil: undefined }), { minLength: 8, maxLength: 8 }),
  )
  .map(([fields, extra, picks]) => {
    const rules: Record<string, FieldRule> = { ...extra };
    fields.forEach((f, i) => {
      const pick = picks[i];
      if (pick !== undefined) {
        rules[f.code] = pick;
      }
    });
    return { fields: fields.slice(0, 5), rules };
  });

function expectedFor(f: FieldDef, rules: Record<string, FieldRule>) {
  const rule = effectiveRule(f, rules);
  const isFile = f.type === 'FILE';
  return {
    rule,
    hasControl: rule !== 'hidden',
    disabled: rule === 'readonly' || isFile,
    submitted: (rule === 'optional' || rule === 'required') && !isFile,
    requiredValidator: rule === 'required' && !isFile,
  };
}

/** A non-empty UI value the widget of this type accepts. */
function sampleValue(f: FieldDef): unknown {
  switch (f.type) {
    case 'MULTISELECT':
      return [f.options?.[0]?.value ?? 'a'];
    case 'SELECT':
      return f.options?.[0]?.value ?? 'a';
    case 'BOOLEAN':
      return true;
    case 'NUMBER':
    case 'DECIMAL':
      return 1;
    case 'DATE':
    case 'DATETIME':
      return new Date(2024, 0, 2, 3, 4, 5);
    case 'LOCATION':
      return { lat: 1, lng: 2 };
    case 'FILE':
      return [];
    default:
      return 'abc';
  }
}

describe('Property 1: controls follow effective rules', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideNzI18n(en_US), provideNzNativeDateAdapter()] });
  });

  it('planControls mirrors the effective rule of every field', () => {
    fc.assert(
      fc.property(arbCase, ({ fields, rules }) => {
        const plan = planControls(fields, rules);
        expect(plan.length).toBe(fields.length);
        plan.forEach((p, i) => {
          const e = expectedFor(fields[i], rules);
          expect(p.rule).toBe(e.rule);
          expect(p.rendered).toBe(e.hasControl);
          expect(p.disabled).toBe(e.disabled);
          expect(p.submitted).toBe(e.submitted);
          expect(p.validators.includes('required')).toBe(e.requiredValidator);
        });
      }),
      { numRuns: 200 },
    );
  });

  it('DynamicFormComponent builds, disables, validates and submits controls per rule', { timeout: 30000 }, () => {
    fc.assert(
      fc.property(arbCase, ({ fields, rules }) => {
        const fixture = TestBed.createComponent(DynamicFormComponent);
        try {
          fixture.componentRef.setInput('fields', fields);
          fixture.componentRef.setInput('rules', rules);
          fixture.detectChanges();
          const cmp = fixture.componentInstance;
          const controls = cmp.form().controls;

          for (const f of fields) {
            const e = expectedFor(f, rules);
            const c = controls[f.code];
            if (!e.hasControl) {
              expect(c).toBeUndefined();
              continue;
            }
            expect(c).toBeDefined();
            expect(c.disabled).toBe(e.disabled);
            expect(c.enabled).toBe(!e.disabled);
            // Empty value: the required error appears only for enabled `required` controls.
            c.setValue(null);
            if (!e.disabled) {
              expect(!!c.errors?.['required']).toBe(e.requiredValidator);
            }
          }

          // Put a value in every control, disabled ones included, then check what is submitted.
          for (const f of fields) {
            controls[f.code]?.setValue(sampleValue(f));
          }
          const values = cmp.values();
          for (const f of fields) {
            if (!expectedFor(f, rules).submitted) {
              expect(Object.prototype.hasOwnProperty.call(values, f.code)).toBe(false);
            }
          }
          for (const key of Object.keys(values)) {
            const f = fields.find((x) => x.code === key) as FieldDef;
            expect(expectedFor(f, rules).submitted).toBe(true);
          }
        } finally {
          fixture.destroy();
        }
      }),
      { numRuns: 40 },
    );
  });
});

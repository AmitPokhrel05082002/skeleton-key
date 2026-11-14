import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import {
  FIELD_TYPES,
  arbApiError,
  arbDefinition,
  arbFieldDef,
  arbHost,
  arbHostsMap,
  arbRuleMap,
  arbViolation,
} from './arbitraries';

describe('shared arbitraries (smoke)', () => {
  it('arbFieldDef covers all 15 types and gives options to SELECT and MULTISELECT', () => {
    const fields = fc.sample(arbFieldDef, { numRuns: 600, seed: 1 });
    expect(new Set(fields.map((f) => f.type))).toEqual(new Set(FIELD_TYPES));
    for (const f of fields.filter((x) => x.type === 'SELECT' || x.type === 'MULTISELECT')) {
      expect(f.options?.length).toBeGreaterThan(0);
    }
    for (const f of fields.filter((x) => x.type === 'REFERENCE')) {
      expect(f.ref).toBeTruthy();
    }
  });

  it('arbRuleMap, arbViolation, arbApiError, arbDefinition and arbHost produce values', () => {
    expect(fc.sample(arbRuleMap, 10)).toHaveLength(10);
    expect(fc.sample(arbViolation, 10)).toHaveLength(10);
    for (const e of fc.sample(arbApiError, 10)) {
      expect(Array.isArray(e.violations)).toBe(true);
    }
    for (const d of fc.sample(arbDefinition, 10)) {
      const codes = d.entities[0].fields.map((f) => f.code);
      expect(new Set(codes).size).toBe(codes.length);
      expect(d.forms[0].sections[0].fields).toEqual(codes);
    }
    expect(fc.sample(arbHost, 10)).toHaveLength(10);
    expect(fc.sample(arbHostsMap, 10)).toHaveLength(10);
  });
});

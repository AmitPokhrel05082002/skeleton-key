import { describe, expect, it } from 'vitest';
import fc from 'fast-check';
import type { RecordSummary, StatusDef } from '../api/api.types';
import { groupByStatus, OTHER_COLUMN } from './view-helpers';

// Feature: skeleton-frontend, Property 9: Kanban grouping places every record once
// **Validates: Requirements 10.1, 10.3, 10.8**

const HOSTILE = ['constructor', '__proto__', 'toString', 'hasOwnProperty', 'valueOf'];
const CATEGORIES = ['OPEN', 'IN_PROGRESS', 'DONE', 'REJECTED'] as const;

const labelArb = fc.oneof(
  fc.constantFrom('Open', 'In review', 'Done', 'Closed', 'A', 'B'),
  fc.constantFrom(...HOSTILE),
  fc.string({ minLength: 1, maxLength: 6 }),
).filter((l) => l !== OTHER_COLUMN);

const statusArb: fc.Arbitrary<StatusDef> = fc.record({
  label: labelArb,
  category: fc.constantFrom(...CATEGORIES),
});

const statusLabelArb = fc.oneof(
  { weight: 3, arbitrary: labelArb },
  { weight: 1, arbitrary: fc.constantFrom(...HOSTILE) },
  { weight: 1, arbitrary: fc.constantFrom(null, undefined, '', 'Unknown', 'open') },
);

const inputArb = fc.record({
  statuses: fc.array(statusArb, { maxLength: 6 }),
  statuses2: fc.array(statusLabelArb, { maxLength: 25 }),
});

function makeRecords(labels: readonly (string | null | undefined)[]): RecordSummary[] {
  return labels.map((status, i) => ({ id: `r${i}`, data: {}, referenceNo: `R-${i}`, instanceId: null, status }));
}

describe('Property 9: Kanban grouping places every record once', () => {
  it('places each record exactly once, in declaration order, Other last, input order kept', () => {
    fc.assert(
      fc.property(inputArb, ({ statuses, statuses2 }) => {
        const records = makeRecords(statuses2);
        const cols = groupByStatus(records, statuses);

        // every record exactly once; total preserved
        const placed = cols.flatMap((c) => c.records.map((r) => r.id));
        expect(placed.length).toBe(records.length);
        expect([...placed].sort()).toEqual(records.map((r) => r.id).sort());

        // column order: declared columns first, then Other only when non-empty
        const last = cols[cols.length - 1];
        const hasOther = last !== undefined && last.label === OTHER_COLUMN && last.status === undefined;
        const declared = hasOther ? cols.slice(0, -1) : cols;
        expect(declared.map((c) => c.label)).toEqual(statuses.map((s) => s.label));
        if (hasOther) expect(last.records.length).toBeGreaterThan(0);

        // placement rule
        const declaredLabels = new Set(statuses.map((s) => s.label));
        for (const c of declared) {
          for (const r of c.records) expect(r.status).toBe(c.label);
        }
        if (hasOther) {
          for (const r of last.records) {
            expect(r.status == null || !declaredLabels.has(r.status)).toBe(true);
          }
        }
        const expectedOther = records.filter((r) => r.status == null || !declaredLabels.has(r.status));
        expect(hasOther ? last.records.length : 0).toBe(expectedOther.length);

        // input order inside each column
        const index = new Map(records.map((r, i) => [r.id, i]));
        for (const c of cols) {
          const idx = c.records.map((r) => index.get(r.id) as number);
          expect(idx).toEqual([...idx].sort((a, b) => a - b));
        }
      }),
      { numRuns: 200 },
    );
  });

  it('sends hostile and unknown labels to Other when undeclared', () => {
    fc.assert(
      fc.property(fc.array(fc.constantFrom(...HOSTILE, null, undefined, 'nope'), { minLength: 1, maxLength: 15 }), (labels) => {
        const records = makeRecords(labels);
        const cols = groupByStatus(records, [
          { label: 'Open', category: 'OPEN' },
          { label: 'Done', category: 'DONE' },
        ]);
        expect(cols.map((c) => c.label)).toEqual(['Open', 'Done', OTHER_COLUMN]);
        expect(cols[0].records).toEqual([]);
        expect(cols[1].records).toEqual([]);
        expect(cols[2].records).toEqual(records);
      }),
      { numRuns: 100 },
    );
  });

  it('does not duplicate records when declared labels repeat', () => {
    fc.assert(
      fc.property(fc.array(fc.constantFrom('Open', 'Done', 'x'), { maxLength: 15 }), (labels) => {
        const records = makeRecords(labels);
        const cols = groupByStatus(records, [
          { label: 'Open', category: 'OPEN' },
          { label: 'Open', category: 'IN_PROGRESS' },
          { label: 'Done', category: 'DONE' },
        ]);
        expect(cols.flatMap((c) => c.records).length).toBe(records.length);
        expect(new Set(cols.flatMap((c) => c.records.map((r) => r.id))).size).toBe(records.length);
      }),
      { numRuns: 100 },
    );
  });
});

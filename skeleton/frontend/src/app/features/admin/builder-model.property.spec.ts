import { describe, expect, it } from 'vitest';
import * as fc from 'fast-check';
import type { FieldDef, SectionDef } from '../../core/api/api.types';
import { arbDefinition, arbFieldDef, arbFieldDefs } from '../../../testing/arbitraries';
import { applyModel, extractModel, type AppDefinitionJson, type BuilderModel } from './builder-model';

/** Feature: skeleton-frontend, Property 19: Form builder model round trip and isolation. */
/** **Validates: Requirements 15.9, 15.10, 15.11** */

const RUNS = { numRuns: 100 };

const FIELD_PROPS = [
  'code', 'label', 'type', 'required', 'help', 'default', 'options', 'ref', 'min', 'max',
  'maxLength', 'pattern', 'accept', 'maxFiles', 'widget',
];
const SECTION_PROPS = ['title', 'columns', 'fields'];

type Rec = Record<string, unknown>;

/** Independent oracle: keep schema properties only, drop undefined. */
function keep(src: Rec, props: string[]): Rec {
  const out: Rec = {};
  for (const p of props) if (src[p] !== undefined) out[p] = structuredClone(src[p]);
  return out;
}
function cleanFieldOracle(f: FieldDef): Rec {
  const out = keep(f as unknown as Rec, FIELD_PROPS);
  if (Array.isArray(out['options'])) {
    out['options'] = (out['options'] as Rec[]).map((o) => keep(o, ['value', 'label']));
  }
  return out;
}
function cleanSectionOracle(s: SectionDef): Rec {
  return keep(s as unknown as Rec, SECTION_PROPS);
}

const arbExtraValue = fc.oneof(
  fc.string({ maxLength: 10 }),
  fc.integer(),
  fc.boolean(),
  fc.array(fc.string({ maxLength: 5 }), { maxLength: 3 }),
);

const arbExtra = fc.dictionary(fc.constantFrom('x-note', 'x-ui', 'unknownProp', 'meta'), arbExtraValue, {
  maxKeys: 3,
});

/** Definition with several entities and forms, extra unknown properties everywhere. */
const arbBuilderDef = fc
  .record({
    base: arbDefinition,
    entityFields: fc.array(arbFieldDefs, { minLength: 1, maxLength: 3 }),
    entityExtras: fc.array(arbExtra, { minLength: 3, maxLength: 3 }),
    sectionLists: fc.array(
      fc.array(
        fc.record({
          title: fc.option(fc.constantFrom('A', 'B', 'Details'), { nil: undefined }),
          columns: fc.option(fc.constantFrom(1, 2, 3), { nil: undefined }),
          fieldIdx: fc.array(fc.nat(20), { minLength: 1, maxLength: 4 }),
        }),
        { minLength: 1, maxLength: 3 },
      ),
      { minLength: 1, maxLength: 3 },
    ),
    formExtras: fc.array(arbExtra, { minLength: 3, maxLength: 3 }),
    topExtra: arbExtra,
  })
  .map(({ base, entityFields, entityExtras, sectionLists, formExtras, topExtra }) => {
    const entities: Rec[] = [
      ...(structuredClone(base.entities) as unknown as Rec[]).map((e, i) => ({ ...entityExtras[i % 3], ...e })),
      ...entityFields.map((fields, i) => ({
        ...entityExtras[(i + 1) % 3],
        code: `Z_EXTRA_${i}`,
        label: `Extra ${i}`,
        fields: structuredClone(fields),
      })),
    ];
    const forms: Rec[] = [
      ...(structuredClone(base.forms) as unknown as Rec[]).map((f, i) => ({ ...formExtras[i % 3], ...f })),
      ...sectionLists.map((secs, i) => {
        const ent = entities[i % entities.length];
        const codes = (ent['fields'] as FieldDef[]).map((f) => f.code);
        return {
          ...formExtras[(i + 1) % 3],
          code: `Z_FORM_${i}`,
          entity: ent['code'],
          sections: secs.map((s) => {
            const sec: Rec = { fields: s.fieldIdx.map((n) => codes[n % codes.length]) };
            if (s.title !== undefined) sec['title'] = s.title;
            if (s.columns !== undefined) sec['columns'] = s.columns;
            return sec;
          }),
        };
      }),
    ];
    return { ...topExtra, schemaVersion: 1, ...base, entities, forms } as AppDefinitionJson;
  });

/** Picks an entity and a form (or null) of the definition. */
function arbSelection(def: AppDefinitionJson) {
  const entities = def['entities'] as Rec[];
  const forms = def['forms'] as Rec[];
  return fc.record({
    e: fc.nat(entities.length - 1).map((i) => entities[i]['code'] as string),
    f: fc.option(fc.nat(forms.length - 1).map((i) => forms[i]['code'] as string), { nil: null }),
  });
}

const arbDefAndSelection = arbBuilderDef.chain((def) => arbSelection(def).map((sel) => ({ def, ...sel })));

/** Edit operations applied to an extracted model. */
const arbOp = fc.oneof(
  fc.record({ kind: fc.constant('removeField' as const), i: fc.nat(20) }),
  fc.record({ kind: fc.constant('addField' as const), field: arbFieldDef, i: fc.nat(20) }),
  fc.record({ kind: fc.constant('moveField' as const), from: fc.nat(20), to: fc.nat(20) }),
  fc.record({ kind: fc.constant('edit' as const), i: fc.nat(20), label: fc.string({ minLength: 1, maxLength: 20 }) }),
  fc.record({ kind: fc.constant('junk' as const), i: fc.nat(20) }),
  fc.record({ kind: fc.constant('removeSection' as const), i: fc.nat(20) }),
  fc.record({ kind: fc.constant('addSection' as const), i: fc.nat(20), n: fc.nat(5) }),
  fc.record({ kind: fc.constant('moveEntry' as const), s: fc.nat(20), from: fc.nat(20), to: fc.nat(20) }),
  fc.record({ kind: fc.constant('removeEntry' as const), s: fc.nat(20), i: fc.nat(20) }),
);
type Op = typeof arbOp extends fc.Arbitrary<infer T> ? T : never;

function applyOp(m: BuilderModel, op: Op): void {
  switch (op.kind) {
    case 'removeField':
      if (m.fields.length) m.fields.splice(op.i % m.fields.length, 1);
      break;
    case 'addField': {
      // Keep codes unique per entity.
      let code = op.field.code;
      const taken = new Set(m.fields.map((f) => f.code));
      while (taken.has(code)) code += 'x';
      m.fields.splice(op.i % (m.fields.length + 1), 0, { ...op.field, code });
      break;
    }
    case 'moveField':
      if (m.fields.length) {
        const [f] = m.fields.splice(op.from % m.fields.length, 1);
        m.fields.splice(op.to % (m.fields.length + 1), 0, f);
      }
      break;
    case 'edit':
      if (m.fields.length) {
        const f = m.fields[op.i % m.fields.length];
        f.label = op.label;
        f.required = undefined;
        f.help = 'edited';
      }
      break;
    case 'junk':
      if (m.fields.length) {
        (m.fields[op.i % m.fields.length] as unknown as Rec)['uiOnly'] = { selected: true };
      }
      if (m.sections.length) (m.sections[op.i % m.sections.length] as unknown as Rec)['uiOnly'] = 1;
      break;
    case 'removeSection':
      if (m.sections.length) m.sections.splice(op.i % m.sections.length, 1);
      break;
    case 'addSection':
      m.sections.splice(op.i % (m.sections.length + 1), 0, {
        title: 'New',
        columns: 2,
        fields: m.fields.slice(0, op.n).map((f) => f.code),
      });
      break;
    case 'moveEntry':
      if (m.sections.length) {
        const s = m.sections[op.s % m.sections.length];
        if (s.fields.length) {
          const [c] = s.fields.splice(op.from % s.fields.length, 1);
          s.fields.splice(op.to % (s.fields.length + 1), 0, c);
        }
      }
      break;
    case 'removeEntry':
      if (m.sections.length) {
        const s = m.sections[op.s % m.sections.length];
        if (s.fields.length) s.fields.splice(op.i % s.fields.length, 1);
      }
      break;
  }
}

/** The definition expected after writing `m`: only the selected fields and sections replaced. */
function expectedAfter(def: AppDefinitionJson, m: BuilderModel): AppDefinitionJson {
  const exp = structuredClone(def);
  const ent = (exp['entities'] as Rec[]).find((e) => e['code'] === m.entityCode)!;
  ent['fields'] = m.fields.map(cleanFieldOracle);
  if (m.formCode !== null) {
    const form = (exp['forms'] as Rec[]).find((f) => f['code'] === m.formCode)!;
    form['sections'] = m.sections.map(cleanSectionOracle);
  }
  return exp;
}

describe('Property 19: form builder model round trip and isolation', () => {
  it('applyModel(def, extractModel(def, e, f)) deep-equals def', () => {
    fc.assert(
      fc.property(arbDefAndSelection, ({ def, e, f }) => {
        expect(applyModel(def, extractModel(def, e, f))).toStrictEqual(def);
      }),
      RUNS,
    );
  });

  it('edits change only the selected entity fields and form sections, and never mutate the input', () => {
    fc.assert(
      fc.property(arbDefAndSelection, fc.array(arbOp, { maxLength: 10 }), ({ def, e, f }, ops) => {
        const before = structuredClone(def);
        const model = extractModel(def, e, f);
        for (const op of ops) applyOp(model, op);
        const modelBefore = structuredClone(model);
        const out = applyModel(def, model);

        expect(def).toStrictEqual(before);
        // fast-check may build null-prototype objects, which structuredClone turns into plain ones.
        expect(model).toEqual(modelBefore);
        expect(out).toStrictEqual(expectedAfter(def, model));
        expect(out).not.toBe(def);
      }),
      RUNS,
    );
  });

  it('extractModel(applyModel(def, m), e, f) returns the cleaned model', () => {
    fc.assert(
      fc.property(arbDefAndSelection, fc.array(arbOp, { maxLength: 10 }), ({ def, e, f }, ops) => {
        const model = extractModel(def, e, f);
        for (const op of ops) applyOp(model, op);
        const again = extractModel(applyModel(def, model), e, f);
        expect(again).toStrictEqual({
          entityCode: e,
          formCode: f,
          fields: model.fields.map(cleanFieldOracle),
          sections: f === null ? [] : model.sections.map(cleanSectionOracle),
        });
        // JSON serialise, parse, extract: still the same model (Property 19, last clause).
        const viaJson = extractModel(JSON.parse(JSON.stringify(applyModel(def, model))) as AppDefinitionJson, e, f);
        expect(viaJson).toStrictEqual(again);
      }),
      RUNS,
    );
  });

  it('with formCode null only the entity fields change', () => {
    fc.assert(
      fc.property(
        arbBuilderDef,
        fc.nat(10),
        arbFieldDefs,
        fc.array(arbExtra, { minLength: 1, maxLength: 1 }),
        (def, idx, newFields, [junk]) => {
          const entities = def['entities'] as Rec[];
          const e = entities[idx % entities.length]['code'] as string;
          const model = extractModel(def, e, null);
          expect(model.sections).toStrictEqual([]);
          model.fields = newFields.map((nf) => ({ ...nf, ...(junk as object), code: nf.code }) as FieldDef);
          // Sections in the model are ignored for a null form.
          model.sections = [{ fields: ['ignored'] }];
          const before = structuredClone(def);
          const out = applyModel(def, model);
          expect(def).toStrictEqual(before);
          const exp = structuredClone(def);
          (exp['entities'] as Rec[]).find((x) => x['code'] === e)!['fields'] = model.fields.map(cleanFieldOracle);
          expect(out).toStrictEqual(exp);
          expect(out['forms']).toStrictEqual(def['forms']);
        },
      ),
      RUNS,
    );
  });
});
